-- Explicit operator migration. No backfill: old grants/usage cannot prove membership.
CREATE TABLE agent_task_execution_member (
  membership_revision BIGINT NOT NULL AUTO_INCREMENT,
  tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL, owner_jiacn VARCHAR(50) NOT NULL,
  task_id VARCHAR(100) NOT NULL, execution_id VARCHAR(100) NOT NULL, run_id VARCHAR(100) NOT NULL,
  admission_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  capture_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  PRIMARY KEY (membership_revision),
  UNIQUE KEY uk_task_exec_member (tenant_id,client_id,owner_jiacn,task_id,execution_id,run_id),
  KEY idx_task_member_page (tenant_id,client_id,owner_jiacn,task_id,membership_revision)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
