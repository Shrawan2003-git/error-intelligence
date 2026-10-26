# Phase 8 Scale Extension Verification

**Decision gate completed 2026-10-11:** [ADR 0004](adr/0004-measured-scale-extension.md) records why the conditional Kafka worker, batch analytics, and time partitioning are deferred. No schema, API, or runtime change was justified by the measured workload.

## Evidence

| Environment and run | Workload | Ingestion P95 | Throughput | Errors |
| --- | --- | ---: | ---: | ---: |
| Local staging, Phase 8 run 1 | 8 warm-ups + 120 measured events; 8 clients; 20 fingerprints | 26 ms | 466.4/s | 0 |
| Local staging, Phase 8 run 2 | Same synthetic workload, newly provisioned tenant/project | 25 ms | 435.4/s | 0 |
| Earlier Phase 7 release B local staging | Same bounded workload | 240 ms | 54.5/s | 0 |
| Earlier Phase 7 full native-suite run | Same 120-event/8-client load shape | 376 ms | Not recorded | 0 reported; P95 gate failed |

The two fresh runs also verified Redis-required readiness and login rate limiting, API provisioning, ingestion, incident retrieval, and the local AI admission cap. The isolated staging database held 516 synthetic events and 84 incident groups after these runs. PostgreSQL and Redis were stopped afterward; their project-local data and generated credentials were preserved. Docker Desktop was not used, and AI workers were disabled to avoid paid API calls.

The blueprint target is ingestion P95 under 300 ms **at a target test load**. These short local runs meet that target for their stated load, but the small dataset and prior variability prevent a production scale claim. There is no representative sustained load or independent-host analytics P95 measurement. The Phase 7 production staging acceptance gate remains open as documented in [Phase 7 verification](phase-7-verification.md). If the workload grows, follow ADR 0004's measurement and review criteria before implementing a scale extension.
