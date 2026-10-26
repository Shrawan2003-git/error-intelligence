-- Synthetic V7 business graph used by both native and container upgrade contracts.
INSERT INTO intelligence.organizations(id,name) VALUES ('00000000-0000-0000-0000-000000000001','Upgrade tenant');
INSERT INTO intelligence.projects(id,organization_id,name,slug) VALUES ('00000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000001','Upgrade project','upgrade-project');
INSERT INTO intelligence.environments(id,project_id,name) VALUES ('00000000-0000-0000-0000-000000000003','00000000-0000-0000-0000-000000000002','test');
INSERT INTO intelligence.error_events(id,organization_id,project_id,environment_id,client_event_id,occurred_at,exception_type,message)
VALUES ('00000000-0000-0000-0000-000000000004','00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000003','00000000-0000-0000-0000-000000000005',now(),'SyntheticFailure','Synthetic upgrade evidence');
INSERT INTO intelligence.incident_groups(id,organization_id,project_id,fingerprint_version,fingerprint,title,first_seen,last_seen,occurrence_count)
VALUES ('00000000-0000-0000-0000-000000000006','00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000002',1,repeat('a',64),'Synthetic incident',now(),now(),1);
INSERT INTO intelligence.incident_occurrences(incident_group_id,event_id,occurred_at)
VALUES ('00000000-0000-0000-0000-000000000006','00000000-0000-0000-0000-000000000004',now());
INSERT INTO intelligence.embedding_jobs(id,organization_id,project_id,incident_group_id,source_type,source_id,source_text,status)
VALUES ('00000000-0000-0000-0000-000000000007','00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000006','INCIDENT','00000000-0000-0000-0000-000000000006','Synthetic upgrade evidence','QUEUED');
