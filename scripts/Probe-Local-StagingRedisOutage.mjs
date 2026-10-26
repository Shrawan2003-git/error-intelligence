const origin = 'http://127.0.0.1:18080';
const readiness = await fetch(`${origin}/actuator/health/readiness`, { signal: AbortSignal.timeout(5_000) });
const health = await readiness.json();
if (readiness.status !== 503 || health.status !== 'DOWN') {
  throw new Error(`Expected readiness DOWN/503 during the staged Redis outage; got ${readiness.status}/${health.status}.`);
}
const csrfResponse = await fetch(`${origin}/api/v1/auth/csrf`, { signal: AbortSignal.timeout(5_000) });
const csrf = await csrfResponse.json();
const cookie = csrfResponse.headers.get('set-cookie')?.split(';', 1)[0];
if (!cookie || !csrf.token || !csrf.headerName) throw new Error('CSRF token unavailable during staged Redis outage.');
const login = await fetch(`${origin}/api/v1/auth/login`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json', Cookie: cookie, [csrf.headerName]: csrf.token },
  body: JSON.stringify({ email: 'outage-check@example.test', password: 'wrong-synthetic-password' }),
  signal: AbortSignal.timeout(10_000),
});
const result = await login.json();
if (login.status !== 503 || result.code !== 'AUTH_UNAVAILABLE') {
  throw new Error(`Expected authentication fail-closed 503/AUTH_UNAVAILABLE; got ${login.status}/${result.code}.`);
}
console.log('Local staging Redis outage passed: readiness DOWN/503 and login failed closed with AUTH_UNAVAILABLE.');
