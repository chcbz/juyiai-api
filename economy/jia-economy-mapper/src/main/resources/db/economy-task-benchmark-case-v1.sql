-- Maintenance only. No cases, spending authority or grants are installed by this migration.
-- Only the separately authorized operator may insert an immutable approved manifest or retire it.
CREATE TABLE IF NOT EXISTS economy_task_benchmark_case (
  tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL, owner_jiacn VARCHAR(100) NOT NULL,
  task_id VARCHAR(100) NOT NULL, benchmark_case_id VARCHAR(100) NOT NULL,
  manifest_json LONGTEXT NOT NULL, manifest_sha CHAR(64) NOT NULL,
  recorded_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), retired BOOLEAN NOT NULL DEFAULT FALSE,
  PRIMARY KEY (tenant_id,client_id,benchmark_case_id), UNIQUE KEY uk_benchmark_task(tenant_id,client_id,owner_jiacn,task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
