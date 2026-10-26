-- Test-only future migration fixture. Never packaged into the application JAR.
ALTER TABLE intelligence.upgrade_probe ADD COLUMN marker text NOT NULL DEFAULT 'upgraded';
