# Back-end

Java 21 and Spring Boot modular monolith for identity, tenant-scoped projects, secure ingestion, incident workflows, AI diagnostics, similarity, analytics, and operations. PostgreSQL/Flyway owns the data schema; Redis backs distributed rate limiting outside the loopback-only native profile.

From the repository root, initialize local configuration and run the back-end:

```powershell
node scripts/initialize-local.mjs
powershell -File scripts/Local-Database.ps1
powershell -File scripts/Run-Backend.ps1
```

The helper preserves an existing `.env`; it does not print generated secrets. The database uses project-local data and a dedicated port. The local database script loads pgvector from the ignored project toolchain before Flyway applies migration V6. For the full Docker-free verification gate:

```powershell
powershell -File scripts/Run-Backend.ps1 -Action VerifyNative
```

That gate uses isolated disposable PostgreSQL clusters, migrations, unit and integration tests, backup/restore, and synthetic load checks. See the [root README](../README.md#testing) and [architecture](../docs/architecture.md) for details. Java source and tests are under `src/`; database migrations are under `src/main/resources/db/migration/`.

The no-cost local demo leaves AI workers disabled. The independent production staging gate remains open; see the [staging checklist](../docs/phase-7-staging-checklist.md).
