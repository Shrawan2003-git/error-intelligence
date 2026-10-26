# ADR 0004: Gate scale extensions on measured bottlenecks

Status: accepted for the current personal-project workload (2026-10-11).

## Context

The blueprint makes Kafka ingestion, batch analytics, and time partitioning conditional on measured limits. Its stated ingestion target is P95 below 300 ms at the target test load. PostgreSQL already owns event ingestion, incident grouping, and bounded project analytics; adding a broker or a second analytics data path would introduce delivery, replay, consistency, and operating costs.

The Phase 7 Docker-free local staging verifier sent 8 warm-ups and 120 measured synthetic events per run, with 8 concurrent clients and 20 fingerprints. Two fresh Phase 8 runs on 2026-10-11 returned P95 26 ms at 466.4 requests/s and P95 25 ms at 435.4 requests/s, with zero request errors. The isolated staging database held 516 synthetic events and 84 incident groups afterward. Earlier local runs varied substantially, including P95 240 ms for a release B staging run and 376 ms for one full native-suite run. These small, single-host samples establish a regression baseline, not a production capacity limit or sustained throughput promise. AI workers were disabled, and no external provider was called.

## Decision

Keep synchronous, transactional ingestion and grouping in the modular monolith. Keep project analytics as bounded PostgreSQL queries. Do not add Kafka, batch analytics, or time partitioning now: the measured workload has not established a scaling bottleneck that requires any of them. Preserve the existing tenant/time indexes and the 7–90 day analytics query bound. This closes the Phase 8 *architecture-decision* exit gate; it does not close the independent production staging gate from Phase 7.

## Revisit criteria and order

1. Define a representative event rate, payload mix, fingerprint cardinality, tenant count, retention period, and peak duration. Repeat cold and warmed runs on an independent staging host, including concurrent incident reads and analytics. Record P50/P95/P99, sustained throughput, error rate, CPU, JVM, database pool waits, query plans, index/vacuum pressure, and database growth. The ingestion target remains P95 below 300 ms at that agreed load.
2. If ingestion repeatedly misses the target, profile the limiting step and tune the synchronous path, indexes, pool sizing, and host resources first. Consider Kafka only if durable buffering and independently scalable normalization are required after those fixes. A broker proposal must specify acceptance semantics, per-project ordering, idempotency, retries/dead letters, replay, backpressure, tenant isolation, and how PostgreSQL remains the system of record.
3. If dashboard queries miss an agreed P95 at representative history size, inspect `EXPLAIN (ANALYZE, BUFFERS)` and optimize bounded SQL/indexes first. Consider incremental aggregates or batch analytics only when repeated queries over retained history remain a measured bottleneck. Define rebuild and reconciliation from source events before adding a second data path.
4. If event-table size, retention-delete cost, vacuum/index maintenance, or time-range query plans become limiting at the agreed retention volume, evaluate monthly time partitioning. Prove tenant-scoped queries, idempotency, foreign keys, retention, and backward-compatible Flyway rollout on a copy of representative data first.

## Consequences

The current stack remains free to run locally and avoids an unjustified broker and migration risk. The decision must be reviewed after representative load evidence or a material workload change. The single-computer results cannot support a claim that Kafka, batching, or partitioning will never be needed.
