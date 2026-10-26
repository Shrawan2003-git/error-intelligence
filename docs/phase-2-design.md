# Phase 2 secure ingestion design

Project API keys authenticate the separate `/ingest/v1/events` route. Keys contain 256 bits of random material, are returned once, stored only as SHA-256 digests, and can be revoked by project administrators. The ingestion request is a versioned canonical JSON event carrying a client UUID, occurrence time, environment/release identifiers, exception evidence, and optional request/environment metadata.

An early servlet filter rejects non-JSON content and request bodies over 256 KiB before event parsing. Bean validation caps nested fields and metadata sizes. The service checks key scope and revocation, rejects event timestamps more than five minutes in the future, and uses `(project_id, client_event_id)` as its idempotency constraint. Environment and release names are associated with the owning project; the event is immutable after insertion.

Sensitive text is redacted before persistence. Request headers are allow-listed; sensitive query/environment names are masked; message, stack trace, request path/body and values are checked for credential assignments, bearer values, known token formats, emails, JWTs and high-entropy strings. Redaction is defense in depth, not a guarantee against every application-specific secret format. API keys never enter event content in plaintext.

Migration V3 creates events with composite project/organization and project/environment/release constraints. Native and Testcontainers share the same MockMvc acceptance contract. The native runner uses a disposable local PostgreSQL cluster and requires no Docker installation.
