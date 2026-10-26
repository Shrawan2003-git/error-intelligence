const response = await fetch('http://127.0.0.1:18080/actuator/health/readiness', {
  signal: AbortSignal.timeout(8_000),
});
const health = await response.json();
if (response.status !== 503 || health.status !== 'DOWN') {
  throw new Error(`Expected readiness DOWN/503 during the staged database outage; got ${response.status}/${health.status}.`);
}
console.log('Local staging PostgreSQL outage passed: readiness reported DOWN/503.');
