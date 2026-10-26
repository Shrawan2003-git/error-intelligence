# Error Intelligence architecture

This project implements the supplied AI Code Review and API Error Intelligence blueprint as a Java modular monolith. Phase 0 established build, migration, security and runtime boundaries. Phases 1-3 add account identity, organization tenancy, projects, secure error ingestion and incident investigation workflows.

## Request flow

React and TanStack Query -> same-origin proxy -> Spring MVC controller -> validated DTO -> authorized module service -> tenant-scoped repository -> PostgreSQL.

Redis provides production rate limits and short-lived caches. PostgreSQL is the system of record. Phase 4 calls the OpenAI Responses API through a bounded server-side adapter with strict structured output. Phase 5 stores OpenAI embeddings in pgvector and filters similarity retrieval by organization and project before results reach the browser or diagnostic context. Kafka, batch analytics, and time partitioning are deferred under the measured [Phase 8 scale decision](adr/0004-measured-scale-extension.md).

The user requested Docker-free development. The native profile runs on loopback with isolated local PostgreSQL and a bounded in-process rate limiter for one development instance. Production profile requires Redis readiness and fails closed on a Redis outage. See ADR 0003 for the boundary of this development exception.

## Module boundaries

| Module | Owns |
| --- | --- |
| common | Problem responses, security configuration, trace IDs, system metadata |
| identity | Global users, organizations, memberships, sessions and permission decisions |
| projects | Projects, environments, releases and ingestion keys |
| ingestion | Bounds, canonical events, redaction, normalization and deduplication |
| incidents | Fingerprints, groups, occurrences, assignment, comments and resolutions |
| similarity | Tenant-scoped semantic retrieval, shared evidence contracts, embeddings and daily usage budgets |
| ai | Sanitized evidence, provider adapters, structured diagnostics and durable jobs |
| analytics | Aggregated engineering metrics |
| audit | Append-only security and administrative history |
| operations | Bounded raw-event retention and operational metrics snapshots |

Modules are implemented as vertical slices. Ingestion owns its stored-event contract; similarity owns evidence contracts consumed by diagnostics. A source-level dependency test rejects cycles between modules.

## Persistence and transactions

Flyway is the only schema mutation mechanism. V1 creates the intelligence namespace, V2 creates identity and project tables, V3 adds secure event persistence, and V4 adds fingerprints, incident groups, occurrences, comments and verified resolutions; Flyway history remains in public. Hibernate validates schemas and open-in-view is disabled. Persisted business timestamps use UTC timestamptz.

V8 adds versioned embedding sources and worker claim tokens, V9 stores daily embedding reservations, and V10 preserves ingestion receipts independently of raw payload retention. Worker completion requires a live claim; embeddings are stored only for the claimed source version. Retrieval matches the configured embedding model. Database connections initialize in UTC.

Phase 1 introduces identity and tenancy tables after a reviewed field-level schema. Tenant-owned data must have a guaranteed scoped parent or organization_id, with composite foreign keys where needed to prevent mismatched project/environment references. Queries require tenant authorization context. Application entities are never API responses.

Ingestion atomically persists a sanitized event, deduplicates by project/event identity, and emits a transactional internal event. The incident module fingerprints normalized exception evidence and atomically records the fingerprint, group count and occurrence. AI requests create durable tenant-scoped jobs; a bounded scheduled worker claims jobs with PostgreSQL row locks, validates output against evidence, retries failures, and records a deterministic fallback after the configured attempt cap. Provider failure does not prevent viewing an incident.

## Foundation security

System metadata, CSRF retrieval, authentication lifecycle endpoints and health/probes do not require a bearer session. Prometheus access is restricted by the scrape filter. Ingestion authenticates a project key. Tenant routes require a live JWT session; unsafe browser requests require CSRF protection, with key-authenticated ingestion excluded. No default credentials, basic login or form login is enabled. Health component details and sensitive Actuator endpoints are not exposed.

The development proxy and container frontend provide same-origin API access. No wildcard CORS is enabled. Refresh credentials are HttpOnly SameSite cookies; short-lived access tokens stay in browser memory. Tenant permissions bind to the active session organization. Container CSP, framing restrictions and nosniff headers are configured.

Organization query keys isolate client caches, requests have cancellation/deadlines, and logout clears local state even when the server request fails. Web Locks serialize refresh and authentication changes across supported browser tabs. CSRF cookies rotate on login, registration and logout; ordinary JWT validation preserves the shared cookie to avoid racing another tab's refresh.

## Observability boundary

Actuator supplies health, dependency readiness and Micrometer instrumentation. Logs use structured JSON. Each HTTP request gets a server-generated correlation ID in X-Trace-Id and MDC; distributed tracing remains separate work. Prometheus/Grafana configuration and alert rules are present. Scrapes read cached database metrics refreshed every 30 seconds, with freshness and failure gauges. Alert delivery and deployed scrape/proxy boundaries require environment-specific verification.

## Open decisions

- Onboarding, invitations, password recovery and email integration.
- Exact role-management and incident recurrence rules.
- Retention, payload limits, AI quotas and representative performance load.
- AI/embedding provider and production hosting.
- Source-code/PR review is not specified in the blueprint despite the project title.
