-- Explicit companion to agent-personal-workspace-native-v6.sql; never run at startup.
-- New PRIVATE steps have all four task/grant fields NULL; TASK keeps real authority.
-- Historical rows and client request digests remain untouched. This is additive schema
-- plus nullability only, not an execution/state conversion or automatic replay.
ALTER TABLE chat_request
  ADD COLUMN client_intent_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL;
ALTER TABLE chat_interaction_step
  MODIFY COLUMN task_id VARCHAR(100) NULL,
  MODIFY COLUMN assignment_revision BIGINT NULL,
  MODIFY COLUMN grant_id VARCHAR(100) NULL,
  MODIFY COLUMN grant_version BIGINT NULL;
