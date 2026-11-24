-- Explicit maintenance migration, no default selections. One immutable initial intent per exact task/candidate/input tuple.
CREATE TABLE agent_task_native_preparation (
 tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL, owner_jiacn VARCHAR(100) NOT NULL, task_id VARCHAR(100) NOT NULL,
 binding_sha CHAR(64) NOT NULL, intent_sha CHAR(64) NOT NULL, intent_json LONGTEXT NOT NULL,
 PRIMARY KEY(tenant_id,client_id,owner_jiacn,task_id,binding_sha)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
