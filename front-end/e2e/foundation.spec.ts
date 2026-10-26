import { expect, test } from '@playwright/test';

test('workspace loads and setup is usable without horizontal overflow', async ({
  page,
}, testInfo) => {
  await page.goto('/login');
  await expect(page.getByRole('heading', { name: 'Sign in to your workspace' })).toBeVisible();
  if (process.env.E2E_REQUIRE_READY === 'true') {
    const health = await page.request.get('/actuator/health/readiness');
    expect((await health.json()).status).toBe('UP');
  }
  await expect(page.locator('.sidebar')).toHaveCount(0);
  const fits = await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth);
  expect(fits).toBe(true);
  await page.getByRole('link', { name: 'Create an account' }).click();
  await expect(page).toHaveURL(/\/register$/);
  await expect(page.getByRole('heading', { name: 'Create your account' })).toBeVisible();
  await page.goBack();
  await expect(page.getByRole('heading', { name: 'Sign in to your workspace' })).toBeVisible();
  if (testInfo.project.name === 'mobile') await expect(page.locator('.mobile-brand')).toBeVisible();
});
