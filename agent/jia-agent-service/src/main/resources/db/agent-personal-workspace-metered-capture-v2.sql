-- Explicit maintenance migration before deploying the new execution entity. No history backfill.
ALTER TABLE agent_personal_workspace_execution
  ADD COLUMN usage_mode VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
  ADD COLUMN capture_protocol VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL;
