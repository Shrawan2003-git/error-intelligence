import { expect, test } from '@playwright/test';

test('password recovery sends a generic request and accepts a one-time link without exposing its token', async ({
  page,
}) => {
  const token = 'abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG';
  await page.route('**/api/v1/auth/refresh', (route) => route.fulfill({ status: 401 }));
  await page.route('**/api/v1/auth/csrf', (route) =>
    route.fulfill({ json: { token: 'browser-csrf', headerName: 'X-XSRF-TOKEN' } }),
  );
  await page.route('**/api/v1/auth/password/forgot', async (route) => {
    expect(route.request().headers()['x-xsrf-token']).toBe('browser-csrf');
    expect(route.request().postDataJSON()).toEqual({ email: 'owner@example.test' });
    await route.fulfill({ status: 202 });
  });
  await page.route('**/api/v1/auth/password/reset', async (route) => {
    expect(route.request().headers()['x-xsrf-token']).toBe('browser-csrf');
    expect(route.request().postDataJSON()).toEqual({
      token,
      newPassword: 'a-strong-new-password-123',
    });
    await route.fulfill({ status: 204 });
  });

  await page.goto('/login');
  await page.getByRole('link', { name: 'Forgot password?' }).click();
  await page.getByLabel('Email address').fill('owner@example.test');
  await page.getByRole('button', { name: 'Send reset link' }).click();
  await expect(page.getByText(/If an eligible account exists/)).toBeVisible();

  await page.goto(`/reset-password#token=${token}`);
  await expect(page).toHaveURL(/\/reset-password$/);
  await page.getByLabel('New password', { exact: true }).fill('a-strong-new-password-123');
  await page.getByLabel('Confirm new password').fill('a-strong-new-password-123');
  await page.getByRole('button', { name: 'Reset password' }).click();
  await expect(
    page.getByText('Your password has been changed. Sign in with your new password.'),
  ).toBeVisible();
  await page.getByRole('link', { name: 'Continue to sign in' }).click();
  await expect(page.getByRole('heading', { name: 'Sign in to your workspace' })).toBeVisible();
});
