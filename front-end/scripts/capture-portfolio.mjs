/* global fetch, AbortSignal, document */
import { randomUUID } from 'node:crypto';
import { log } from 'node:console';
import { mkdir, writeFile } from 'node:fs/promises';
import process from 'node:process';
import { fileURLToPath, URL } from 'node:url';
import { chromium } from 'playwright';

if (!process.argv.includes('--acknowledge-synthetic-writes')) {
  throw new Error(
    'Pass --acknowledge-synthetic-writes to seed only the isolated local staging database.',
  );
}

const backend = 'http://127.0.0.1:18080';
const frontend = 'http://127.0.0.1:5173';
const readiness = await fetch(`${backend}/actuator/health/readiness`, {
  signal: AbortSignal.timeout(5_000),
});
const system = await fetch(`${backend}/api/v1/system`, { signal: AbortSignal.timeout(5_000) });
if (readiness.status !== 200 || (await readiness.json()).status !== 'UP' || !system.ok) {
  throw new Error('The isolated local staging backend must be ready before capturing a demo.');
}
if ((await system.json()).cacheRequired !== true) {
  throw new Error('Refusing to seed the native development profile; start local staging instead.');
}
const frontendResponse = await fetch(frontend, { signal: AbortSignal.timeout(5_000) });
if (!frontendResponse.ok) throw new Error('Start the local Vite frontend on 127.0.0.1:5173.');

const csrfResponse = await fetch(`${backend}/api/v1/auth/csrf`);
if (!csrfResponse.ok) throw new Error('Could not obtain a local CSRF token.');
const csrf = await csrfResponse.json();
const csrfCookie = csrfResponse.headers.get('set-cookie')?.split(';', 1)[0];
if (!csrfCookie || !csrf.token || !csrf.headerName)
  throw new Error('Local CSRF metadata is missing.');

