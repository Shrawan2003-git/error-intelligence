# Foundation threat model

| Threat | Current control | Verification |
| --- | --- | --- |
| Public access to unfinished business routes | Deny by default; require live bearer sessions for tenant resources | Native PostgreSQL integration tests |
| Actuator exposing configuration/secrets | Health plus low-cardinality Prometheus metrics; scrape CIDR allowlist; no component details | Sensitive endpoint and loopback scrape tests |
| Default login credentials | Default user auto-configuration excluded | No login/basic flow configured |
| Unsafe browser writes | CSRF required for unsafe browser routes, including public authentication; ingestion uses project keys | Unsafe-request contracts and real browser acceptance |
| Cross-tab refresh races | Web Locks serialize authentication changes; ordinary JWT validation preserves the shared CSRF cookie | Simultaneous refresh in two Chromium tabs |
| Stale worker writes | Expiring claim tokens and embedding source versions reject obsolete completion | Queue ownership and source-change regression contracts |
| Provider workload growth | Durable per-organization daily embedding call/character reservations | Budget exhaustion contracts; external spending controls remain separate |
| Exception details in responses/logs | Generic problem handler, class-name-only failure log | Problem tests and source review |
| Caller-controlled log correlation input | Server-generated request correlation IDs | Filter implementation |
| Local credential reuse | Random ignored .env created without overwriting | Initialization helper |
| Container privilege abuse | Non-root apps, read-only filesystem, dropped capabilities | Compose/image checks |

Access JWTs are short lived and checked against active sessions and current membership on every request. Refresh credentials are hashed and rotated; reuse revokes the whole session. Production authentication limits use Redis and fail closed. Native development uses a bounded process-local limiter and must not be used for a multi-instance deployment. Ingestion keys are stored as SHA-256 digests, scoped to projects, and revocable. Event content is bounded, allow-listed and redacted before persistence and re-redacted before model calls; this reduces exposure but cannot guarantee removal of every application-specific secret. Incident records and AI analyses are scoped by the active organization, and analysis requests enforce contributor roles and organization rate limits. Prompt instructions treat evidence as untrusted data; the adapter has no tools, output is redacted and structurally validated, and cited excerpts must match supplied evidence. These controls reduce risk but do not prevent every prompt injection or hallucination. AI advice remains a hypothesis for human review. This is not a production security sign-off.
