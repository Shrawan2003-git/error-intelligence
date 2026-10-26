CREATE TABLE intelligence.embedding_daily_usage (
    organization_id uuid NOT NULL REFERENCES intelligence.organizations(id),
    usage_day date NOT NULL,
    calls integer NOT NULL CHECK(calls > 0),
    input_characters bigint NOT NULL CHECK(input_characters >= 0),
    PRIMARY KEY(organization_id,usage_day)
);