async function api(path, method, body, bearer) {
  const response = await fetch(`${backend}${path}`, {
    method,
    headers: {
      'Content-Type': 'application/json',
      Cookie: csrfCookie,
      [csrf.headerName]: csrf.token,
      ...(bearer ? { Authorization: `Bearer ${bearer}` } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(10_000),
    redirect: 'error',
  });
  if (!response.ok) throw new Error(`${method} ${path} returned HTTP ${response.status}.`);
  return response.json();
}

const suffix = randomUUID().slice(0, 10);
const email = `portfolio-${suffix}@example.test`;
const password = `Demo-${randomUUID()}-strong`;
const session = await api('/api/v1/auth/register', 'POST', {
  email,
  password,
  displayName: 'Portfolio Demo',
  organizationName: 'Sample Engineering',
});
const token = session.accessToken;
if (!token) throw new Error('Demo registration did not return an access token.');
const project = await api(
  '/api/v1/projects',
  'POST',
  {
    name: 'Checkout API',
    slug: `checkout-api-${suffix}`,
  },
  token,
);
const key = await api(
  `/api/v1/projects/${project.id}/keys`,
  'POST',
  {
    label: 'Synthetic portfolio data',
  },
  token,
);
if (!key.secret?.startsWith('ei_')) throw new Error('Local ingestion key creation failed.');

const cases = [
  {
    count: 14,
    exceptionType: 'java.sql.SQLTransientConnectionException',
    message: 'Checkout database connection timed out',
    stackTrace: 'at com.example.checkout.OrderRepository.save(OrderRepository.java:84)',
  },
  {
    count: 7,
    exceptionType: 'java.lang.IllegalStateException',
    message: 'Payment authorization returned an unexpected state',
    stackTrace: 'at com.example.checkout.PaymentService.authorize(PaymentService.java:62)',
  },
  {
    count: 4,
    exceptionType: 'org.springframework.web.client.HttpServerErrorException',
    message: 'Inventory service returned HTTP 503',
    stackTrace: 'at com.example.checkout.InventoryClient.reserve(InventoryClient.java:37)',
  },
];
for (const item of cases) {
  for (let index = 0; index < item.count; index++) {
    const response = await fetch(`${backend}/ingest/v1/events`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-API-Key': key.secret },
      body: JSON.stringify({
        eventId: randomUUID(),
        occurredAt: new Date().toISOString(),
        environment: 'portfolio-demo',
        exceptionType: item.exceptionType,
        message: item.message,
        stackTrace: item.stackTrace,
      }),
      signal: AbortSignal.timeout(10_000),
    });
    await response.arrayBuffer();
    if (response.status !== 202)
      throw new Error(`Synthetic ingestion returned HTTP ${response.status}.`);
  }
}

const incidents = await api(
  `/api/v1/projects/${project.id}/incidents?size=50`,
  'GET',
  undefined,
  token,
);
if (!Array.isArray(incidents.items) || incidents.items.length !== cases.length) {
  throw new Error('Synthetic events did not group into the three expected incidents.');
}
const resolved =
  incidents.items.find((item) => item.title.includes('Inventory')) ?? incidents.items[0];
await api(
  `/api/v1/incidents/${resolved.id}/resolve`,
  'POST',
  {
    summary: 'Restored inventory service connectivity',
    rootCause: 'The inventory dependency was unavailable during the synthetic checkout request.',
  },
  token,
);

const output = fileURLToPath(new URL('../../docs/screenshots/', import.meta.url));
const local = fileURLToPath(new URL('../../.local/', import.meta.url));
await mkdir(output, { recursive: true });
await mkdir(local, { recursive: true });
await writeFile(
  `${local}/portfolio-demo-access.txt`,
  `Synthetic local staging account\nEmail: ${email}\nPassword: ${password}\nURL: ${frontend}\n`,
  { mode: 0o600 },
);

const browser = await chromium.launch();
try {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', (error) => errors.push(error.message));
  await page.goto(frontend);
  await page.getByRole('status').filter({ hasText: 'Services connected' }).waitFor();
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password').fill(password);
  await page.locator('.identity-form button').click();
  await page.getByText('Checkout API', { exact: true }).first().waitFor();
  await page.getByRole('heading', { name: 'Project analytics' }).waitFor();
  await page.getByRole('heading', { name: 'Incidents', exact: true }).waitFor();
  await page.locator('.analytics-metrics').getByText('25', { exact: true }).waitFor();
  await page.getByLabel('Analytics period').selectOption('7');
  await page.waitForFunction(() => document.querySelectorAll('.analytics-bars li').length === 7);
  await page.screenshot({ path: `${output}/portfolio-workspace.png` });
  await page
    .locator('.analytics-dashboard')
    .screenshot({ path: `${output}/portfolio-analytics.png` });
  await page
    .locator('.incident-workspace')
    .screenshot({ path: `${output}/portfolio-incidents.png` });

  const mobileContext = await browser.newContext({
    viewport: { width: 390, height: 844 },
    isMobile: true,
  });
  await mobileContext.addCookies(await context.cookies());
  const mobile = await mobileContext.newPage();
  mobile.on('pageerror', (error) => errors.push(error.message));
  await mobile.goto(frontend);
  await mobile.getByRole('heading', { name: 'Project analytics' }).waitFor();
  await mobile.screenshot({ path: `${output}/portfolio-mobile.png` });
  await mobile
    .locator('.incident-workspace')
    .evaluate((element) => element.scrollIntoView({ block: 'start' }));
  await mobile.screenshot({ path: `${output}/portfolio-mobile-incidents.png` });
  if (errors.length) throw new Error(`Browser errors: ${errors.join('; ')}`);
  await mobileContext.close();
  await context.close();
} finally {
  await browser.close();
}
log('Captured five portfolio screenshots from synthetic local staging data.');
log('The disposable demo sign-in is saved in ignored .local/portfolio-demo-access.txt.');
