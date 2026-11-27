-- Explicit migration for existing pre-scope tables; no data backfill or price rewrite.
ALTER TABLE agent_task_requirement_snapshot
 ADD COLUMN scope_schema VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD COLUMN scope_json JSON NULL,
 ADD COLUMN scope_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD COLUMN scope_input_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD COLUMN scope_confirmed_at BIGINT NULL;

ALTER TABLE agent_task_current_requirement
 ADD COLUMN scope_schema VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD COLUMN scope_json JSON NULL,
 ADD COLUMN scope_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD COLUMN scope_input_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD COLUMN scope_confirmed_at BIGINT NULL;
