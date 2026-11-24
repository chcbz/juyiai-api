-- Explicit maintenance, after observation-v1. Old observations without a deadline are unusable.
ALTER TABLE agent_task_native_preparation ADD COLUMN probe_valid_until DATETIME(6) NULL;
