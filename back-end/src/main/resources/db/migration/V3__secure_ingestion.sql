CREATE TABLE intelligence.error_events (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL,
    environment_id uuid NOT NULL,
    release_id uuid,
    client_event_id uuid NOT NULL,
    occurred_at timestamptz NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now(),
    exception_type varchar(500) NOT NULL,
    message varchar(4000) NOT NULL,
    stack_trace varchar(12000) NOT NULL DEFAULT '',
    request_metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    environment_metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT event_org_project_fk FOREIGN KEY (organization_id, project_id)
        REFERENCES intelligence.projects(organization_id, id),
    CONSTRAINT event_project_environment_fk FOREIGN KEY (project_id, environment_id)
        REFERENCES intelligence.environments(project_id, id),
    CONSTRAINT event_project_release_fk FOREIGN KEY (project_id, release_id)
        REFERENCES intelligence.releases(project_id, id),
    CONSTRAINT event_project_client_id_unique UNIQUE (project_id, client_event_id),
    CONSTRAINT event_exception_type_nonblank CHECK (length(btrim(exception_type)) > 0),
    CONSTRAINT event_message_nonblank CHECK (length(btrim(message)) > 0)
);
CREATE INDEX event_project_time ON intelligence.error_events(organization_id, project_id, occurred_at DESC, id);
CREATE INDEX event_project_environment_time ON intelligence.error_events(project_id, environment_id, occurred_at DESC);
