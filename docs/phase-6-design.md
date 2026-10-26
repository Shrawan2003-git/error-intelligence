# Phase 6 Analytics and Operations Design

Phase 6 adds organization-safe analytics and operating controls without moving data ownership out of PostgreSQL. The project dashboard reads only the active organization's project; owner/admin audit review uses the same verified tenant context. Operational metrics use low-cardinality names without tenant, user, project, or event-content labels.

## Analytics and administration

`GET /api/v1/projects/{projectId}/analytics/overview?days=30` returns event/incident status counts, resolution rate, daily volume, and the five most frequent incident groups. The query is bounded to 7-90 days and uses the existing organization/project/time indexes. Cross-organization project access returns 404. The React dashboard provides project and period filters and empty/loading/error states.

`GET /api/v1/audit?limit=50` returns the newest 1-100 audit rows from the active organization. Owners and admins can view the feed; other roles are rejected by the backend. The response intentionally omits event payloads and metadata.

## Retention

`RAW_EVENT_RETENTION_DAYS` defaults to `0`, which disables deletion. When a positive period is explicitly configured, the hourly job selects no more than 500 expired raw events, removes incident-occurrence and fingerprint references, then deletes event rows in one database transaction. Incident aggregates, comments, resolutions, embeddings, and AI analyses remain. Operators must select the period from an approved retention policy; there is no implicit expiry window.

## Metrics and tracing

Actuator exposes only health and Prometheus endpoints. Prometheus scraping is filtered by `PROMETHEUS_ALLOWED_CIDRS`, which defaults to loopback. HTTP/JVM/Hikari metrics and low-cardinality gauges report open incidents, raw event count, and queued AI/embedding jobs. JMX remains closed. Prometheus scrape and alert files and a Grafana dashboard are under `infra/observability`.

OTLP tracing uses Micrometer/OpenTelemetry and is disabled by default. Operators enable it with `OTEL_TRACING_ENABLED=true`, a trusted `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT`, and the configured 10% default sample rate. Request bodies, raw error payloads and credentials are not exported as span attributes. The existing `X-Trace-Id` header remains a separate application correlation ID.

## Alerting and operations

Prometheus rules alert on sustained API 5xx rate, AI/embedding queue backlog, and database connection waiters. The runbook describes collector privacy, trace configuration, alert response, audit access, and the irreversible raw-event deletion behavior. No schema migration was needed; analytics and audit read the existing indexed tables.
