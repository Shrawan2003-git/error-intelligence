# Phase 2 verification

Verified on 9 October 2026 in the supplied Windows workspace. Secure ingestion is implemented over PostgreSQL using the Docker-free native profile.

| Check | Result |
| --- | --- |
| Java 21 compile and Spotless format check | Passed |
| Backend unit tests | 5 passed |
| Native PostgreSQL integration suite | 13 passed across migration, foundation, identity and ingestion contracts |
| Flyway V3 clean install and additive upgrade fixture | Passed on PostgreSQL 18.4 |
| API key issuance and at-rest digest | Passed; one-time secret is not stored in plaintext |
| Event normalization and persistence | Passed; event ties to project, organization, environment and optional release |
| Redaction fixture | Passed for bearer/API keys, emails, query values, headers, body excerpts and environment metadata |
| Revocation and duplicate idempotency | Passed; revoked key denied and duplicate UUID returns the original receipt without another row |
| Content type and body size limits | Passed; unsupported media type and payload above 256 KiB rejected |
| Frontend Prettier, ESLint, Vitest and TypeScript build | Passed; 10 unit tests |
| Desktop/mobile browser flows | Passed; 4 tests including one-time key creation and revocation UI |

The native integration gate creates and removes its own temporary PostgreSQL clusters. It did not use Docker and did not modify the persistent local development database. The Docker/Testcontainers variant compiles as part of Maven but was not run because Docker is not part of this environment.

Redaction heuristics can miss an unknown secret pattern; projects should avoid sending unrelated personal data. Keys are scoped to ingestion only, can be immediately revoked, and are protected by an IP/key rate limit.
