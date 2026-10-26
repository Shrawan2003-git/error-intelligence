# API overview

The authoritative machine-readable contract is openapi.yaml. All organization and project access is derived from a verified, live session; clients cannot select tenant scope in project requests.

| Method | Path | Authentication | Success |
| --- | --- | --- | --- |
| GET | /api/v1/system | Public, non-sensitive metadata | 200 name, version, phase |
| GET | /api/v1/auth/csrf | Public; same-origin browser | 200 token and required header name |
| POST | /api/v1/auth/register | Public + CSRF + limits | 202; verification email queued, no session |
| POST | /api/v1/auth/verification/resend | Public + CSRF + limits | 202; generic response |
| POST | /api/v1/auth/verification/confirm | Public + CSRF + limits | 204; one-time email confirmation |
| POST | /api/v1/auth/password/forgot | Public + CSRF + limits | 202; generic response |
| POST | /api/v1/auth/password/reset | Public + CSRF + limits | 204; password changed, sessions revoked |
| POST | /api/v1/auth/login | Public + CSRF + limits | 200 account, access token and refresh cookie |
| POST | /api/v1/auth/refresh | Refresh cookie + CSRF | 200 rotated access and refresh credentials |
| POST | /api/v1/auth/logout | CSRF | 204 session revocation and cookie clear |
| GET | /api/v1/auth/me | Bearer access token | 200 account and memberships |
| POST | /api/v1/auth/organization | Bearer + CSRF | 200 new organization-scoped access token |
| GET/POST | /api/v1/organizations/{id}/members | Member; admin writes | 200 members / 201 membership |
| GET/POST | /api/v1/projects | Member reads; admin writes | 200 page / 201 project |
| GET/PATCH/DELETE | /api/v1/projects/{id} | Member reads; admin writes | 200 detail / update / 204 archive |
| GET/POST | /api/v1/projects/{id}/keys | Owner/Admin | 200 safe metadata / 201 secret returned once |
| POST | /ingest/v1/events | Project ingestion key | 202 sanitized immutable event receipt; duplicate-safe by project/event UUID |
| GET | /api/v1/projects/{id}/incidents | Project member | 200 paginated/filterable incidents |
| GET | /api/v1/incidents/{id} | Project member | 200 incident with recent event evidence and collaboration |
| PATCH | /api/v1/incidents/{id} | Project developer+ | 200 status/assignment update |
| POST | /api/v1/incidents/{id}/comments | Project developer+ | 201 comment |
| POST | /api/v1/incidents/{id}/resolve | Project developer+ | 201 human-verified resolution |
| POST | /api/v1/incidents/{incidentId}/ai/analyze | Project developer+; CSRF; rate limited | 202 durable diagnostic job; 429 when per-minute or daily organization limit is reached |
| POST | /api/v1/incidents/{incidentId}/similar | Project developer+; rate limited | 200 project-scoped similar incidents and verified resolutions |
| GET | /api/v1/projects/{projectId}/analytics/overview?days=30 | Active organization member | 200 project-scoped aggregates and daily event counts (7-90 days) |
| GET | /api/v1/audit?limit=50 | Organization Owner/Admin | 200 newest audit events, tenant scoped (1-100) |
| GET | /actuator/prometheus | Configured scrape network only | Prometheus metrics; CIDR allowlist defaults to loopback |
| GET | /api/v1/incidents/{incidentId}/ai/analyses/{analysisId} | Project member | 200 job state and validated structured result |
| GET | /actuator/health | Public, status only | 200 UP or 503 DOWN |
| GET | /actuator/health/liveness | Public, process state only | 200 UP or 503 |
| GET | /actuator/health/readiness | Public, required dependencies | 200 UP or 503 |

Production readiness includes database and Redis connectivity. The loopback native profile includes PostgreSQL and uses process-local limits; system metadata reports cacheRequired=false. A running Java process is not sufficient for readiness. All responses have a server-generated X-Trace-Id. Prometheus exposes low-cardinality JVM, HTTP, database-pool and work-queue metrics only to configured scrape CIDRs. OTLP traces are disabled unless explicitly enabled and configured.

Unknown API routes receive a 401 problem response for anonymous requests; prohibited operations and CSRF failures receive 403. Sensitive Actuator endpoints remain inaccessible. Tenant-scoped resource absence is reported as 404.

Problem responses use application/problem+json with type, title, status, detail, code and traceId. They never include a stack trace, connection string, credential or raw exception message.

New accounts cannot obtain a session until email verification succeeds. Existing accounts created before migration V11 are backfilled as verified. Verification and reset tokens are single-use, expire after 30 and 15 minutes, and are stored as SHA-256 digests. Both email-request endpoints return the same success status for known and unknown addresses. All authentication POSTs require the same-origin CSRF cookie and header.

AI analyses run asynchronously from re-redacted evidence. A contributor request is limited per organization; queued and stale-running jobs are recovered by the worker. Provider calls use a bounded timeout and are never made during ingestion. Malformed output or provider failure is retried and then saved as a deterministic UNKNOWN/manual-investigation fallback. Analysis records retain model and prompt versions, status, failure code and token counts; provider exception text is not stored.
