# Phase 7 Production Hardening Verification

Local, Docker-free backend hardening checks ran on 2026-10-11. The checks use newly initialized PostgreSQL clusters under `back-end/target`; they do not connect to the configured development database or call an external AI provider. The later no-cost deployment rehearsal uses a separate project-local staging database.

## Automated checks

| Check | Result |
| --- | --- |
| Backend formatter and 18 unit tests | Passed |
| Full native PostgreSQL migration and integration suite (19 tests, Flyway V1-V7) | Earlier run passed 19/19; release B run passed 18/19 with one load P95 failure at 376 ms; isolated load rerun passed at 71 ms |
| Backup/restore: PostgreSQL custom dump restored into a fresh database and marker verified | Passed |
| Native auth rate-limit unit contract (`429 Too Many Requests`) | Passed |
| Daily AI analysis cap per organization, including active duplicate reuse | Passed: second distinct incident receives `429 AI_DAILY_LIMIT_REACHED` at a test cap of one |
| Earlier warmed ingestion load: 120 measured events, 8 concurrent clients, 20 fingerprints | Passed: P50 10 ms, P90 16 ms, P95 18 ms, P99 20 ms, max 20 ms, 641.7 requests/s |
| Event persistence after load, including 8 warm-up events | Passed: 128 events present |
| Frontend format, lint, production build, and 10 unit/component tests | Passed |
| Staging smoke URL guards and load-script syntax/metric calculation | Passed with mocked responses; then exercised against loopback local staging |
| Isolated no-cost local staging with Redis-required readiness | Passed on 127.0.0.1:15433 (PostgreSQL), 16379 (Redis), and 18080 (backend); no Docker used |
| Synthetic API flow, Redis login limit, AI daily quota | Passed against local staging with AI workers disabled and no provider call |
| Local staging load | Initial P95 31 ms, P99 35 ms, 347.1/s; release B P95 240 ms, P99 324 ms, 54.5/s; 120 measured requests/8 clients each, 0 errors |
| Local staging isolated restore | Passed: 129 synthetic events restored to a newly created database, then temporary restore files removed |
| Local Redis and PostgreSQL outage/recovery | Passed: readiness DOWN/503 during each outage, UP after restart; Redis login failed closed with `AUTH_UNAVAILABLE` |
| Two immutable JARs deployed and rolled back locally | Passed: release B to release A; 258 synthetic events and 42 incident groups retained at Flyway V7 |

The P95 gate is below 300 ms for the warmed local workload. Before adding warm-up, two uncached runs measured P95 at 870 ms and 469 ms; an earlier warmed run measured 47 ms. During the second-JAR build, the full native suite's load test failed at P95 376 ms; an isolated rerun passed at P95 71 ms. This demonstrates host-sensitive variability. The passing steady-state numbers are not production capacity guarantees. Representative independent staging load remains future deployment work.

The restore drill uses a temporary database in the test cluster only. The dump and test clusters are disposable. The application database configured in `.env` is not a restore target.

The configuration review found required database credentials and signing key, hidden error messages/stack traces, and Actuator exposure limited to health and Prometheus. The Prometheus CIDR filter and tenant/role checks are covered by local tests. The staging proxy, service permissions, and actual log/trace output still require environment review.

The daily AI cap limits admission of new analysis jobs; it does not replace a provider-side spend budget. A fresh dependency-advisory query could not be completed in this environment because access to the public registry was restricted. Dependency findings still need review before release.

The local rollback used release A SHA-256 `5CA20F8F8A462C208F345A0BAAB9DE314AB0FA028F7FF648CD28554F7BB9363E` and release B SHA-256 `E6FC7FE5E716EEAFA0DDEC19E20E04B745E5AAA50BC805FC3B510300312F2712`. The current `back-end/target` JAR matches release B. Both releases were exercised against the same isolated local database; release A is the final running version before staging shutdown. See [the no-cost rehearsal](phase-7-local-rehearsal.md).

## Remaining Phase 7 acceptance gate

The user's no-spend constraint rules out a paid staging host for this personal project. The local rehearsal exercises most app-level behavior, but it shares one computer with development and uses loopback HTTP. Public TLS, a restricted service account, cloud secret injection, off-machine encrypted scheduled backups, external alert delivery, independent-host capacity, and a current external dependency-advisory review remain unverified. Therefore the blueprint's **production staging acceptance gate remains open**; describe Phase 7 as locally rehearsed, not production deployed. See [the full staging checklist](phase-7-staging-checklist.md) and [the runbook](runbook.md).
