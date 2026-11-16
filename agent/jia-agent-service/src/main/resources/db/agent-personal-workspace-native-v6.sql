-- Explicit coordinated native6 migration. Never run from an application initializer.
-- Before applying: drain accepted protocol5 executions, projections and usage; preserve
-- database/program backups and installation locks. Do not rewrite/replay historical rows.
-- MySQL DDL commits independently: record each statement and inspect information_schema
-- after interruption, rather than replaying the entire migration blindly.
ALTER TABLE agent_personal_workspace_execution
  MODIFY COLUMN task_id VARCHAR(100) NULL,
  ADD COLUMN business_scope VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL;
