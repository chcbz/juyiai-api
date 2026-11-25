-- Explicit migration; only the actual first admitted execution is set, in its own admission transaction.
ALTER TABLE agent_task_native_preparation ADD COLUMN initial_execution_id VARCHAR(100) DEFAULT NULL,
 ADD UNIQUE KEY uk_native_prepared_intent (tenant_id,client_id,owner_jiacn,task_id,intent_sha);
