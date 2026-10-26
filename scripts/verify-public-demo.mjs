#!/usr/bin/env node

const supplied = process.argv[2];
if (!supplied) {
  console.error('Usage: node scripts/verify-public-demo.mjs https://your-demo.example');
  process.exit(1);
}

let origin;
try {
  const url = new URL(supplied);
  if (
    url.protocol !== 'https:' ||
    url.username ||
    url.password ||
    url.pathname !== '/' ||
    url.search ||
    url.hash
  ) {
    throw new Error('Expected a bare HTTPS origin.');
  }
  origin = url.origin;
} catch {
  console.error('Pass an HTTPS origin such as https://your-name.duckdns.org');
  process.exit(1);
}

async function check(path, assert) {
  const response = await fetch(`${origin}${path}`, {
    redirect: 'manual',
    signal: AbortSignal.timeout(15000),
  });
  await assert(response);
  console.log(`PASS ${path} (${response.status})`);
}

try {
  for (const name of ['liveness', 'readiness']) {
    await check(`/actuator/health/${name}`, async (response) => {
      if (!response.ok || (await response.json()).status !== 'UP') {
        throw new Error(`${name} is not UP`);
      }
    });
  }
  await check('/api/v1/system', async (response) => {
    if (!response.ok || (await response.json()).name !== 'Error Intelligence') {
      throw new Error('The Java API did not respond as expected.');
    }
  });
  await check('/login', async (response) => {
    if (!response.ok || !(await response.text()).includes('id="root"')) {
      throw new Error('The React sign-in page did not load.');
    }
    if (!response.headers.get('content-security-policy')) {
      throw new Error('The frontend security headers are missing.');
    }
  });
  await check('/actuator/prometheus', async (response) => {
    if (response.ok) throw new Error('The private Prometheus endpoint is public.');
  });
  console.log('Public HTTPS smoke passed. Check registration email and a synthetic incident manually.');
} catch (error) {
  console.error(`Public HTTPS smoke failed: ${error.message}`);
  process.exitCode = 1;
}
