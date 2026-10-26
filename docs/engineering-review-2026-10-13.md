# Engineering review and improvement plan — 13 October 2026

## Assessment

**The project has a professional foundation and a sensible structure for a modular-monolith MVP. It is not yet ready to be called a production-ready release.** The main gaps concern reproducibility, asynchronous job correctness, frontend state isolation, and operational evidence. A wholesale rewrite would discard useful work without addressing these gaps.

This workspace contains one executable application, `error-intelligence-platform`, with a Spring Boot backend and React frontend. The Word blueprints are separate documents, not additional implemented applications. This review covers the application's source, database migrations, configuration, CI, frontend state and API handling, tests, operations scripts, and engineering documentation. It checks the existing 12 October audit against current source and fresh verification rather than assuming that earlier results still apply.

No application implementation was changed. This review adds a plan; test/build outputs and audit logs were generated locally. Existing private configuration was not printed. Native backend verification uses disposable PostgreSQL clusters, a temporary process-local signing key, and disabled or stubbed model providers. No paid model calls were made.

## Fresh verification

| Check | Result and scope |
| --- | --- |
| Frontend ESLint | **Failed:** eight `no-undef` errors in `scripts/capture-user-guide.mjs`, including `URL`, `fetch`, and `console`. |
| Frontend Prettier | **Failed:** the same capture script needs formatting. |
| Frontend Vitest | **Passed:** 10 tests across three files. |
| Frontend production build | **Passed:** TypeScript check and Vite build. |
| Playwright | **Passed:** four desktop/mobile checks. Business interactions use mocked APIs; the foundation checks passed while the backend on port 8080 was unavailable. These results do not prove integrated business flows. |
| npm advisory check | **Passed:** `npm audit --json` reported zero known vulnerabilities, including development dependencies, at the time of this check. This is not a Java/container vulnerability assessment. |
| Backend format/unit/native integration | **Passed:** Maven `verify -Pnative-postgres`, formatting, 18 unit tests, and 19 native PostgreSQL integration tests; zero failures/errors/skips. Includes seven migrations, backup/restore, database-outage behavior, identity, ingestion, incidents, and the local load gate. |
| Container deployment and remote CI | Not executed. Source review identifies configuration blockers below; no Git remote exists. |
| Live model output / independent production staging | Not verified. |

The first sandboxed backend attempt reached 18 unit tests, with 16 passing and two failing because Windows loopback connections were prohibited. Those were environment failures. Verification was rerun with local networking allowed, using the existing portable Java 21/Maven toolchain. The initial PowerShell wrapper invocation was also blocked by local execution policy, so Maven was invoked directly without changing policy.

Reproducible local evidence is under ignored `.local/audit-2026-10-13-backend-verified.log` and `.local/audit-2026-10-13-npm.json`; Maven XML reports are under `back-end/target`. These files are operational evidence, not source-controlled release artifacts.

The fresh local load check measured 120 requests, eight concurrent clients, 20 fingerprints, and eight warm-ups: P50 109 ms, P95 256 ms, P99 295 ms, maximum 323 ms, throughput 62.8 requests/second. It passed the existing P95-under-300-ms gate. This short single-machine check is a regression result, not a capacity guarantee; provider workers were disabled.

## What is already professionally implemented

- **Repository layout:** application code, shared documentation, infrastructure, scripts, and CI are separated. Each application has a build definition and guide.
- **Backend business slices:** identity, projects, ingestion, incidents, diagnostics, similarity, analytics, and audit have concrete responsibilities. Controllers generally validate/map HTTP input and delegate business work.
- **Database discipline:** seven Flyway migrations, Hibernate validation, disabled open-in-view, UTC timestamp types, unique constraints, and composite tenant/project foreign keys. Parameterized SQL is used for business values.
- **Security foundations:** BCrypt, validated JWT issuer/audience, live session/membership checks, hashed rotating refresh credentials, reuse revocation, CSRF, project-scoped hashed ingestion keys, and deny-by-default endpoints.
- **Ingestion correctness:** bounded JSON bodies, field validation, redaction, database-enforced event idempotency, and incident grouping within the ingestion transaction. The concurrent ingestion contract tests are valuable.
- **Provider boundaries:** adapters, bounded inputs/timeouts, structured diagnostic results, evidence validation, sanitized output, and fallback behavior. These are good foundations even though budget and queue correctness still need work.
- **Engineering intent:** formatting/lint gates, native and container test paths, backup/restore checks, outage checks, ADRs, a threat model, and a runbook. Deferring Kafka and partitioning until evidence warrants them is a sensible decision.

