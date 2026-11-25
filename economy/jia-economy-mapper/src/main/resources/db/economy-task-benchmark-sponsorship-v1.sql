-- Maintenance-only schema. No approved case or spending authorization is installed.
-- An authorized operator imports immutable authority JSON/SHA before dispatch; the application
-- only transitions its state under the existing owner-scoped task-root transaction.
-- Withdrawal fences first activation; it never deletes past spending or prevents settlement.
CREATE TABLE IF NOT EXISTS economy_task_benchmark_sponsorship (
  tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL,
  owner_jiacn VARCHAR(100) NOT NULL, task_id VARCHAR(100) NOT NULL,
  authority_json LONGTEXT NOT NULL, authority_sha CHAR(64) NOT NULL,
  state VARCHAR(16) NOT NULL DEFAULT 'APPROVED', withdrawn BOOLEAN NOT NULL DEFAULT FALSE,
  recorded_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (tenant_id,client_id,owner_jiacn,task_id), UNIQUE KEY uk_benchmark_sponsorship_sha(authority_sha)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
