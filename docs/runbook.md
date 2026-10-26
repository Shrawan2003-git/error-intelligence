# Foundation runbook

## Backend startup failure

Check Java 21, configured DB_URL/DB_USERNAME/DB_PASSWORD, PostgreSQL availability and the complete Flyway error. Credentials are required and have no default. Never disable Flyway or change ddl-auto to update to force startup.

If PostgreSQL is already running for another project, use a dedicated database/user or an isolated local instance on a different port. Do not reset an existing database or change its credentials.

## Readiness failure

Use /actuator/health/liveness to distinguish a running process from failed dependencies. Production readiness depends on PostgreSQL and Redis; the loopback native profile checks PostgreSQL and uses single-process authentication limits. Inspect service connectivity locally without exposing health details publicly. Restarting an application will not repair invalid database credentials.

## PostgreSQL outage

If readiness fails on `db`, stop sending new traffic to the instance, inspect the database service and private network, and check connection saturation and storage before restarting anything. Keep the current database volume and backups intact. After connectivity returns, confirm Flyway's recorded version, readiness, and a staging-only synthetic ingest/retrieval check. Restore from backup only through the separate recovery process after checking the source and target database identities.

## Redis outage and rate limits

If readiness fails on `redis`, inspect the Redis service and private network. Production authentication rate limiting fails closed when Redis is unavailable; do not switch to the `native` in-memory limiter to keep public login traffic flowing. Restore Redis connectivity, then confirm readiness and a bounded rate-limit check. Do not clear unrelated Redis keys or databases.

## AI provider outage and worker backlog

Check provider status, configured timeout, queue depth, and non-sensitive failure codes. Jobs have bounded retries; keep the durable PostgreSQL queue intact while the provider recovers. If paid calls must pause, disable the diagnostic/embedding workers through their configuration and restart only the application instances, then verify the queue stops draining. Keep `AI_DAILY_ANALYSIS_LIMIT` and the provider project budget in place. When the provider is healthy, re-enable workers and watch queue depth and error rate. Never copy request payloads, API keys, or provider responses into incident tickets or logs.

## Build failure

Run the full build output first. Maven Enforcer rejects Java versions other than 21. Use scripts/Run-Backend.ps1 to select the project-local toolchain when available. Formatting failures are fixed with spotless:apply, not by disabling the formatter. Frontend uses the exact npm lockfile; run npm ci rather than changing dependency ranges.

## Database migrations

V1 creates an empty application namespace. Do not edit a migration already applied in a shared environment. Add a new migration for changes. Test against clean and upgraded databases. Flyway clean is disabled. Later migrations must follow expand-migrate-contract for rolling deployment compatibility.

## Optional Compose lifecycle

Start: docker compose -f infra/compose.yml --env-file .env --profile app up --build -d --wait

Stop without deleting data: docker compose -f infra/compose.yml --env-file .env --profile app down

The named PostgreSQL volume is preserved. Do not use down -v unless intentional destruction of this project's local database is authorized.

## Rollback

For a bad application release, route traffic away from the unhealthy version and redeploy the previously recorded immutable artifact. Keep schema changes backward compatible so the previous application version can run against the expanded schema. Never reverse Flyway history or drop columns as an automatic rollback. If a migration is not backward compatible, stop rollout and follow its reviewed recovery plan before restoring application traffic. Restore a verified backup only into an isolated target first, validate it, and schedule any production cutover as a separate recovery action.

Before production rollout, record the artifact digest/version, verify a recent encrypted backup and restore result, apply only backward-compatible migrations, deploy to staging with TLS and secret-manager injection, run `scripts/Test-Staging.ps1 -BaseUrl 'https://staging.example.com' -ExpectedVersion '0.1.0'` with the real staging host and version, run an authorized synthetic ingestion smoke check, and rehearse rollback to the previous artifact. The smoke script checks HTTPS liveness/readiness and confirms the running application/version. Use `-AllowHttp` only for a local development server. Production deployment and staging rollback have not been exercised in this workspace; see [Phase 7 verification](phase-7-verification.md).

## Metrics, traces and alerts

Spring Actuator exposes `/actuator/prometheus` through a CIDR allowlist. It defaults to IPv4/IPv6 loopback; configure `PROMETHEUS_ALLOWED_CIDRS` with only the private scrape networks required by the metrics collector. Do not expose this route through a public proxy. The Prometheus scrape and alert templates are in `infra/observability`. The Grafana dashboard JSON is `infra/observability/grafana-dashboard.json`. Keep Prometheus and Grafana storage private and apply retention limits at the collector.

OTLP trace export is disabled unless `OTEL_TRACING_ENABLED=true`. Set `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` to a reachable trusted collector endpoint before enabling it. The sample rate defaults to 10%; adjust `OTEL_TRACING_SAMPLE_PROBABILITY` to the traffic and storage budget. HTTP request spans do not include request bodies, stack traces or credentials. `X-Trace-Id` is an application correlation header; consult the collector trace ID separately when tracing is enabled.

Alert rules cover sustained API 5xx rate, diagnostic and embedding queue backlog, and pending database connections. Verify that the collector is scraping and alert evaluation is active before relying on them. If a rule fires, check readiness and service logs with the trace ID; follow the database, AI-provider and worker procedures above.

## Raw-event retention

The cleanup job is disabled when `RAW_EVENT_RETENTION_DAYS` is unset or zero. Set a positive window only after the organization has approved its retention policy. By default it runs every minute, processing batches of 500 under row locks, bounded by 20 batches and a five-second loop budget per invocation (the transaction timeout is ten seconds). Expiry uses event occurrence time, so late-arriving old events may expire immediately. It removes linked incident-occurrence and fingerprint rows before raw events. It retains incident aggregates, comments, verified resolutions, embeddings, AI analyses and ingestion receipts; retained receipts prevent recounting replayed event IDs. These retained artifacts need separate lifecycle policies. Track `error_intelligence_retention_deleted_events_total` and oldest raw-event age. Restore mistakenly removed data into an isolated database for review before any production recovery.

## Review and release evidence

Run `promtool test rules prometheus-alerts.test.yml` from `infra/observability`, or use the pinned Prometheus container command in CI. The tests check low-traffic error ratios, idle traffic, scrape outage/recovery, old jobs and stale metric collection. These tests do not establish notification delivery. See [release readiness](release-readiness.md) for the remaining environment acceptance checks and [contribution guidance](../CONTRIBUTING.md) for local gates.

## Audit trail

Owners and administrators can read the latest 100 tenant audit entries at `GET /api/v1/audit`. The endpoint returns actor/resource identifiers and action names, never event contents. A viewer receives 403. The UI only shows the trail to organization owners and admins, while the backend enforces the same role check.
