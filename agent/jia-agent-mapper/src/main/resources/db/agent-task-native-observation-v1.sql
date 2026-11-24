-- Explicit maintenance, after agent-task-native-preparation-v1. No backfill or activation.
ALTER TABLE agent_task_native_preparation
  ADD COLUMN probe_ticket CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  ADD COLUMN measurement_sha CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  ADD COLUMN measurement_json LONGTEXT NULL;
