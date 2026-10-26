CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE intelligence.embedding_jobs (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL,
    incident_group_id uuid NOT NULL,
    source_type varchar(20) NOT NULL CHECK (source_type IN ('INCIDENT','RESOLUTION')),
    source_id uuid NOT NULL,
    source_text text NOT NULL CHECK (length(source_text) <= 12000),
    status varchar(20) NOT NULL CHECK (status IN ('QUEUED','RUNNING','RETRYING','SUCCEEDED','FAILED')),
    attempt_count smallint NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    failure_code varchar(40),
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT embedding_job_incident_tenant_fk FOREIGN KEY (organization_id, project_id, incident_group_id)
        REFERENCES intelligence.incident_groups(organization_id, project_id, id),
    CONSTRAINT embedding_job_active_unique UNIQUE (organization_id, project_id, source_type, source_id)
);

CREATE INDEX embedding_job_queue ON intelligence.embedding_jobs(next_attempt_at, created_at, id)
    WHERE status IN ('QUEUED','RETRYING');

CREATE TABLE intelligence.incident_embeddings (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL,
    incident_group_id uuid NOT NULL,
    source_type varchar(20) NOT NULL CHECK (source_type IN ('INCIDENT','RESOLUTION')),
    source_id uuid NOT NULL,
    embedding_model varchar(120) NOT NULL,
    source_text text NOT NULL CHECK (length(source_text) <= 12000),
    embedding vector(1536) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT incident_embedding_incident_tenant_fk FOREIGN KEY (organization_id, project_id, incident_group_id)
        REFERENCES intelligence.incident_groups(organization_id, project_id, id),
    CONSTRAINT incident_embedding_unique UNIQUE (organization_id, project_id, source_type, source_id)
);

CREATE INDEX incident_embeddings_incident_hnsw ON intelligence.incident_embeddings
    USING hnsw (embedding vector_cosine_ops) WHERE source_type = 'INCIDENT';
CREATE INDEX incident_embeddings_resolution_hnsw ON intelligence.incident_embeddings
    USING hnsw (embedding vector_cosine_ops) WHERE source_type = 'RESOLUTION';
