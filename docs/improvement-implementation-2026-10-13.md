# Improvement implementation — 13 October 2026

This records the local implementation of the engineering review. No Git remote, public deployment, or paid provider calls were created.

Verification evidence reviewed and report completed on 17 October 2026. Backend and unit-test logs below retain their original execution timestamps.

## Implemented

1. Repaired screenshot-script lint/format gates; supplied a test-only signing fixture; passed the required signing key explicitly to Compose; switched Compose and integration fixtures to pgvector-compatible PostgreSQL. Compose workers remain off by default.
2. Added Flyway V8 for source versions and worker claim tokens. Embeddings coalesce unchanged evidence, preserve in-flight claims, and discard vectors for superseded source versions. Both queues reject expired/stale completion, and abandoned attempts reach a terminal state.
3. Added V9 for durable organization/UTC-day embedding call and character budgets across workers, similarity search, and diagnostic retrieval. Retrieval filters by embedding model.
4. Added organization-scoped query hooks, request cancellation/deadlines, session-change notifications, local logout cleanup on remote failure, and Web Locks refresh coordination across tabs. Tokens remain in memory.
5. Exposed incident page navigation and fetched later project/member/key pages. Incident details include the most recent 100 comments in chronological order.
6. Added V10 receipts that survive raw-event retention. Cleanup locks batches and drains up to 20 batches within five seconds each minute by default; retention remains disabled until configured.
7. Moved retrieval evidence contracts to similarity and ingestion events to ingestion; added an acyclic module dependency gate. Identity HTTP contracts run in native and Redis/Testcontainers modes. Disposable database contexts close after each suite.
8. Scrapes read cached metrics; collection has a transaction timeout and freshness/failure gauges. Added job-age and scrape-outage alerts and corrected the low-volume 5xx ratio. Database connections initialize in UTC, and the UI explains analytics cohort/lifetime semantics.
9. Added real browser acceptance against a disposable backend/database, covering registration, project/key creation, duplicate ingestion, collaboration/resolution, tenant switching/logout, and simultaneous tab refresh. Existing mocked smoke tests remain separate.
10. Added environment/release routes and member/key offsets to OpenAPI, plus runtime route/method coverage validation.
11. Fixed a CSRF race discovered by real browser acceptance: stateless JWT validation no longer clears the shared CSRF cookie on each request. Registration, login and logout explicitly rotate it. CSRF protection remains enabled for unsafe browser routes.

## Deployment and behavior notes

- Apply new V8–V10 migrations; older migrations are unchanged. Drain/stop older workers before deploying this version. Mixed old/new workers are unsupported because older code does not understand claims.
- A model change excludes old vectors from retrieval. Re-enqueue desired source records through evidence updates or a separately reviewed backfill. Workers remain opt-in in local/native/Compose demos.
- `AI_EMBEDDING_DAILY_CALLS` defaults to 1,000 and `AI_EMBEDDING_DAILY_CHARACTERS` to 1,000,000 per organization/UTC day. Failed attempts consume reservations. These bound workload; provider-side spending limits remain a separate setting.
- Forwarded-header handling defaults to `NONE`. Compose uses Tomcat `NATIVE` behind its private Nginx proxy. Production must restrict direct backend access and configure its actual trusted proxy boundary.
- Raw expiry does not delete embeddings, diagnostics, comments, resolutions, audits, or receipts. Receipts retain identity without event payload to preserve deduplication. Those artifact classes still require separate product policies.
- Metrics snapshots run every 30 seconds. External alert delivery needs a configured destination and staging verification.
- Chromium's Web Locks API coordinates refresh across tabs. Browsers lacking Web Locks retain in-tab coordination; additional compatibility work is required before claiming multi-tab guarantees on those browsers.

## Verification

Frontend commands: `npm run format:check`, `npm run lint`, `npm test`, `npm run build`, and desktop/mobile mocked Playwright checks.

Native command: `mvn verify -Pnative-postgres -Dit.test=Native*IT`, with Java 21, `PG_BIN`, `PGVECTOR_HOME`, installed frontend dependencies, and Chromium available. This explicitly includes `NativeBrowserIT`, which runs its frontend on loopback port 5183. Run only that acceptance check with `-Dit.test=NativeBrowserIT`.

Container CI enables `E2E_LIVE=true` after readiness. Its configuration is repaired in source but has not been run through Docker Desktop or remote Actions.

Local evidence: ignored `.local/improvement-release-tests.log`, `.local/improvement-frontend-tests.log`, and `back-end/target/live-browser.log`. Earlier verification exposed a browser selector ambiguity, leaked test contexts, a CSRF cookie race, an ambiguous Spring test bean injection and a cookie-header assertion that assumed header order. These were corrected before the successful full backend run.

Verified results:

- Backend: 19 unit tests and 22 native PostgreSQL integration tests passed, with zero failures/errors/skips; Maven verification succeeded. The integration count includes real browser acceptance, migrations, backup/restore, authorization, queues, retention and load checks.
- Frontend: 14 tests passed. Formatting, lint and production build passed. All four desktop/mobile smoke checks passed on 17 October; the isolated smoke run logged connection refusals for unmocked background health/auth requests because no backend was started. Real backend/browser acceptance passed separately within the native integration suite.
- Local ingestion load fixture: 120 requests, eight concurrent clients, p95 31 ms. This small local fixture is a regression check, not a production capacity estimate.
- Compose configuration validation passed using explicit dummy credentials and `config --quiet`; no containers were started. Container runtime and remote CI remain unverified.

## Remaining work

Remote CI/repository publication; independent HTTPS staging and actual proxy verification; least-privilege service/database accounts; secret rotation; off-machine backups and recovery objectives; real alert delivery; Java/container advisory scans; representative sustained capacity/recall evaluation; and model-quality tests with an explicit provider budget.

Account recovery/invitations and artifact-specific data lifecycle policies require product decisions. Further component/repository decomposition should follow cohesive responsibilities as features evolve; critical state handling and cyclic dependencies are fixed locally.

## Follow-up verification

The [18 October completion record](review-completion-2026-10-18.md) supersedes the earlier container-verification gap and records additional fixes, full container/browser results, and remaining security release blockers. Historical results above retain their original scope.
