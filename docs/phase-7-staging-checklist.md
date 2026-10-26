# Phase 7 staging acceptance checklist

Use a staging host and services separate from Docker Desktop and from any production or company-project data. Record evidence here without copying credentials, tokens, event payloads, or backup contents into this file. Phase 7 passes only after every item below has evidence from the actual staging environment.

For this no-spend personal project, the [Docker-free local rehearsal](phase-7-local-rehearsal.md) is complete separately. It provides portfolio evidence but does not claim the independent production staging gate below has passed.

## Environment and release

- [ ] Record the staging provider/host, URL, and operator: **pending**.
- [ ] Run the backend with Java 21 as a restricted service account. Do not activate the `native` profile.
- [ ] Place PostgreSQL and Redis on private connections. Confirm the PostgreSQL `vector` extension is available before Flyway applies V6.
- [ ] Inject `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD`, `AUTH_SIGNING_KEY`, and (if AI is exercised) `OPENAI_API_KEY` through the host's secret manager. Record only the secret names, never their values.
- [ ] Set `AI_DAILY_ANALYSIS_LIMIT` to the approved per-organization request cap and configure a monetary budget on the OpenAI project before paid AI traffic. Confirm the configured timeout and retry count.
- [ ] Serve the app through HTTPS and limit `/actuator/prometheus` to the private scraper network.
- [ ] Confirm there are no default credentials, public debug endpoints, or production use of the `native` profile. Review the external proxy and service account permissions.
- [ ] Review dependency scan results for both Maven and npm; resolve or explicitly review every critical finding before release.
- [ ] Record the current and previous JAR version, SHA-256 digest, and the applied Flyway schema version.

## Functional and recovery checks

- [ ] Run `scripts/Test-Staging.ps1 -BaseUrl 'https://your-staging-host' -ExpectedVersion '<deployed-version>'`; record the time and result.
- [ ] Use a staging-only `ei_` ingestion key to send one synthetic event with a unique event ID. Confirm HTTP 202 and that the event appears in an incident. The OpenAI API key is a different credential.
- [ ] Run a representative, bounded staging load check; record concurrency, requests, P50/P95/P99 latency, throughput, and error rate. The ingestion P95 target is below 300 ms at the agreed test load. Keep the test data synthetic. After setting `EIP_STAGING_INGEST_KEY` in the current shell to a staging-only `ei_` key, run `node scripts/Test-StagingLoad.mjs https://your-staging-host --acknowledge-synthetic-writes` from the project root. The script sends 8 warm-ups and 120 measured events with 8 clients; it prints metrics but never the key or response bodies. Do not point it at production.
- [ ] Confirm encrypted scheduled PostgreSQL backups and the recovery retention policy. Restore one backup into an isolated database, check a synthetic marker, and record the backup identifier and restore result.
- [ ] Confirm Redis-backed rate limiting and that the alert collector receives the configured health, HTTP, queue, and database metrics.
- [ ] Recheck tenant isolation, role restrictions, payload-size limits, and secret redaction with synthetic staging data. Confirm logs and traces omit credentials and event payloads.
- [ ] Exercise an AI-provider outage and a database/cache outage in the staging runbook; confirm bounded retries, safe failure, alerts, and recovery. Do not send real customer data to the provider during the drill.
- [ ] Deploy a second immutable application artifact, run the smoke checks, then redeploy the previous artifact. Confirm readiness, the synthetic incident, and database state after rollback. Do not reverse Flyway migrations or restore a backup as part of an ordinary application rollback.

## Evidence to record

| Field | Value |
| --- | --- |
| Staging URL and host/provider | Pending |
| Current/previous artifact version and SHA-256 | Pending |
| Flyway schema version | Pending |
| HTTPS smoke result and time | Pending |
| Synthetic ingestion result | Pending |
| Staging load metrics | Pending |
| Backup ID and isolated restore result | Pending |
| Alerts and Redis rate-limit result | Pending |
| Security review and dependency scan findings | Pending |
| Provider/database/cache outage drill | Pending |
| Rollback result and time | Pending |

When all rows have verified evidence, update `phase-7-verification.md` and mark Phase 7 complete. Phase 8 starts afterward.
