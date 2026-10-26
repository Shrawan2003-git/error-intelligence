import { randomUUID } from 'node:crypto';
import { expect, test } from '@playwright/test';
import { waitForVerificationLink } from './local-mail';

test('real API flow preserves grouping, tenant scope, and multi-tab sessions', async ({
  page,
  context,
}) => {
  test.skip(
    process.env.E2E_LIVE !== 'true',
    'Enable explicitly against disposable or synthetic staging data.',
  );
  const suffix = randomUUID().slice(0, 8);
  const email = `acceptance-${suffix}@example.test`;
  const password = `Synthetic-test-${suffix}-password`;
  await page.goto('/register');
  await page.getByLabel('Full name').fill('Acceptance User');
  await page.getByLabel('Workspace name').fill(`Acceptance ${suffix}`);
  await page.getByLabel('Email address').fill(email);
  await page.getByLabel('Password', { exact: true }).fill(password);
  await page.getByRole('button', { name: 'Create workspace', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Check your email' })).toBeVisible();
  const verification = new URL(await waitForVerificationLink(email));
  await page.goto(`${verification.pathname}${verification.hash}`);
  await page.getByRole('button', { name: 'Verify email' }).click();
  await expect(page.getByText('Your email is verified. You can now sign in.')).toBeVisible();
  await page.getByRole('link', { name: 'Continue to sign in' }).click();
  await page.getByLabel('Email address').fill(email);
  await page.getByLabel('Password', { exact: true }).fill(password);
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Your first project starts here' })).toBeVisible();
  await page.getByRole('button', { name: 'Create project', exact: true }).first().click();
  await page.getByLabel('Project name').fill(`Browser project ${suffix}`);
  await page.getByLabel('URL slug').fill(`browser-${suffix}`);
  await page.getByRole('dialog').getByRole('button', { name: 'Create project' }).click();
  await expect(page.getByRole('heading', { name: `Browser project ${suffix}` })).toBeVisible();
  await page.getByRole('link', { name: 'Ingestion keys' }).click();
  await page.getByRole('button', { name: 'Create key', exact: true }).click();
  await page.getByLabel('Key label').fill('Synthetic acceptance');
  await page.getByRole('dialog').getByRole('button', { name: 'Create key' }).click();
  const key = await page.locator('.secret-value').innerText();
  await page.getByRole('button', { name: 'Done' }).click();
  const event = {
    eventId: randomUUID(),
    occurredAt: new Date().toISOString(),
    environment: 'acceptance',
    exceptionType: 'BrowserAcceptanceException',
    message: 'Synthetic browser failure',
    stackTrace: 'at com.example.Acceptance.run(Acceptance.java:42)',
  };
  const first = await context.request.post('/ingest/v1/events', {
    headers: { 'X-API-Key': key },
    data: event,
  });
  expect(first.status()).toBe(202);
  expect((await first.json()).duplicate).toBe(false);
  const duplicate = await context.request.post('/ingest/v1/events', {
    headers: { 'X-API-Key': key },
    data: event,
  });
  expect(duplicate.status()).toBe(202);
  expect((await duplicate.json()).duplicate).toBe(true);
  await page.goto('/app/incidents');
  await expect(page.getByRole('link', { name: 'BrowserAcceptanceException' })).toBeVisible();
  await page.getByRole('link', { name: 'BrowserAcceptanceException' }).click();
  await expect(page.getByText('1 occurrences')).toBeVisible();
  const secondTab = await context.newPage();
  await Promise.all([page.reload(), secondTab.goto('/app/incidents')]);
  await expect(page.locator('.sidebar-account').getByText('Acceptance User')).toBeVisible();
  await expect(secondTab.locator('.sidebar-account').getByText('Acceptance User')).toBeVisible();
  await secondTab.close();
  await page.getByLabel('Comment', { exact: true }).fill('Synthetic browser investigation');
  await page.getByRole('button', { name: 'Post comment' }).click();
  await expect(page.getByText('Synthetic browser investigation', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Verify & resolve' }).click();
  await page.getByLabel('Resolution summary').fill('Verified synthetic fix');
  await page.getByLabel('Root cause').fill('Synthetic acceptance fixture');
  await page.getByRole('button', { name: 'Verify resolution' }).click();
  await expect(page.getByText('Verified synthetic fix', { exact: true })).toBeVisible();
  await page.getByRole('link', { name: 'Open account' }).click();
  await page.getByLabel('Workspace name').fill(`Second ${suffix}`);
  await page.getByRole('button', { name: 'Create workspace' }).click();
  await page.goto('/app/projects');
  await expect(page.getByText(`Browser project ${suffix}`, { exact: true })).toHaveCount(0);
  await page.getByRole('link', { name: 'Open account' }).click();
  await page.getByRole('button', { name: 'Sign out' }).click();
  await expect(page.getByRole('heading', { name: 'Sign in to your workspace' })).toBeVisible();
});
