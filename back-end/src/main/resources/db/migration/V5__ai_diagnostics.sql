ALTER TABLE intelligence.incident_groups
    ADD CONSTRAINT incident_group_tenant_id_unique UNIQUE (organization_id, project_id, id);

CREATE TABLE intelligence.incident_ai_analyses (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL,
    incident_group_id uuid NOT NULL,
    status varchar(20) NOT NULL CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','RETRYING','FALLBACK')),
    category varchar(20),
    model varchar(120) NOT NULL,
    prompt_version varchar(40) NOT NULL,
    result_json jsonb,
    failure_code varchar(40),
    attempt_count smallint NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    input_tokens integer,
    output_tokens integer,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz,
    CONSTRAINT ai_analysis_incident_tenant_fk FOREIGN KEY (organization_id, project_id, incident_group_id)
        REFERENCES intelligence.incident_groups(organization_id, project_id, id),
    CONSTRAINT ai_analysis_tokens_nonnegative CHECK ((input_tokens IS NULL OR input_tokens >= 0) AND (output_tokens IS NULL OR output_tokens >= 0))
);

CREATE INDEX incident_ai_analysis_order ON intelligence.incident_ai_analyses(organization_id, incident_group_id, created_at DESC, id);
CREATE INDEX incident_ai_analysis_queue ON intelligence.incident_ai_analyses(next_attempt_at, created_at, id) WHERE status IN ('QUEUED','RETRYING');
CREATE UNIQUE INDEX incident_ai_analysis_one_active ON intelligence.incident_ai_analyses(incident_group_id) WHERE status IN ('QUEUED','RUNNING','RETRYING');
