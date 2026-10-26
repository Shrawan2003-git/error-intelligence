import { expect, test } from '@playwright/test';

test('the edge proxy rejects forged client IPs for login rate limits', async ({ request }) => {
  test.skip(process.env.E2E_PROXY !== 'true', 'Requires the isolated real Nginx/API/Redis stack.');
  const csrfResponse = await request.get('/api/v1/auth/csrf');
  expect(csrfResponse.ok()).toBeTruthy();
  const csrf = (await csrfResponse.json()) as { token: string; headerName: string };
  const run = Date.now();
  let limited = false;
  for (let index = 0; index < 31; index++) {
    const response = await request.post('/api/v1/auth/login', {
      headers: {
        [csrf.headerName]: csrf.token,
        'X-Forwarded-For': `198.51.100.${index + 1}`,
      },
      data: { email: `proxy-${run}-${index}@example.test`, password: 'synthetic-invalid-password' },
    });
    expect([401, 429]).toContain(response.status());
    if (response.status() === 429) {
      limited = true;
      break;
    }
  }
  expect(limited, 'Changing an untrusted header must not bypass the 30/minute IP bucket').toBe(
    true,
  );
});
