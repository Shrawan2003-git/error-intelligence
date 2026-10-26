import { randomUUID } from 'node:crypto';
import { mkdir, readFile, readdir } from 'node:fs/promises';
import process from 'node:process';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';

const base = process.env.E2E_BASE_URL ?? 'http://127.0.0.1:5173';
const output = new URL('../../docs/screenshots/', import.meta.url);
const outbox = new URL('../../.local/mail/', import.meta.url);
async function accountLink(email, kind) {
  const deadline = Date.now() + 15_000;
  const pattern =
    kind === 'verify'
      ? /https?:\/\/\S+\/verify-email#token=[A-Za-z0-9_-]{43}/
      : /https?:\/\/\S+\/reset-password#token=[A-Za-z0-9_-]{43}/;
  while (Date.now() < deadline) {
    const files = await readdir(outbox).catch(() => []);
    for (const file of files) {
      if (!file.endsWith('.txt')) continue;
      const message = await readFile(new URL(file, outbox), 'utf8');
      if (!message.startsWith(`To: ${email}\n`)) continue;
      const link = message.match(pattern)?.[0];
      if (link) return link;
    }
    await delay(200);
  }
  throw new Error(`No local ${kind} email arrived.`);
}
const ready = await fetch(`${base}/actuator/health/readiness`);
if (!ready.ok || (await ready.json()).status !== 'UP') {
  throw new Error('The isolated frontend proxy/backend is not ready.');
}
await mkdir(output, { recursive: true });
const browser = await chromium.launch({ headless: true });
try {
  const page = await browser.newPage({
    viewport: { width: 1440, height: 900 },
    deviceScaleFactor: 1,
  });
  const pageErrors = [];
  page.on('pageerror', (error) => pageErrors.push(error.message));
  const suffix = randomUUID().slice(0, 8);
  await page.goto(`${base}/login`);
  await page.getByRole('heading', { name: 'Sign in to your workspace' }).waitFor();
  await page.waitForTimeout(500);
  await page.screenshot({ path: fileURLToPath(new URL('guide-sign-in.png', output)) });
  await page.goto(`${base}/forgot-password`);
  await page.getByRole('heading', { name: 'Forgot your password?' }).waitFor();
  await page.screenshot({ path: fileURLToPath(new URL('guide-forgot-password.png', output)) });
  await page.goto(`${base}/register`);
  await page.getByRole('heading', { name: 'Create your account' }).waitFor();
  await page.waitForTimeout(500);
  await page.screenshot({ path: fileURLToPath(new URL('guide-create-account.png', output)) });
  await page.getByLabel('Full name').fill('Guide User');
  await page.getByLabel('Workspace name').fill(`Guide ${suffix}`);
  const email = `guide-${suffix}@example.test`;
  const password = `Synthetic-guide-${suffix}-password`;
  await page.getByLabel('Email address').fill(email);
  await page.getByLabel('Password', { exact: true }).fill(password);
  await page.getByRole('button', { name: 'Create workspace' }).click();
  await page.getByRole('heading', { name: 'Check your email' }).waitFor();
  await page.screenshot({ path: fileURLToPath(new URL('guide-check-email.png', output)) });
  const link = new URL(await accountLink(email, 'verify'));
  await page.goto(`${base}${link.pathname}${link.hash}`);
  await page.getByRole('heading', { name: 'Verify your email' }).waitFor();
  await page.screenshot({ path: fileURLToPath(new URL('guide-verify-email.png', output)) });
  await page.getByRole('button', { name: 'Verify email' }).click();
  await page.getByText('Your email is verified. You can now sign in.').waitFor();
  await page.goto(`${base}/forgot-password`);
  await page.getByLabel('Email address').fill(email);
  await page.getByRole('button', { name: 'Send reset link' }).click();
  await page.getByText(/If an eligible account exists/).waitFor();
  const resetLink = new URL(await accountLink(email, 'reset'));
  await page.goto(`${base}${resetLink.pathname}${resetLink.hash}`);
  await page.getByRole('heading', { name: 'Choose a new password' }).waitFor();
  await page.screenshot({ path: fileURLToPath(new URL('guide-reset-password.png', output)) });
  const newPassword = `Synthetic-guide-new-${suffix}-password`;
  await page.getByLabel('New password', { exact: true }).fill(newPassword);
  await page.getByLabel('Confirm new password').fill(newPassword);
  await page.getByRole('button', { name: 'Reset password' }).click();
  await page.getByText('Your password has been changed. Sign in with your new password.').waitFor();
  await page.getByRole('link', { name: 'Continue to sign in' }).click();
  await page.getByLabel('Email address').fill(email);
  await page.getByLabel('Password', { exact: true }).fill(newPassword);
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await page.getByRole('heading', { name: 'Your first project starts here' }).waitFor();
  await page.getByRole('button', { name: 'Create project' }).first().click();
  await page.getByLabel('Project name').fill('Guide demo API');
  await page.getByLabel('URL slug').fill(`guide-demo-${suffix}`);
  await page.getByRole('dialog').getByRole('button', { name: 'Create project' }).click();
  await page.getByRole('heading', { name: 'Guide demo API' }).waitFor();
  const projectPath = new URL(page.url()).pathname;
  await page.goto(`${base}/app/projects`);
  await page.getByRole('heading', { name: 'Projects', exact: true }).waitFor();
  await page.getByText('Guide demo API', { exact: true }).waitFor();
  await page.waitForTimeout(500);
  await page.screenshot({ path: fileURLToPath(new URL('guide-projects.png', output)) });
  await page.getByRole('link', { name: /Open project/ }).click();
  await page.getByRole('link', { name: 'Ingestion keys' }).click();
  await page.getByRole('button', { name: 'Create key', exact: true }).click();
  await page.getByLabel('Key label').fill('Synthetic guide sender');
  await page.getByRole('dialog').getByRole('button', { name: 'Create key' }).click();
  const key = await page.locator('.secret-value').innerText();
  await page.getByRole('button', { name: 'Done' }).click();
  await page.locator('table strong', { hasText: 'Synthetic guide sender' }).waitFor();
  await page.waitForTimeout(500);
  await page.screenshot({ path: fileURLToPath(new URL('guide-ingestion-keys.png', output)) });

  const response = await page.request.post(`${base}/ingest/v1/events`, {
    headers: { 'X-API-Key': key },
    data: {
      eventId: randomUUID(),
      occurredAt: new Date().toISOString(),
      environment: 'guide',
      exceptionType: 'java.lang.IllegalStateException',
      message: 'Synthetic checkout failure for local guide',
      stackTrace: 'at example.CheckoutService.submit(CheckoutService.java:42)',
    },
  });
  if (response.status() !== 202) throw new Error('Synthetic event ingestion failed.');
  await page.goto(`${base}/app/incidents`);
  await page.getByRole('link', { name: 'java.lang.IllegalStateException' }).click();
  await page.getByRole('heading', { level: 1, name: 'java.lang.IllegalStateException' }).waitFor();
  const incidentPath = new URL(page.url()).pathname;
  await page.waitForTimeout(500);
  await page.screenshot({
    path: fileURLToPath(new URL('guide-incident.png', output)),
    fullPage: true,
  });
  const routes = [
    '/app',
    '/app/projects',
    projectPath,
    `${projectPath}/keys`,
    '/app/incidents',
    incidentPath,
    `${incidentPath}/diagnostics`,
    `${incidentPath}/related`,
    '/app/analytics',
    '/app/team',
    '/app/audit',
    '/app/setup',
    '/app/account',
    '/app/status',
  ];
  for (const route of routes) {
    await page.goto(`${base}${route}`);
    await page.locator('#main-content h1').first().waitFor({ timeout: 10000 });
    if (await page.getByRole('heading', { name: 'Page not found' }).count()) {
      throw new Error(`Missing page: ${route}`);
    }
  }
  if (pageErrors.length) throw new Error(`Browser errors: ${pageErrors.join('; ')}`);
  console.log('Captured nine screenshots and checked all 16 redesigned routes.');
} finally {
  await browser.close();
}
