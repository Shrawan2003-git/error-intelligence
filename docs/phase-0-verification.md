# Phase 0 verification

Verified on 9 October 2026 in the supplied Windows workspace. Development uses Java 21 and the installed PostgreSQL 18.4 without Docker. The local application is runnable; container/remote CI gates are recorded separately and are not represented as passed.

## Observed results

| Check | Result |
| --- | --- |
| Java version and Maven enforcement | Passed with project-local Java 21.0.12.1 and Maven 3.9.16 |
| Backend formatting, compilation and packaged JAR | Passed |
| Backend unit tests | 3 passed, 0 failed, 0 skipped |
| Native PostgreSQL integration tests | 9 passed, 0 failed, 0 skipped |
| Clean/repeated baseline migration | Passed on real PostgreSQL |
| Additive migration upgrade with existing data | Passed; original row preserved |
| HTTP metadata, security headers and status-only health | Passed |
| Anonymous and authenticated forbidden routes | Passed |
| Unsafe request/CSRF denial | Passed |
| Database outage | Readiness returns 503; liveness remains 200; test database restarted |
| Caller-controlled correlation and exception cleanup | Passed |
| Frontend tests | 9 passed across 2 files |
| Prettier, ESLint and strict TypeScript | Passed |
| Frontend production build | Passed |
| Live browser smoke tests | 2 passed: desktop and mobile, with backend readiness required |
| Browser visual review | Workspace/setup checked at 1440px and 390px; no browser exceptions or horizontal page overflow |
| Frontend production dependency audit | 0 reported vulnerabilities |
| Local credentials in publishable source | No generated database/cache passwords found |
| Ignore rules | .env, .tools, .local, node_modules and target excluded |
| Existing PostgreSQL service | Still running; existing data/credentials unchanged |

## Local runtime

- Frontend: http://127.0.0.1:5173
- Backend: http://127.0.0.1:8080
- Isolated project PostgreSQL: 127.0.0.1:15432, data in .local/postgres/data.
- GET /api/v1/system returns FOUNDATION and cacheRequired=false.
- GET /actuator/health/readiness returns UP when native PostgreSQL is ready.
- Redis is explicitly deferred in native foundation mode; no Redis-dependent business feature is implemented yet.

Use scripts/Local-Database.ps1 and scripts/Run-Backend.ps1 to restart local development. Database initialization uses SCRAM credentials, loopback networking and a project-owned data directory. The installed system PostgreSQL cluster is not reused or changed.

## Issues found and resolved

- Global Java was 17 and Maven was unavailable: downloaded checksum-verified project-local Java 21/Maven; added wrapper and version enforcement.
- Docker engine was unavailable: honored the request for native development; retained optional packaging/default Testcontainers checks.
- Latest TypeScript exceeded the current ESLint supported peer range: pinned compatible TypeScript 6.0.3.
- Managed Flyway warned about PostgreSQL 18: pinned Flyway 11.20.3 and reverified migrations without that warning.
- Mockito dynamically attached its agent: configured an explicit test agent; final run has no dynamic-attachment warning.
- Initial helper-script lint errors and a formatting mismatch: corrected imports/formatting and reran the failing checks successfully.

Connection-refused warnings in the integration report are expected evidence of the deliberately induced database outage. They are not hidden or treated as successful connectivity.

## Remaining gates

- Docker images, full PostgreSQL/Redis Testcontainers runtime, Nginx container startup and container smoke workflow have not been executed locally.
- GitHub CI configuration exists, but no remote repository has been created/pushed and no remote CI result is claimed.
- Redis must be provisioned and included in readiness before rate limiting is implemented. Native Phase 0 mode is not production configuration.
- Image digest pinning, full dependency/SAST/container scanning, telemetry export, load targets, backups and production rollout remain later delivery gates.

The source blueprint's full container/remote CI exit gate remains pending. The native foundation path is verified and available for review. Phase 1 business implementation has not started.
