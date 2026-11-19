-- Explicit migration, never run at application startup.
CREATE TABLE agent_private_tool_call_receipt (
  tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL, owner_jiacn VARCHAR(50) NOT NULL,
  execution_id VARCHAR(100) NOT NULL, run_id VARCHAR(100) NOT NULL, call_id VARCHAR(100) NOT NULL,
  tool_name VARCHAR(100) NOT NULL, input_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  receipt_json JSON NOT NULL, created_at BIGINT NOT NULL,
  PRIMARY KEY (tenant_id,client_id,owner_jiacn,execution_id,run_id,call_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
