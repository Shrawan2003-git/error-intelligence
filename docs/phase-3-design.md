# Phase 3 incident grouping design

Each newly committed ErrorEvent raises an internal Spring event. A transaction listener groups it before the ingestion transaction commits, so the event, fingerprint, occurrence and group counter commit or roll back together. The path does not call AI or an external service.

Fingerprint version 1 hashes the normalized exception type, the first five stack frames without source line numbers, and the route path without query parameters. UUID and timestamp noise, request/trace IDs, and numeric or UUID route segments are normalized. The version is stored on both event fingerprints and groups; future algorithms can add another version through an explicit regrouping job without silently rewriting the current key.

PostgreSQL enforces a unique `(project, fingerprint version, fingerprint)` group key, a unique `(event, fingerprint version)` fingerprint, and one incident occurrence per event. The service inserts the fingerprint first and increments the group only for the transaction that won the event fingerprint. The group upsert updates first/last-seen bounds and count atomically under concurrent ingestion.

All incident reads join the active organization scope. Project lists support status, environment, release, title search, pagination and newest-first ordering. Details return the latest 20 sanitized events, up to 100 comments, and up to 50 verified resolution records. OWNER, ADMIN and DEVELOPER roles can change status or assignment, comment, and verify a resolution; VIEWER is read-only. A direct status change cannot close an open incident; the resolution action requires a summary and root cause, records the verifier, and closes the group. Mutations append audit events.

Flyway V4 adds incident groups, versioned event fingerprints, occurrence links, comments and resolution history. Tenant/project foreign keys and unique indexes keep all group records scoped. The native and Testcontainers integration contracts cover concurrent grouping and the complete investigation workflow.
