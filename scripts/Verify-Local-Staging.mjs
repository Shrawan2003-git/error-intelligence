import { randomUUID } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const origin = 'http://127.0.0.1:18080';
const readiness = await fetch(`${origin}/actuator/health/readiness`, { signal: AbortSignal.timeout(5_000) });
if (readiness.status !== 200 || (await readiness.json()).status !== 'UP') {
  throw new Error('Local staging backend is not ready.');
}
const system = await (await fetch(`${origin}/api/v1/system`)).json();
if (system.cacheRequired !== true) throw new Error('Local staging must require Redis.');

const csrfResponse = await fetch(`${origin}/api/v1/auth/csrf`);
if (csrfResponse.status !== 200) throw new Error(`CSRF request returned HTTP ${csrfResponse.status}.`);
const csrf = await csrfResponse.json();
const csrfCookie = csrfResponse.headers.get('set-cookie')?.split(';', 1)[0];
if (!csrfCookie || !csrf.token || !csrf.headerName) throw new Error('CSRF cookie/token is missing.');

async function request(path, method, body, bearer) {
  const headers = {
    'Content-Type': 'application/json',
    Cookie: csrfCookie,
    [csrf.headerName]: csrf.token,
  };
  if (bearer) headers.Authorization = `Bearer ${bearer}`;
  const response = await fetch(`${origin}${path}`, {
    method,
    headers,
    body: body ? JSON.stringify(body) : undefined,
    signal: AbortSignal.timeout(10_000),
    redirect: 'error',
  });
  if (!response.ok) throw new Error(`${method} ${path} returned HTTP ${response.status}.`);
  return response.json();
}

const unique = randomUUID().slice(0, 12);
const session = await request('/api/v1/auth/register', 'POST', {
  email: `phase7-${unique}@example.test`,
  password: `Stage-${randomUUID()}-strong`,
  displayName: 'Phase 7 synthetic owner',
  organizationName: `Phase 7 local staging ${unique}`,
});
if (!session.accessToken) throw new Error('Registration did not return an access token.');
for (let attempt = 0; attempt < 6; attempt++) {
  const rejected = await fetch(`${origin}/api/v1/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Cookie: csrfCookie, [csrf.headerName]: csrf.token },
    body: JSON.stringify({ email: `phase7-${unique}@example.test`, password: 'wrong-synthetic-password' }),
    signal: AbortSignal.timeout(10_000),
  });
  await rejected.arrayBuffer();
  const expected = attempt === 5 ? 429 : 401;
  if (rejected.status !== expected) throw new Error(`Redis-backed login limit returned HTTP ${rejected.status}; expected ${expected}.`);
}
const project = await request('/api/v1/projects', 'POST', {
  name: 'Synthetic staging project',
  slug: `stage-${unique}`,
}, session.accessToken);
if (!project.id) throw new Error('Project creation did not return an ID.');
const key = await request(`/api/v1/projects/${project.id}/keys`, 'POST', {
  label: 'Phase 7 synthetic load',
}, session.accessToken);
if (!key.secret?.startsWith('ei_')) throw new Error('Staging ingestion key was not created.');

const event = {
  eventId: randomUUID(),
  occurredAt: new Date().toISOString(),
  environment: 'phase7-local-staging',
  exceptionType: 'com.example.Phase7SmokeFailure',
  message: 'Synthetic staging smoke event',
  stackTrace: 'at com.example.Phase7.run(Phase7.java:42)',
};
const ingested = await fetch(`${origin}/ingest/v1/events`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json', 'X-API-Key': key.secret },
  body: JSON.stringify(event),
  signal: AbortSignal.timeout(10_000),
});
if (ingested.status !== 202) throw new Error(`Synthetic ingestion returned HTTP ${ingested.status}.`);
await ingested.arrayBuffer();
const incidents = await request(`/api/v1/projects/${project.id}/incidents`, 'GET', null, session.accessToken);
if (!Array.isArray(incidents.items) || incidents.items.length < 1) {
  throw new Error('Synthetic event did not appear in the incident list.');
}

const loadScript = fileURLToPath(new URL('./Test-StagingLoad.mjs', import.meta.url));
const load = spawnSync(process.execPath, [loadScript, origin, '--acknowledge-synthetic-writes', '--allow-http-loopback'], {
  env: { ...process.env, EIP_STAGING_INGEST_KEY: key.secret },
  stdio: 'inherit',
  timeout: 90_000,
});
if (load.error) throw load.error;
if (load.status !== 0) throw new Error('Bounded synthetic staging load check failed.');
const afterLoad = await request(`/api/v1/projects/${project.id}/incidents`, 'GET', null, session.accessToken);
if (afterLoad.items?.length < 2) throw new Error('Load did not create two distinct incidents for the AI quota check.');
const firstIncident = afterLoad.items[0].id;
const secondIncident = afterLoad.items[1].id;
const firstAnalysis = await request(`/api/v1/incidents/${firstIncident}/ai/analyze`, 'POST', null, session.accessToken);
const duplicate = await request(`/api/v1/incidents/${firstIncident}/ai/analyze`, 'POST', null, session.accessToken);
if (!firstAnalysis.analysisId || duplicate.analysisId !== firstAnalysis.analysisId) {
  throw new Error('Active AI analysis was not reused.');
}
const quotaResponse = await fetch(`${origin}/api/v1/incidents/${secondIncident}/ai/analyze`, {
  method: 'POST',
  headers: { Cookie: csrfCookie, [csrf.headerName]: csrf.token, Authorization: `Bearer ${session.accessToken}` },
  signal: AbortSignal.timeout(10_000),
});
const quotaProblem = await quotaResponse.json();
if (quotaResponse.status !== 429 || quotaProblem.code !== 'AI_DAILY_LIMIT_REACHED') {
  throw new Error('The one-per-day synthetic AI quota did not reject a second incident.');
}
console.log('Local staging verification passed: Redis-required readiness and rate limit, API provisioning, ingestion, incident retrieval, load, and AI quota.');
