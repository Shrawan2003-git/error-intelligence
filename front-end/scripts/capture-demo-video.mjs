/* global URL, fetch, console */
import { randomUUID } from 'node:crypto';
import { mkdir, readFile, readdir } from 'node:fs/promises';
import process from 'node:process';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';

const base = process.env.E2E_BASE_URL ?? 'http://127.0.0.1:5174';
const output = new URL('../../.local/demo-video/stills/', import.meta.url);
const outbox = new URL('../../.local/mail/', import.meta.url);
const ready = await fetch(`${base}/actuator/health/readiness`);
if (!ready.ok || (await ready.json()).status !== 'UP') {
  throw new Error('The isolated local frontend and backend must be ready.');
}
await mkdir(output, { recursive: true });

async function verificationLink(email) {
  const deadline = Date.now() + 20_000;
  while (Date.now() < deadline) {
    for (const file of await readdir(outbox).catch(() => [])) {
      if (!file.endsWith('.txt')) continue;
      const message = await readFile(new URL(file, outbox), 'utf8');
      if (!message.startsWith(`To: ${email}\n`)) continue;
      const link = message.match(/https?:\/\/\S+\/verify-email#token=[A-Za-z0-9_-]{43}/)?.[0];
      if (link) return new URL(link);
    }
    await delay(200);
  }
  throw new Error('The synthetic verification email did not arrive.');
}

const browser = await chromium.launch({ headless: true });
try {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  const suffix = randomUUID().slice(0, 8);
  const email = `video-${suffix}@example.test`;
  const password = `Synthetic-video-${suffix}-password`;

  await page.goto(`${base}/register`);
  await page.getByLabel('Full name').fill('Video Demo');
  await page.getByLabel('Workspace name').fill('Sample Engineering');
  await page.getByLabel('Email address').fill(email);
  await page.getByLabel('Password', { exact: true }).fill(password);
  await page.getByRole('button', { name: 'Create workspace', exact: true }).click();
  await page.getByRole('heading', { name: 'Check your email' }).waitFor();
  const link = await verificationLink(email);
  await page.goto(`${base}${link.pathname}${link.hash}`);
  await page.getByRole('button', { name: 'Verify email' }).click();
  await page.getByText('Your email is verified. You can now sign in.').waitFor();
  await page.getByRole('link', { name: 'Continue to sign in' }).click();
  await page.getByLabel('Email address').fill(email);
  await page.getByLabel('Password', { exact: true }).fill(password);
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await page.getByRole('heading', { name: 'Your first project starts here' }).waitFor();

  await page.getByRole('button', { name: 'Create project', exact: true }).first().click();
  await page.getByLabel('Project name').fill('Checkout API');
  await page.getByLabel('URL slug').fill(`checkout-${suffix}`);
  await page.getByRole('dialog').getByRole('button', { name: 'Create project' }).click();
  await page.getByRole('heading', { name: 'Checkout API' }).waitFor();
  const projectRoute = new URL(page.url()).pathname;

  async function shot(route, name) {
    await page.goto(`${base}${route}`);
    await page.locator('#main-content h1').first().waitFor();
    await page.waitForTimeout(550);
    await page.screenshot({ path: fileURLToPath(new URL(`${name}.png`, output)) });
  }

  await shot('/app', 'overview');
  await shot('/app/projects', 'projects');
  await shot(projectRoute, 'project-detail');
  await page.goto(`${base}${projectRoute}/keys`);
  await page.getByRole('button', { name: 'Create key', exact: true }).click();
  await page.getByLabel('Key label').fill('Synthetic video sender');
  await page.getByRole('dialog').getByRole('button', { name: 'Create key' }).click();
  const secret = await page.locator('.secret-value').innerText();
  await page.getByRole('button', { name: 'Done' }).click();
  await shot(`${projectRoute}/keys`, 'ingestion-keys');

  const cases = [
    {
      count: 14,
      exceptionType: 'java.sql.SQLTransientConnectionException',
      message: 'Checkout database connection timed out',
      stackTrace: 'at example.checkout.OrderRepository.save(OrderRepository.java:84)',
    },
    {
      count: 7,
      exceptionType: 'java.lang.IllegalStateException',
      message: 'Payment authorization returned an unexpected state',
      stackTrace: 'at example.checkout.PaymentService.authorize(PaymentService.java:62)',
    },
    {
      count: 4,
      exceptionType: 'org.springframework.web.client.HttpServerErrorException',
      message: 'Inventory service returned HTTP 503',
      stackTrace: 'at example.checkout.InventoryClient.reserve(InventoryClient.java:37)',
    },
  ];
  for (const item of cases) {
    for (let index = 0; index < item.count; index++) {
      const response = await page.request.post(`${base}/ingest/v1/events`, {
        headers: { 'X-API-Key': secret },
        data: {
          eventId: randomUUID(),
          occurredAt: new Date().toISOString(),
          environment: 'synthetic-video',
          exceptionType: item.exceptionType,
          message: item.message,
          stackTrace: item.stackTrace,
        },
      });
      if (response.status() !== 202)
        throw new Error(`Synthetic ingestion failed: ${response.status()}`);
    }
  }

  await shot('/app/incidents', 'incidents');
  await page
    .getByRole('link', { name: /HttpServerErrorException/ })
    .first()
    .click();
  const incidentRoute = new URL(page.url()).pathname;
  await shot(incidentRoute, 'incident-detail');
  await shot(`${incidentRoute}/diagnostics`, 'diagnostics');
  await shot(`${incidentRoute}/related`, 'related');
  await page.goto(`${base}${incidentRoute}`);
  await page
    .getByLabel('Comment', { exact: true })
    .fill('Inventory dependency returned HTTP 503 during checkout.');
  await page.getByRole('button', { name: 'Post comment' }).click();
  await page.getByRole('button', { name: 'Verify & resolve' }).click();
  await page.getByLabel('Resolution summary').fill('Restored inventory service connectivity');
  await page
    .getByLabel('Root cause')
    .fill('The inventory dependency was unavailable during checkout.');
  await page.getByRole('button', { name: 'Verify resolution' }).click();
  await page.getByText('Restored inventory service connectivity', { exact: true }).waitFor();
  await shot(incidentRoute, 'resolved-incident');
  await shot('/app/analytics', 'analytics');
  await shot('/app/team', 'team');
  await shot('/app/audit', 'audit');
  await shot('/app/setup', 'setup');
  await shot('/app/status', 'status');
  await shot('/app/account', 'account');
  console.log('Captured 14 current UI screens with synthetic data.');
} finally {
  await browser.close();
}