The use of both JPA and `JdbcTemplate` is not itself a defect. JPA suits some entity CRUD, and SQL is useful for queues, aggregates, and atomic upserts. Establish consistent ownership and transaction rules rather than replacing one technology solely for uniformity.

## Prioritized findings

Priority P1 means resolve before presenting the affected path as release-ready. P2 means address in the next hardening iteration. P3 means improve maintainability and engineering presentation. Static findings describe behavior evident in source; they are not claims that a production incident was reproduced.

### 1. P1 — Fresh quality gates fail, and the clean CI/deployment path has configuration blockers

**Verified failure:** the new user-guide capture script breaks frontend lint and format checks. The ESLint configuration applies recommended JavaScript rules but provides no Node globals for `.mjs` scripts. Configure the script environment explicitly and format the file, then run the complete frontend gate.

**Static deployment blockers:**

- `application.yml:82` requires `AUTH_SIGNING_KEY`. The backend job in `.github/workflows/ci.yml` does not generate or inject it, and test properties do not supply one. The local runner loads `.env`, which masks this missing clean-runner dependency.
- `infra/compose.yml:42` passes database/Redis settings to the backend but omits `AUTH_SIGNING_KEY`. Passing `--env-file .env` for Compose interpolation does not automatically inject every variable into a container. See [Docker's container environment documentation](https://docs.docker.com/compose/how-tos/environment-variables/set-environment-variables/).
- Compose and the Testcontainers integration classes use plain `postgres:17.11-alpine`, while V6 requires `CREATE EXTENSION vector`. No extension installation is supplied for these images. Use a compatible image with pgvector installed, or explicitly build/install the extension. The [pgvector installation documentation](https://github.com/pgvector/pgvector#docker) provides that path.

These blockers were identified by source inspection; Docker startup and remote Actions were not run. Fix them without weakening signing-key validation. Generate independent test credentials within tests/CI and keep production secrets mandatory. Keep workers disabled in no-cost test environments.

**Acceptance:** a fresh checkout with an empty environment passes frontend gates and backend verification; the chosen documented deployment path starts and becomes ready with all seven migrations applied. If the container path is retained, it must be tested. If Docker-free operation is the supported path, CI and documentation must reflect that decision explicitly.

### 2. P1 when embeddings are enabled — Ingestion resets completed and running embedding work

`IncidentService.java:72` enqueues an embedding for every newly stored occurrence. `EmbeddingJobQueue.java` unconditionally resets a conflicting source job to `QUEUED`, replaces source text, and resets attempts, including when the job is `RUNNING` or `SUCCEEDED`.

Consequences include repeated provider calls for a busy incident and a worker losing its claim during ingestion. Completion can also associate a vector generated from an earlier source with newer text: `complete` reads the current job's source text after the provider call instead of persisting the claimed snapshot. The prior audit identified part of this issue; it remains in current source.

Use a sanitized source hash and source version. Skip unchanged completed sources, retain an immutable claimed snapshot, and coalesce later changes into a new version. Updates must not reset an active claim. Apply completion only to the version that generated the vector.

The diagnostic daily admission cap does not cover embedding jobs. Similarity search already has a five-per-minute organization rate limit; it still lacks a daily token/spend reservation shared with background embedding work. Add a common budget policy and reuse eligible stored query vectors.

**Acceptance:** repeated identical evidence causes no additional embedding call; source changes during a provider call cannot produce mismatched text/vector records; all provider paths obey explicit quotas.

### 3. P1 for tenant UX — Organization switching can display stale organization data

`IdentityWorkspace.tsx:68` asynchronously loads projects and members. `changeOrganization` changes `account` and clears key state, but leaves old `projects` and `members` until the new request completes. The child workspaces are remounted for the new organization while receiving the old arrays. If loading fails, old organization data can remain under the new organization's heading.

The `active` flag correctly prevents an older effect from updating state after cleanup, but it does not clear data already displayed. Some mutation helpers also update arrays outside that guard. Failed logout clears the API client's token in `finally`, while the component clears account/data only on success, leaving an apparently signed-in workspace.

This is a frontend state defect; backend tenant checks exist, and this review does not establish a server-side cross-tenant authorization bypass. Clear or scope all sensitive state immediately on switch/logout. Use organization-scoped query keys, cancel pending requests, and require a matching scope before rendering results. Clear local session UI even when remote logout fails, while explaining that server revocation may need retry.

**Acceptance:** delayed, failed, or reordered requests never show organization A's projects/members beneath organization B; logout failure cannot leave authenticated-looking data; mutations completed after a switch cannot populate the new workspace.

### 4. P2 — Queue recovery lacks claim ownership and a bounded crash policy

Both queue implementations reclaim jobs whose `RUNNING` timestamp is older than two minutes. Completion and failure checks use only ID/status, without a lease token or claimed version. An earlier worker resuming after a long pause can complete a job that a second worker has reclaimed.

`claimNext` increments attempts without enforcing the configured cap for repeatedly abandoned jobs. The attempt cap is applied only when `failed` executes. Repeated crashes can therefore bypass the intended cap, and a continually old job can consume worker capacity.

Add a claim token, lease expiration, conditional completion/failure, and bounded abandoned-job handling. Reject updates from expired claims. Separate retryable failures from terminal configuration/validation failures and expose oldest pending/running job age. One job per scheduled poll is a valid initial limit; increase concurrency only after measuring provider/database limits.

**Acceptance:** two workers and a paused/restarted worker cannot overwrite each other's results; repeated abandoned claims reach a terminal state; no claim holds a database transaction open during a provider call.

### 5. P2 — Session refresh coordination stops at one browser tab

`client.ts:198` coordinates refresh through one JavaScript-module promise. Separate tabs have separate promises but share the refresh cookie. `IdentityService.java:160` revokes the session whenever a consumed refresh token is reused. Two tabs refreshing the same token concurrently can trigger that policy during normal use.

Preserve reuse detection while defining cross-tab behavior. Coordinate refresh ownership through an appropriate browser mechanism and verify other tabs adopt the resulting session. Consider any server-side grace/idempotency design carefully so it does not silently disable replay detection.

**Acceptance:** simultaneous tab restoration/expiry does not log out a legitimate user; deliberate replay still revokes the session; concurrent logout/switch does not restore a superseded account.

### 6. P2 — Frontend network handling and pagination are incomplete

The public health/metadata helper has an eight-second timeout, while authenticated `secureRequest` and its CSRF request have no abort/timeout. Pending actions can hang indefinitely. Add explicit cancellation and appropriate request deadlines, including the CSRF and refresh stages, and normalize errors consistently.

`getProjects` requests only the backend's default first 20 projects; `getIncidents` hardcodes page zero and size 50. Members/keys are limited to 100 by backend queries without continuation. Incident details select the oldest 100 comments, so a newly added comment beyond that limit can be saved successfully but disappear from the returned view.

Expose pagination/cursors and totals consistently; render useful loading, empty, retry, and access-expired states. Ensure role changes refresh the UI's account/permissions rather than relying on stale session-response roles.

**Acceptance:** a user can reach project 21, incident 51, and newly added comments beyond 100; hanging/cancelled requests return a controlled state; permission changes update visible controls.

### 7. P2 — Proxy behavior can collapse IP limits and time out valid similarity searches

Authentication and ingestion controllers rate-limit `request.getRemoteAddr()`. Nginx supplies forwarded headers, but the application has no explicit forwarded-header strategy or trusted-proxy boundary. On an ordinary container deployment, callers may all share the proxy's address and therefore the 30-per-minute login bucket or 1,000-per-minute ingestion bucket.

Define the trusted proxy chain and ensure untrusted clients cannot spoof it. Verify client-IP behavior through the actual deployment proxy. Keep account/key limits as complementary controls.

Nginx uses a ten-second API read timeout, while the synchronous embedding request used by similarity search permits twelve seconds, plus database work. The proxy can fail a valid request before the backend finishes. Align endpoint deadlines or queue/cache similarity work rather than allowing contradictory timeouts.

**Acceptance:** independent clients have independent IP buckets through the proxy; forged forwarded headers cannot bypass limits; slow provider behavior produces a bounded, consistent API/UI outcome.

### 8. P2 when retention is enabled — Cleanup capacity and data lifecycle need a defined policy

`EventRetentionJob.java:47` globally sorts expired events and removes at most 500 each hour: at most 12,000 per day per instance. A larger expired-event arrival rate creates a growing backlog. Existing event indexes start with organization/project, not the global expiration order. Multiple instances also select the same batches without coordinated claims. The current unit test exercises only disabled retention.

Add an expiration-order index through a new migration, process bounded batches under a time budget, and coordinate deletion workers. Expose backlog count/age. Define whether expiry is based on occurrence or receipt time, particularly for late-arriving events.

Deleting raw rows also removes their idempotency identity, so replaying an old client event ID after deletion can be counted again. Source text survives in embedding jobs/vectors and diagnostic results, while session/refresh/audit history has no cleanup policy. Specify retention separately for these artifacts. The existing documentation correctly describes raw-event-only deletion; preserve that distinction.

**Acceptance:** enabled deletion removes linked records atomically on real PostgreSQL, keeps up with the approved workload, and preserves the declared deduplication horizon and evidence-retention policy.

### 9. P2 — Current test breadth does not establish full application acceptance

Four passing browser tests are useful, but the business flow is intercepted with `page.route`. The foundation test can pass with the backend down unless `E2E_REQUIRE_READY=true`. Fresh results demonstrate this limitation.

The default Maven gate selects class names for identity, backup/restore, and load integrations that have only native implementations in the repository. Ingestion/incident container tests themselves activate the native profile. Consequently, the README's statement that the two backend gates reuse the same contracts should not be interpreted as full parity, and the default gate does not establish production Redis behavior throughout the business suite.

Add one live, synthetic browser flow against a real API/database: register/login, create project/key, ingest duplicate events, inspect grouping, comment/resolve, switch tenant, and logout. Model providers can remain local stubs. Share identity/security contracts between deployment modes and cover Redis fail-closed behavior and the findings above.

Keep fast gates separate from representative load qualification. The 120-request/eight-client check is a useful regression signal, but its small dataset and host variability cannot establish sustained capacity. Migration tests should eventually include representative business records across supported upgrade paths, beyond the current upgrade-probe row.

**Acceptance:** clean CI exercises a real browser/API/database flow, documents mode-specific coverage, and detects regressions in tenant switching, queue claims, pagination, retention, and refresh concurrency.

### 10. P2 — Module boundaries are conventions, and frontend components carry too many responsibilities

Business folders are useful, but Java classes directly reach across modules. `ingestion` imports an event nested inside an `incidents` listener. `ai` depends on `similarity`, while `similarity` imports AI-specific evidence DTOs and provider types. That creates a package dependency cycle. Services commonly combine authorization, persistence SQL, mapping, workflow, and audit statements.

The frontend has an identity component over 600 nonblank lines, an incident component over 500, and one large client mixing all DTOs and API functions. TanStack Query is used for system checks while business data uses independent effects/state, despite the documented query conventions.

Refactor incrementally after correctness fixes. Publish events from the owning module's public contract package. Move retrieval contracts away from AI-internal DTOs so dependencies are one-way. Extract cohesive SQL repositories/query objects where they simplify ownership and testing. Define permitted module dependencies and enforce them with an architecture check. Do not add an interface for every class by default.

Frontend target layout:

```text
src/
  app/                  # shell, providers, navigation
  features/
    auth/               # session provider, forms, session queries
    organizations/      # membership and organization switching
    projects/           # project list and ingestion keys
    incidents/          # list, detail, collaboration, diagnostics
    analytics/
    audit/
  shared/
    api/                # bounded request transport and problem mapping
    ui/                 # genuinely reused controls
```

Split by responsibility rather than arbitrary line limits. Maintain one source of truth for API schemas/types, and validate the OpenAPI contract in CI. Environment/release routes exist in controllers but are absent from the current OpenAPI path list.

**Acceptance:** documented dependency rules pass automatically, business query keys include organization/project scope, and API contracts/types cannot silently drift.

### 11. P2 — Metrics and analytics can give misleading operational signals

`PlatformMetrics` runs whole-table database counts at scrape time. This adds database work proportional to retained data and makes scraping depend directly on database availability. Snapshot aggregates on a bounded interval and expose collection freshness/failure without adding high-cardinality tenant labels.

The 5xx alert divides by `clamp_min(total_request_rate, 1)`. Below one request/second, this understates the error percentage. For example, 0.2 requests/second with 10% failures produces 0.02 and misses a 5% threshold. Define a correct ratio and a deliberate minimum-traffic condition. Add scrape/readiness outage and oldest-job-age signals, and test alert expressions with representative series. Alertmanager/delivery is not configured in this repository.

Analytics uses recent `first_seen` for incident/status counts but recent `last_seen` with lifetime occurrence counts for top incidents. That can be a valid cohort metric, but the UI's generic period selector does not explain it. Database `current_date` and date casts also depend on session timezone; UTC Java settings do not alone define the database aggregation timezone. Document cohort/lifetime semantics or implement period-specific metrics with an explicit timezone.

Similarity retrieval stores `embedding_model` but does not filter by it. A configured model change can mix incomparable vectors even when dimensions match. Specify supported model/version and re-embedding behavior; benchmark filtered similarity recall against exact search. See [pgvector's filtering guidance](https://github.com/pgvector/pgvector#filtering).

**Acceptance:** alerts detect low-volume failures and service outages; scrape cost is bounded; dashboard numbers and embedding retrieval remain meaningful after retention and model changes.

### 12. P3 plus an external release gate — Engineering history and production evidence are incomplete

Git reports all candidate source files as untracked, has no HEAD commit, and no remote. Therefore there is no reviewable commit history, remote CI result, or revision-linked release evidence. Establish small coherent commits after a secret/content review, then enable the remote quality gates in a user-selected repository. Add concise contribution/security guidance and choose a license if public distribution is intended.

The 12 October audit claims frontend format/lint passed; today's current source fails both. Treat verification notes as dated snapshots and attach commands/results to a revision. Architecture/threat-model text also contains stale phase descriptions. Update it to the implemented boundaries and current threat surface.

The README already honestly leaves independent production staging open. Before a public/customer release, demonstrate HTTPS, trusted proxies, restricted database/service roles, secrets injection/rotation, off-machine backup restoration, alert delivery, sustained representative traffic, and migration-compatible rollout/rollback. Native tests use a superuser test cluster and local restore marker; they do not prove least-privilege production migrations or backup recovery objectives. Obtain Java/container advisory results and pin release images/artifacts by digest.

Password recovery, email verification/invitations, and optional stronger authentication are product decisions for real users, not reasons to discard the current architecture. Prioritize account recovery before opening self-service registration to customers.

## Implementation sequence

Estimates are planning ranges for one experienced engineer familiar with the code. They include focused regression checks but exclude hosting procurement, paid-provider access, and substantial new product features. Split each phase into reviewable changes; do not combine everything into a large refactor.

| Order | Work package | Suggested effort | Required outcome |
| --- | --- | --- | --- |
| 1 | Restore reproducibility: capture-script lint/format, test signing configuration, pgvector-compatible test/deployment database, document supported deployment modes. | 1–2 engineer-days | Fresh local/CI commands pass without private workstation configuration; documented runtime becomes ready. |
| 2 | Correctness: embedding source/version handling, claim tokens and crash caps, tenant UI reset/scope checks, timeout/error handling. | 3–5 engineer-days | Regression tests cover concurrent updates, stale responses, abandoned jobs, and failed network/logout behavior. |
| 3 | Complete data access: pagination and comment ordering, cross-tab refresh, proxy IP/deadline handling, shared provider budgets/model-version rules. | 3–5 engineer-days | Larger datasets remain usable; simultaneous tabs and proxied clients behave correctly; provider work stays bounded. |
| 4 | Improve boundaries incrementally: session provider, scoped business queries, smaller feature components, owned event/retrieval contracts, cohesive SQL repositories, architecture/OpenAPI checks. | 3–5 engineer-days | The intended module/query rules are enforced, with existing behavior preserved. |
| 5 | Reliability and acceptance: enabled retention, correct metrics/alerts, live browser flow, mode-specific Redis/security coverage, representative migration/load datasets. | 3–5 engineer-days | Real dependencies exercise the release path; cleanup and alerts work under meaningful data volume/failure scenarios. |
| 6 | Release evidence: coherent Git history, remote CI, refreshed documentation, independent staging/security/backup/rollback acceptance. | Local preparation 1–2 engineer-days; external gate depends on environment | A revision-linked release checklist is complete for the chosen audience and deployment target. |

The first four urgent changes are **reproducibility, embedding state correctness, organization-state isolation, and bounded frontend requests**. Complete these before expanding features. Keep the modular monolith; review Kafka/partitioning only when representative measurements demonstrate a bottleneck.

## Completion criteria for a professional release

- Clean checkout and chosen CI mode pass the documented gate without personal `.env` or portable tool dependencies.
- All P1 defects above have behavior-level regression coverage and are resolved.
- A synthetic live browser flow passes against the real application dependencies.
- Tenant isolation holds in the database/API and in delayed/failed frontend transitions.
- Queue ownership, retry limits, provider budgets, and enabled retention are deterministic under concurrency.
- Relevant advisory results, operational alerts, backup recovery objectives, and deployment rollback evidence belong to a specific revision/artifact.
- Documentation clearly distinguishes demonstrated local MVP capabilities from remaining production acceptance work.
