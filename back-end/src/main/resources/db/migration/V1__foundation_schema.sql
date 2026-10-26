-- Business tables are introduced by reviewed migrations in their delivery phases.
CREATE SCHEMA IF NOT EXISTS intelligence;
COMMENT ON SCHEMA intelligence IS 'Error Intelligence application data; tenant isolation is mandatory.';
