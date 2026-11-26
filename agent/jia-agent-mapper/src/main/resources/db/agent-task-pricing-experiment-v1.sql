-- Maintenance only. Normal task creation does not receive a field, infer an operation or create a row.
ALTER TABLE agent_task_meta ADD COLUMN pricing_experiment_sha CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL;
