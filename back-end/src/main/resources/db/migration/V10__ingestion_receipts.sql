-- Receipt identity survives raw-event cleanup. Do not delete receipts without declaring
-- a deduplication horizon; retained receipt rows contain no event payload.
CREATE TABLE intelligence.ingestion_receipts (
    project_id uuid NOT NULL REFERENCES intelligence.projects(id),
    client_event_id uuid NOT NULL,
    event_id uuid NOT NULL,
    accepted_at timestamptz NOT NULL,
    PRIMARY KEY(project_id,client_event_id)
);
INSERT INTO intelligence.ingestion_receipts(project_id,client_event_id,event_id,accepted_at)
    SELECT project_id,client_event_id,id,received_at FROM intelligence.error_events;
