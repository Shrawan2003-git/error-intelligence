# Phase 7 no-cost local staging rehearsal

This personal-project rehearsal runs on one computer without Docker Desktop or a paid hosting account. It uses a new PostgreSQL cluster in `.local/staging/postgres` on `127.0.0.1:15433`, a separate Redis process in the project's `ErrorIntelligenceDev` WSL distribution on port `16379`, and the Java backend on `127.0.0.1:18080`. The development database and Redis ports are separate. Staging credentials are generated in ignored `.local/staging/staging.env` and never printed. The OpenAI key is deliberately removed from the staging process, and both AI workers are disabled, so the rehearsal makes no paid provider calls.

## Run it again

From the project root in PowerShell:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts\Local-Staging.ps1 -Action Start
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts\Test-Staging.ps1 -BaseUrl http://127.0.0.1:18080 -ExpectedVersion 0.1.0-SNAPSHOT -AllowHttp
node scripts\Verify-Local-Staging.mjs
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts\Verify-Local-StagingRestore.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts\Verify-Local-StagingRedisOutage.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts\Verify-Local-StagingDatabaseOutage.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts\Local-Staging.ps1 -Action Stop
```

The verification script creates only synthetic accounts, projects, keys, and events in this isolated staging database. Its staging key is kept in process memory for the bounded load run. `Stop` preserves the database and generated credentials for another rehearsal. Do not use `-AllowHttp` or `--allow-http-loopback` with a non-loopback address.

The rollback rehearsal used two immutable JAR copies in ignored `.local/staging/releases`: release A SHA-256 `5CA20F8F8A462C208F345A0BAAB9DE314AB0FA028F7FF648CD28554F7BB9363E` and release B SHA-256 `E6FC7FE5E716EEAFA0DDEC19E20E04B745E5AAA50BC805FC3B510300312F2712`. To replay it, stop local staging, start with `-JarPath .local/staging/releases/release-b.jar -ReleaseLabel 0.1.0-local-b`, run the smoke and synthetic checks, stop, then start with `-JarPath .local/staging/releases/release-a.jar` and confirm the original version and event count. Never replace a JAR while Windows is running it.

## Evidence

| Check | Local result |
| --- | --- |
| No-cost isolated services | PostgreSQL/Redis/backend on separate project-local ports; Docker Desktop untouched |
| Production-style profile | Redis required for readiness; `native` profile absent |
| Health/version | Liveness and readiness UP; `0.1.0-SNAPSHOT` |
| Synthetic ingestion and retrieval | HTTP 202 and incident visible through the API |
| Bounded load | Initial run: 120 measured requests, 8 clients, P95 31 ms, P99 35 ms, 347.1 requests/s. Release B run: P95 240 ms, P99 324 ms, 54.5 requests/s. Both had 0 errors. |
| Isolated backup restore | 129 synthetic events restored and checked in a new temporary database; temporary dump/database removed |
| Redis-backed login limit and AI daily quota | Passed on release B: sixth invalid login returned 429; active analysis reused, second incident returned `AI_DAILY_LIMIT_REACHED` at configured cap 1. No provider call. |
| Two-artifact deploy and rollback | Passed locally: release B `0.1.0-local-b` deployed; release A `0.1.0-SNAPSHOT` restored, readiness UP. The same database retained 258 synthetic events, 42 incident groups and Flyway V7. |
| Redis outage and recovery | Readiness DOWN/503 and login failed closed with `AUTH_UNAVAILABLE`; readiness UP after only staging Redis restarted. |
| PostgreSQL outage and recovery | Readiness DOWN/503 while only staging PostgreSQL stopped; readiness UP after restart. |
| Metrics | Local loopback Prometheus endpoint returned HTTP 200 with HTTP server request metrics. |

Performance is host-dependent: the full backend suite had one local P95 failure at 376 ms; an isolated rerun passed at 71 ms. Treat the local measurements as regression signals, not capacity guarantees.

This proves a local rehearsal, not the blueprint's independent production staging gate. It does not verify public TLS, a restricted service account, a cloud secret manager, off-machine encrypted backup retention, external alert delivery, or availability during a computer outage. Report these as future deployment work in a CV or interview; do not call the app production deployed.
