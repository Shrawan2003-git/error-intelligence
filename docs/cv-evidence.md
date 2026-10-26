# CV and interview evidence

Use these as factual starting points; adjust the wording to match your own contribution and the role you apply for.

## Project entry

**Error Intelligence — personal full-stack project**  
Java 21, Spring Boot, React, TypeScript, PostgreSQL/pgvector, Redis, Flyway

- Built a tenant-scoped error investigation platform with hashed ingestion keys, redacted and idempotent event intake, deterministic incident grouping, role-based workflows, and human-verified resolutions.
- Added asynchronous structured AI diagnostics and semantic retrieval with project/organization scoping, retry handling, and per-organization AI admission limits. The no-cost local demo keeps provider workers off.
- Implemented Docker-free local staging on isolated PostgreSQL and Redis services, plus migration tests, synthetic load checks, backup/restore, dependency-outage checks, and a two-artifact application rollback rehearsal.

## Quantified evidence you can explain

Two bounded local staging runs on 2026-10-11 sent 120 measured synthetic ingestion requests each, after 8 warm-ups, with 8 concurrent clients and 20 fingerprints. They returned P95 26 ms and 25 ms, with zero request errors. The load is small and single-host; earlier runs varied, including one failed 376 ms P95 regression check. Describe these as **local synthetic results**, never production capacity or a general SLO guarantee. See [Phase 8 verification](phase-8-verification.md).

The [portfolio demo](portfolio-demo.md) has live-browser screenshots of 25 synthetic events grouped into three incidents, one human-verified resolution, project analytics, and desktop/mobile layouts. These are example data, not customer data or AI-generated conclusions.

## Interview discussion points

- [Why a modular monolith](adr/0001-modular-monolith.md): preserve simple transactions and clear module boundaries until measured scale demands a split.
- [Why Kafka and partitioning are deferred](adr/0004-measured-scale-extension.md): current local evidence does not identify a bottleneck, and a broker changes acceptance, replay, and operational semantics.
- [Security model](threat-model.md): tenant checks, hashed ingestion keys, CSRF, short-lived access tokens, refresh rotation, and secret redaction.
- [Operational honesty](phase-7-verification.md): local backup/restore, outage, and rollback drills passed, while the independent production staging gate remains open.

Avoid “production deployed,” “high-scale proven,” “AI results shown in demo,” or “remote CI green” until those claims have actual evidence. A GitHub repository or hosted demo can be added later without changing the no-cost local workflow.
