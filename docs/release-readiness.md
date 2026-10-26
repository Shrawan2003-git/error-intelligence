# Local review and release readiness

The owner chose local completion on 17 October 2026. Remote publication, a staging host and production service settings are not configured. This checklist is the handoff for those decisions, not a claim of production approval.

## Local acceptance

| Area | Evidence required |
| --- | --- |
| Backend | Unit and PostgreSQL/Redis container contracts; native backup/restore and load checks |
| Browser | Synthetic register/project/key/duplicate-ingest/comment/resolve/tenant-switch/logout flow through the real proxy |
| Migration | V7 business graph survives V8–V10; ingestion receipts seeded; repeat migration is a no-op |
| Frontend | Formatting, lint, tests, build and desktop/mobile acceptance |
| Alerts | Promtool tests for low-rate failures, idle service, scrape failure/recovery and stale work/metrics |
| Advisories | Dated Java, npm and runtime-image results; high/critical findings remedied or explicitly reviewed |
| Provenance | Source manifest, artifact checksum, image IDs/digests and command results |

The current completion record is [review-completion-2026-10-18.md](review-completion-2026-10-18.md). Historical review documents remain dated snapshots.

## Isolated Docker review

Use Compose project `eip-review-20261017`, ignored generated credentials in `.local/review-compose.env`, and loopback ports 55439 (PostgreSQL), 56389 (Redis), 18089 (API), 55179 (UI). Workers are disabled. This stack has its own named database volume and network. Existing projects must not be stopped or reconfigured. Do not use Docker prune, `down -v`, or global cleanup commands.

Stop only the review stack with:

```powershell
docker compose -p eip-review-20261017 --env-file .local/review-compose.env -f infra/compose.yml --profile app down
```

This preserves its database volume. Testcontainers manages only the disposable containers it creates. The test stacks share the local Docker engine's CPU, memory and disk, so run capacity measurements separately from builds/scans.

## Before hosting real users

1. Choose the repository, private reporting contact and distribution license. Review candidate source for secrets, create coherent commits and enable required CI checks. No license is selected implicitly.
2. Select staging/production destinations and TLS termination. Restrict backend/database/Redis access; verify real client IPs and spoofed forwarded headers through the actual proxy chain.
3. Provision a migration identity and a separate runtime database identity. Runtime needs schema usage and table DML, not superuser/createdb/createrole/schema ownership. Install pgvector through the migration/admin process. Demonstrate full acceptance with these exact grants before rollout; local disposable superuser tests do not prove this.
4. Set secret-manager injection and rotation procedures, worker quotas, artifact retention and data deletion policies. Keep model workers disabled until a budget and quality evaluation are approved.
5. Choose encrypted off-machine backup storage, retention, recovery-time and recovery-point objectives. Restore to an isolated target and record a timed recovery rehearsal; a local restore test does not prove off-machine recovery.
6. Configure private metrics scraping and an alert destination. Send a synthetic test notification through the actual delivery path and record acknowledgement.
7. Run a sustained workload with approved request volume, dataset, concurrency, latency/error objectives, and exact-search retrieval baselines. Existing short local load checks are regressions, not capacity certification.
8. Record immutable artifact IDs/digests, source revision, scans, migrations and rollback rehearsal. Stop old workers before V8–V10 rollout; old and new claim protocols cannot run together. Never reverse Flyway history as a rollback shortcut.
9. Decide password recovery, verification/invitations and supported browser behavior before opening self-service registration.

## Tool references

Alert fixtures follow [Prometheus rule-testing documentation](https://prometheus.io/docs/prometheus/latest/configuration/unit_testing_rules/). Advisory gates use [Trivy filesystem scanning](https://trivy.dev/docs/latest/target/filesystem/) and runtime-image scanning. CI retains JSON results and fails on high/critical findings, including unfixed ones; results must be interpreted against the actual deployed artifact.

