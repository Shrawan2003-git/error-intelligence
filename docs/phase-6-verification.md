# Phase 6 Analytics and Operations Verification

Phase 6 passed its local Docker-free acceptance gate on 2026-10-09. Analytics, audit, Prometheus scraping, optional OTLP tracing, alert templates, and the retention controls compile and run under the native PostgreSQL profile.

## Automated checks

| Check | Result |
| --- | --- |
| Backend formatter and 17 unit tests | Passed |
| Native PostgreSQL migrations and 16 integration tests | Passed |
| Prometheus loopback scrape and metric-name contract | Passed |
| Owner audit access and viewer denial | Passed |
| Project analytics cross-tenant denial | Passed |
| Frontend format, lint, 10 unit tests and production build | Passed |
| Desktop/mobile Playwright flows | 4 passed |
| OpenAPI and Prometheus YAML formatting | Passed |
| Grafana dashboard JSON parse | Passed |

The analytics response is checked against a seeded real PostgreSQL dataset and asserts the event totals, status counts, daily series, resolution rate, top incident, and outsider 404. Audit access returns entries to an owner and 403 to a viewer. The default retention window is zero; its unit test verifies that the disabled job performs no database query. The scrape endpoint test verifies operational gauges and HTTP request metrics through the loopback allowlist. The filter unit test rejects an address outside the configured CIDRs.

## Operator configuration

Set a positive `RAW_EVENT_RETENTION_DAYS` only after selecting an approved policy. The job removes no more than 500 raw events each hour and retains aggregates, comments, resolutions, embeddings, and AI analyses. Configure Prometheus with `infra/observability/prometheus.yml`, load `prometheus-alerts.yml`, and import `grafana-dashboard.json`. Restrict scrape addresses with `PROMETHEUS_ALLOWED_CIDRS`; its default is loopback. OTLP traces stay disabled until an operator sets `OTEL_TRACING_ENABLED=true` and configures a trusted collector endpoint.

No production collector, alert receiver, retention window, backup system, or hosting environment was configured in this local phase gate. Phase 7 local backup/restore, warmed-load, and rate-limit checks are recorded in [phase-7-verification.md](phase-7-verification.md); a staging deployment and rollback drill remain pending.
