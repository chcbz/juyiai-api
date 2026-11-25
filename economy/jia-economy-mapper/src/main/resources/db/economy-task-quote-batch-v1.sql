-- Explicit schema maintenance; no legacy quote change, migration of an old batch or backfill.
CREATE TABLE IF NOT EXISTS economy_task_quote_batch (
  tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL, owner_jiacn VARCHAR(100) NOT NULL,
  task_id VARCHAR(100) NOT NULL, payer_user_id VARCHAR(100) NOT NULL, key_sha256 CHAR(64) NOT NULL,
  input_sha256 CHAR(64) NOT NULL, response_json LONGTEXT NOT NULL,
  PRIMARY KEY (tenant_id,client_id,owner_jiacn,task_id,payer_user_id,key_sha256)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
