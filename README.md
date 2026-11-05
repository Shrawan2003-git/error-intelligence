# Error Intelligence

Error Intelligence collects failures from Java applications and turns repeated errors into incidents that a team can investigate and resolve. I built it to make the history around an exception useful: where it happened, how often it recurred, who investigated it, and what fixed it.

[Product walkthrough](docs/demo/error-intelligence-walkthrough.webm) · [Screenshots](docs/portfolio-demo.md) · [User guide](docs/user-guide.md) · [Deployment guide](docs/deployment.md)

## How it works

1. A Java service sends an error event to `POST /ingest/v1/events` with a key scoped to one project.
2. The API validates and redacts the event, stores it in PostgreSQL, and groups matching failures into an incident.
3. Team members review occurrences, assign an owner, discuss the cause, and record a verified resolution.
4. Project analytics show event volume and incident status over time. Optional AI diagnostics and semantic search can use past verified resolutions when a provider is configured.

The browser does not collect application logs by itself. See the [Java sender example](examples/java-sender/README.md) for an integration point. The walkthrough and screenshots use synthetic data.

## What is implemented

- Email-verified registration, sign-in, password reset, organization roles, and project-level access.
- Rotating refresh sessions, CSRF protection, rate limiting, and revocable ingestion keys stored as hashes.
- Bounded event intake with redaction, deduplication, deterministic grouping, and incident history.
- Assignment, comments, verified resolutions, analytics, audit records, and raw-event retention.
- Optional queued AI diagnostics and pgvector-based retrieval. Both AI workers are disabled in the no-cost demo configuration.

The backend is a modular Spring Boot application. React calls its API through a same-origin proxy; PostgreSQL holds application data, and Redis supports shared rate limits. [Architecture details](docs/architecture.md) and [API reference](docs/openapi.yaml) are available in `docs/`.

## Stack

| Area | Technologies |
| --- | --- |
| API | Java 21, Spring Boot, Spring Security, JPA, Flyway |
| Data | PostgreSQL, pgvector, Redis |
| Web | React, TypeScript, Vite, TanStack Query |
| Tests | JUnit, Testcontainers, Vitest, Playwright |
| Deployment | Docker Compose, Caddy HTTPS |

## Run locally

The portable path for a fresh clone uses Docker Desktop and Node.js 22.12 or newer. From the repository root in PowerShell:

```powershell
node scripts/initialize-local.mjs
New-Item -ItemType Directory -Force .local/mail | Out-Null
docker compose --env-file .env -f infra/compose.yml -f infra/compose.ci.yml --profile app up --build -d --wait
```

Open <http://127.0.0.1:5173/login>. Registration and password-reset links are written to ignored text files in `.local/mail` for this local setup. Follow the [user guide](docs/user-guide.md) to create a project, issue an ingestion key, and send a synthetic event. The first container build can take several minutes.

To stop the local services without deleting their database volume:

```powershell
docker compose --env-file .env -f infra/compose.yml -f infra/compose.ci.yml --profile app down
```

Local secrets are generated into `.env` and are excluded from Git. The [front-end](front-end/README.md) and [back-end](back-end/README.md) guides cover running each service separately.

## Tests

```powershell
cd front-end
npm ci
npm run lint
npm test
npm run build
cd ..
```

The backend verification suite requires Java 21 and a running Docker engine for Testcontainers:

```powershell
cd back-end
.\mvnw.cmd -B -ntp verify
```

The repository also has a [CI workflow](.github/workflows/ci.yml). For the user-facing path, verify registration, email confirmation, password recovery, event ingestion, and incident resolution with the [user guide](docs/user-guide.md).

## Deployment status

The source and deployment package are in this repository. A public application URL has not been verified yet. The [deployment guide](docs/deployment.md) covers the VM, DNS, SMTP, HTTPS, backups, and the final public checks. The free configuration keeps paid AI calls off; email verification and password recovery need a working SMTP account on the host.

## Repository layout

```text
back-end/   Spring Boot API and database migrations
front-end/  React application and browser tests
infra/      Local and public Docker Compose configurations
examples/   Java error sender
scripts/    Local setup and verification helpers
docs/       User guide, architecture, API, and test evidence
```

Built by [Shrawan Gautam](https://github.com/Shrawan2003-git).
