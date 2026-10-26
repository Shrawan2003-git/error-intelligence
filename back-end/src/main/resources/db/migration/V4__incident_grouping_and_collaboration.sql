CREATE TABLE intelligence.incident_groups (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL,
    fingerprint_version smallint NOT NULL,
    fingerprint char(64) NOT NULL,
    title varchar(500) NOT NULL,
    first_seen timestamptz NOT NULL,
    last_seen timestamptz NOT NULL,
    occurrence_count bigint NOT NULL DEFAULT 0,
    status varchar(20) NOT NULL DEFAULT 'OPEN' CHECK(status IN ('OPEN','ACKNOWLEDGED','RESOLVED')),
    assignee_id uuid REFERENCES intelligence.users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT incident_group_org_project_fk FOREIGN KEY (organization_id, project_id)
        REFERENCES intelligence.projects(organization_id, id),
    CONSTRAINT incident_group_fingerprint_unique UNIQUE(project_id, fingerprint_version, fingerprint),
    CONSTRAINT incident_group_count_nonnegative CHECK(occurrence_count >= 0),
    CONSTRAINT incident_group_time_order CHECK(first_seen <= last_seen)
);
CREATE INDEX incident_project_recent ON intelligence.incident_groups(organization_id, project_id, last_seen DESC, id);
CREATE INDEX incident_project_status_recent ON intelligence.incident_groups(organization_id, project_id, status, last_seen DESC, id);

CREATE TABLE intelligence.error_fingerprints (
    event_id uuid NOT NULL REFERENCES intelligence.error_events(id),
    fingerprint_version smallint NOT NULL,
    fingerprint char(64) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(event_id, fingerprint_version)
);

CREATE TABLE intelligence.incident_occurrences (
    incident_group_id uuid NOT NULL REFERENCES intelligence.incident_groups(id),
    event_id uuid NOT NULL REFERENCES intelligence.error_events(id),
    occurred_at timestamptz NOT NULL,
    PRIMARY KEY(incident_group_id, event_id),
    UNIQUE(event_id)
);
CREATE INDEX incident_occurrence_recent ON intelligence.incident_occurrences(incident_group_id, occurred_at DESC, event_id);

CREATE TABLE intelligence.incident_comments (
    id uuid PRIMARY KEY,
    incident_group_id uuid NOT NULL REFERENCES intelligence.incident_groups(id),
    author_id uuid NOT NULL REFERENCES intelligence.users(id),
    body varchar(4000) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT incident_comment_nonblank CHECK(length(btrim(body)) > 0)
);
CREATE INDEX incident_comment_order ON intelligence.incident_comments(incident_group_id, created_at, id);

CREATE TABLE intelligence.incident_resolutions (
    id uuid PRIMARY KEY,
    incident_group_id uuid NOT NULL REFERENCES intelligence.incident_groups(id),
    summary varchar(500) NOT NULL,
    root_cause varchar(4000) NOT NULL,
    verified_by uuid NOT NULL REFERENCES intelligence.users(id),
    verified_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT incident_resolution_summary_nonblank CHECK(length(btrim(summary)) > 0),
    CONSTRAINT incident_resolution_cause_nonblank CHECK(length(btrim(root_cause)) > 0)
);
CREATE INDEX incident_resolution_order ON intelligence.incident_resolutions(incident_group_id, verified_at DESC, id);
