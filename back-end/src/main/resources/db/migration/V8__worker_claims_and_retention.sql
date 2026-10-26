ALTER TABLE intelligence.embedding_jobs
    ADD COLUMN source_hash char(64) NOT NULL DEFAULT '',
    ADD COLUMN source_version bigint NOT NULL DEFAULT 1,
    ADD COLUMN claim_token uuid,
    ADD COLUMN claimed_version bigint;
ALTER TABLE intelligence.incident_ai_analyses ADD COLUMN claim_token uuid;
CREATE INDEX embedding_job_abandoned ON intelligence.embedding_jobs(updated_at)
    WHERE status = 'RUNNING';
CREATE INDEX analysis_job_abandoned ON intelligence.incident_ai_analyses(updated_at)
    WHERE status = 'RUNNING';
CREATE INDEX event_expiration_order ON intelligence.error_events(occurred_at,id);
