# ADR 0003 Docker free foundation development

Status: user requested development without Docker.

## Context

Docker is installed but its local engine is unavailable. The machine already has PostgreSQL 18. Global Java defaults to 17, while the required application uses Java 21.

## Decision

Use project-local Java 21 and Maven, and direct React development. Reuse installed PostgreSQL binaries to initialize a separate SCRAM-authenticated loopback-only cluster under .local on port 15432. Never change the existing PostgreSQL service, its data or credentials.

Supply a native-postgres verification profile that initializes disposable test clusters under back-end/target, uses the same API/migration assertions as the container path, and shuts down its own servers afterward. The default Testcontainers gate remains intact.

Phase 0 implements no Redis-backed business feature. The native Spring profile disables only unused Redis health checks and reports cacheRequired=false. PostgreSQL readiness remains enforced. Redis remains part of the required stack and must be provisioned before distributed rate limiting; native mode must not silently become a production configuration.

## Consequences

Foundation development and PostgreSQL acceptance tests do not require Docker. Redis connectivity and container runtime/image checks remain separate unverified gates until their infrastructure is available. CI configuration retains the full container path. No H2, SQLite, Memurai or alternate database/cache substitutes are introduced.
