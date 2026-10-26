# Phase 1 verification

Verified on 9 October 2026 in the supplied Windows workspace. Phase 1 delivers authentication, organization tenancy, membership roles and project management over the Docker-free native profile.

## Results

| Check | Result |
| --- | --- |
| Java 21 and Maven enforcement | Passed with the project-local toolchain |
| Backend compile and formatting | Passed |
| Backend unit tests | 3 passed |
| Native PostgreSQL integration tests | 11 passed across migration, foundation and identity suites |
| Flyway V1/V2 on PostgreSQL 18.4 | Passed; schema validation and clean migration passed |
| Additive upgrade fixture | Passed; existing probe data remained intact |
| Registration and role setup | Passed; each new account owns its new organization |
| Password and refresh-token storage | Passed; BCrypt password hash and SHA-256 refresh digest stored, never raw values |
| Refresh rotation/reuse | Passed; reuse revoked the session and its access JWT |
| Organization-scoped session | Passed; current membership is checked on every request |
| Role enforcement | Passed; VIEWER can read, cannot create projects |
| Tenant isolation | Passed; non-member project read returns 404 |
| Authentication limits | Passed; sixth login attempt for one account returns 429 |
| Frontend tests | 10 passed across 3 files |
| ESLint, TypeScript, Prettier and production build | Passed |
| Browser tests | 4 passed across desktop and mobile; mocked account registration and project creation verified |
| Live native application at Phase 1 verification | API reported phase IDENTITY; PostgreSQL readiness UP |

The native integration suite starts isolated, disposable PostgreSQL clusters under `back-end/target/native-postgres` and stops them when the tests finish. It does not modify the persistent local development database.

## Docker-free rate-limit boundary

Production uses atomic Redis-backed rate limits and fails closed if Redis is unavailable. The loopback-only `native` profile uses a one-minute, bounded process-local map for single-instance development. Counters are not shared across multiple application processes. Native readiness checks PostgreSQL only, and the UI reports that boundary. No Docker or WSL service was needed.

## Scope notes

Account recovery and email invitations are not configured. Administrators can add an account after it has registered; the UI says no invitation email is sent. Login sessions are limited to 30 days, access tokens to 10 minutes, and refresh-token reuse revokes the session. Source/API-key ingestion begins in Phase 2.

Container/Testcontainers and remote CI remain independent deployment gates; this native result does not represent them as passed.
