-- Explicit maintenance migration, after metered-capture-v2. No backfill.
ALTER TABLE agent_personal_workspace_execution ADD COLUMN capture_profile_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL;
