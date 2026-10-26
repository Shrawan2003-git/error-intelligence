# Phase 7 Production Hardening Design

Phase 7 turns the blueprint's readiness criteria into repeatable Docker-free checks. The tests use disposable local PostgreSQL clusters and loopback HTTP only. They never point `pg_restore` at the application's configured database, call an external AI provider, or deploy an image.

## Load verification

`NativeLoadIT` starts a random-port Spring Boot server against its own native PostgreSQL cluster, seeds one synthetic project/key, warms the HTTP connector and connection pool with eight requests, then sends 120 synthetic ingestion requests from eight concurrent clients across 20 fingerprints. It records per-request latency, throughput, P50, P95, and P99. The local steady-state acceptance threshold is P95 below 300 ms; this is a smoke/load regression baseline, not a claim of production capacity. Earlier uncached runs are documented separately; production tests should report cold-start and steady-state results independently. Repeat with managed staging resources and representative payloads before making capacity commitments.

## Backup and restore verification

`NativeBackupRestoreIT` applies the full Flyway schema to a disposable cluster, inserts a synthetic marker, takes a PostgreSQL custom-format dump, creates a fresh restore database in the same test cluster, restores the dump, and verifies the marker. The dump is deleted after the test. Operators should retain encrypted, access-controlled backups outside source control and schedule restore drills against isolated databases.

## Security and rate limits

The native rate limiter receives a 429 contract test. Existing API integration tests cover tenant object isolation, hashed/revocable ingestion keys, CSRF, oversized payload rejection, redaction, AI authorization, audit roles, and the Prometheus CIDR filter. AI analysis requests have a configurable daily per-organization cap enforced under a database row lock, so multiple app instances cannot admit new jobs beyond the same count. Existing production configuration keeps distributed limits on Redis and fails closed when Redis is unavailable; the native in-memory limiter is single-process only. A provider-side monetary budget remains necessary because request count alone does not cap token cost.

## Deployment and rollback

No cloud account or staging environment is configured. The deployment checklist requires a staging smoke pass, immutable artifact identity, TLS, private managed PostgreSQL/Redis, secret-manager injection, backup verification, backward-compatible Flyway migration, readiness check, and a previous-version rollback procedure. No production deployment or data restore runs as part of this local phase. Phase 7 remains open until the staging checklist passes.
