# Contributing

Use Java 21, Maven 3.9 and Node 22.12+ (22.x) or 24.x. Install frontend dependencies with `npm ci`. Keep personal credentials in ignored configuration; never copy production data into tests.

## Before proposing a change

- Run frontend `npm run format:check`, `npm run lint`, `npm test`, and `npm run build`.
- With Docker available, build the test database image from the repository root using `docker build -t eip-postgres-test:local infra/postgres`, then run backend `./mvnw verify`. This uses disposable databases with the same PostgreSQL/pgvector build as Compose. To use a separately named local image, pass `-Dtest.postgres.image=your-test-image:tag`. For the supported Windows native path, follow the README; its coverage differs from the container gate.
- Run the real browser acceptance flow for changes to authentication, projects or incidents. Keep model providers disabled or stubbed.
- Add behavior regressions for authorization, concurrency or data-loss risks. Formatting-only changes do not require new tests.
- Add new Flyway migrations; never modify shared migration history. State rollout/rollback constraints.
- Keep module dependencies acyclic and OpenAPI routes in sync. Prefer cohesive feature extraction over generic abstractions.

Describe the problem, final behavior, relevant test results and operational constraints in each review. Keep changes focused. Do not include credentials, raw customer events, generated build output or local databases in commits. Record the source revision and artifact digest with release evidence.

## Release prerequisites

See `docs/release-readiness.md`. Passing local checks does not approve production rollout. Repository hosting, public license, staging destination and operational ownership remain owner decisions.
