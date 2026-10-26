# ADR 0002 Foundation stack

Status: accepted within the approved blueprint alternatives.

## Decision

- Java 21, Spring Boot 3.5.16, Maven Wrapper 3.9.16.
- React 19.3, TypeScript 6.0.3, Vite 8.3.4 and TanStack Query.
- PostgreSQL and Redis; Flyway 11.20.3 migration-only schema evolution.
- JUnit 5/Mockito/MockMvc and Testcontainers 2.0.5.
- Vitest/Testing Library and Playwright.
- Docker images are optional local packaging; direct host development is supported.

Spring Boot 3.5 is selected as a maintained release line with the blueprint's JUnit 5 stack. Future Spring AI integration must use the compatible 1.1 release line unless a coordinated upgrade is reviewed. TypeScript 6 is selected because the current TypeScript ESLint peer range excludes TypeScript 7.

Flyway is overridden within its 11.x release line because the Boot-managed version warned that PostgreSQL 18 was newer than its tested support. Clean, repeated and additive-upgrade migrations were verified against the installed PostgreSQL 18.4 using 11.20.3 without that compatibility warning.

AI dependencies and credentials are deliberately introduced in Phase 4. pgvector arrives in Phase 5. There are no model calls or AI costs in Phase 0.

## Version policy

Application dependencies are managed through the Spring Boot/Testcontainers BOMs and exact npm versions with a committed lockfile. Maven distribution checksum is recorded in wrapper configuration. Runtime image digest pinning and automated image scanning must be finalized before production deployment; phase 0 packaging is not production approval.
