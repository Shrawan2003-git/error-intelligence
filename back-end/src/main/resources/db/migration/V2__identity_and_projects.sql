CREATE TABLE intelligence.users (
 id uuid PRIMARY KEY, email varchar(254) NOT NULL UNIQUE, display_name varchar(100) NOT NULL,
 password_hash varchar(100) NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
 CONSTRAINT normalized_email CHECK(email = lower(email))
);
CREATE TABLE intelligence.organizations (
 id uuid PRIMARY KEY, name varchar(120) NOT NULL, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE intelligence.memberships (
 organization_id uuid NOT NULL REFERENCES intelligence.organizations(id),
 user_id uuid NOT NULL REFERENCES intelligence.users(id),
 role varchar(20) NOT NULL CHECK(role IN ('OWNER','ADMIN','DEVELOPER','VIEWER')),
 created_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(organization_id,user_id)
);
CREATE INDEX membership_user ON intelligence.memberships(user_id,organization_id);
CREATE TABLE intelligence.sessions (
 id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES intelligence.users(id),
 organization_id uuid NOT NULL REFERENCES intelligence.organizations(id),
 expires_at timestamptz NOT NULL, revoked_at timestamptz, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX session_user_expiry ON intelligence.sessions(user_id,expires_at);
CREATE TABLE intelligence.refresh_tokens (
 id uuid PRIMARY KEY, session_id uuid NOT NULL REFERENCES intelligence.sessions(id),
 digest char(64) NOT NULL UNIQUE, expires_at timestamptz NOT NULL, consumed_at timestamptz
);
CREATE INDEX refresh_session ON intelligence.refresh_tokens(session_id);
CREATE TABLE intelligence.projects (
 id uuid PRIMARY KEY, organization_id uuid NOT NULL REFERENCES intelligence.organizations(id),
 name varchar(120) NOT NULL, slug varchar(80) NOT NULL, created_at timestamptz NOT NULL DEFAULT now(), archived_at timestamptz,
 UNIQUE(organization_id,slug), UNIQUE(organization_id,id)
);
CREATE INDEX project_organization ON intelligence.projects(organization_id,created_at DESC,id);
CREATE TABLE intelligence.environments (
 id uuid PRIMARY KEY, project_id uuid NOT NULL REFERENCES intelligence.projects(id),
 name varchar(80) NOT NULL, created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(project_id,name), UNIQUE(project_id,id)
);
CREATE TABLE intelligence.releases (
 id uuid PRIMARY KEY, project_id uuid NOT NULL REFERENCES intelligence.projects(id),
 version varchar(120) NOT NULL, created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(project_id,version), UNIQUE(project_id,id)
);
CREATE TABLE intelligence.ingestion_keys (
 id uuid PRIMARY KEY, project_id uuid NOT NULL REFERENCES intelligence.projects(id),
 label varchar(100) NOT NULL, digest char(64) NOT NULL UNIQUE, prefix varchar(16) NOT NULL,
 scope varchar(20) NOT NULL CHECK(scope = 'INGEST'), created_at timestamptz NOT NULL DEFAULT now(), revoked_at timestamptz
);
CREATE INDEX key_project ON intelligence.ingestion_keys(project_id,created_at DESC);
CREATE TABLE intelligence.audit_events (
 id uuid PRIMARY KEY, organization_id uuid NOT NULL REFERENCES intelligence.organizations(id),
 actor_id uuid REFERENCES intelligence.users(id), action varchar(80) NOT NULL, target_id uuid,
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX audit_organization_time ON intelligence.audit_events(organization_id,created_at DESC,id);
