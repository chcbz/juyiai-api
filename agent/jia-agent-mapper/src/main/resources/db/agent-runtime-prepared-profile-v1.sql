-- Explicit maintenance after runtime-session-fence-v1. A missing epoch is not an approved configuration.
ALTER TABLE agent_runtime ADD COLUMN prepared_session_generation BIGINT NULL,
  ADD COLUMN prepared_profile_sequence BIGINT NULL, ADD COLUMN prepared_profile_sha CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL;
