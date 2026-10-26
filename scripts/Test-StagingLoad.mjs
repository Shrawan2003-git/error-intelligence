import { randomUUID } from 'node:crypto';
import { performance } from 'node:perf_hooks';

const [originArg, acknowledgement] = process.argv.slice(2);
const allowHttpLoopback = process.argv.includes('--allow-http-loopback');
const key = process.env.EIP_STAGING_INGEST_KEY;
if (acknowledgement !== '--acknowledge-synthetic-writes') {
  throw new Error('Pass --acknowledge-synthetic-writes for a staging-only destination.');
}
if (!key?.startsWith('ei_')) {
  throw new Error('Set EIP_STAGING_INGEST_KEY to a staging-only ingestion key.');
}
let origin;
try {
  const parsed = new URL(originArg);
  const localHttp = allowHttpLoopback && parsed.protocol === 'http:' && ['127.0.0.1', 'localhost'].includes(parsed.hostname);
  if ((!localHttp && parsed.protocol !== 'https:') || parsed.username || parsed.password || parsed.pathname !== '/' || parsed.search || parsed.hash) {
    throw new Error('invalid origin');
  }
  origin = parsed.origin;
} catch {
  throw new Error('First argument must be a bare HTTPS staging origin, or HTTP loopback with --allow-http-loopback.');
}

const warmups = 8;
const measured = 120;
const concurrentClients = 8;
const endpoint = `${origin}/ingest/v1/events`;

async function sendEvent(index) {
  const body = {
    eventId: randomUUID(),
    occurredAt: new Date().toISOString(),
    environment: 'phase7-staging-load',
    exceptionType: `com.example.Phase7SyntheticFailure${index % 20}`,
    message: `Synthetic Phase 7 event ${index}`,
    stackTrace: 'at com.example.Phase7.run(Phase7.java:42)',
  };
  const started = performance.now();
  const response = await fetch(endpoint, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-API-Key': key },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(10_000),
    redirect: 'error',
  });
  // Include body transfer in latency, but never print it: it could contain tenant data.
  await response.arrayBuffer();
  if (response.status !== 202) throw new Error(`Synthetic ingestion returned HTTP ${response.status}.`);
  return performance.now() - started;
}

async function batch(count, offset) {
  const samples = Array(count);
  let next = 0;
  await Promise.all(Array.from({ length: concurrentClients }, async () => {
    while (next < count) {
      const position = next++;
      samples[position] = await sendEvent(offset + position);
    }
  }));
  return samples;
}

await batch(warmups, 1_000);
const started = performance.now();
const samples = (await batch(measured, 0)).sort((a, b) => a - b);
const elapsed = performance.now() - started;
const percentile = (fraction) => Math.round(samples[Math.ceil(samples.length * fraction) - 1]);
console.log(`Staging synthetic ingestion: requests=${measured} warmups=${warmups} concurrentClients=${concurrentClients} fingerprints=20 p50=${percentile(0.5)}ms p95=${percentile(0.95)}ms p99=${percentile(0.99)}ms max=${Math.round(samples.at(-1))}ms throughput=${(measured / (elapsed / 1_000)).toFixed(1)}/s errors=0`);
if (percentile(0.95) >= 300) process.exitCode = 1;
