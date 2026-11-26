-- Empty projections only: no initial publication, grants, or enabled groups.
CREATE TABLE economy_budget_domain_epoch (
  tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL, book_epoch BIGINT NOT NULL,
  PRIMARY KEY (tenant_id,client_id), CHECK (book_epoch > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE economy_budget_group_head (
  tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL, group_sha CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  generation BIGINT NOT NULL, active_revision BIGINT NULL, status VARCHAR(12) NOT NULL,
  tariff_sha CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL, book_epoch BIGINT NOT NULL, attempt INT NOT NULL,
  next_at TIMESTAMP(3) NULL, claim_id CHAR(36) NULL, lease_until TIMESTAMP(3) NULL,
  policy_sha CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, updated_at TIMESTAMP(3) NOT NULL,
  PRIMARY KEY (tenant_id,client_id,group_sha), KEY due_groups(status,next_at,updated_at), KEY expired_leases(status,lease_until),
  CHECK (generation > 0 AND book_epoch > 0 AND attempt >= 0),
  CHECK (status IN ('DIRTY','BUILDING','RETRY','ACTIVE','WAIT_INPUT','RETIRED')),
  CHECK ((status='ACTIVE' AND active_revision IS NOT NULL AND active_revision=generation AND tariff_sha IS NOT NULL)
      OR (status<>'ACTIVE' AND active_revision IS NULL AND tariff_sha IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE economy_budget_group_revision (
  tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL, group_sha CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  revision BIGINT NOT NULL, book_epoch BIGINT NOT NULL, tariff_sha CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  policy_sha CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, sample_count INT NOT NULL, sum_cost_yuan DECIMAL(65,30) NOT NULL,
  evidence_sha CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, built_at TIMESTAMP(3) NOT NULL,
  PRIMARY KEY (tenant_id,client_id,group_sha,revision), CHECK (sample_count>0 AND sum_cost_yuan>=0 AND revision>0 AND book_epoch>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE economy_budget_group_member (
  tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL, group_sha CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  sample_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, source_owner VARCHAR(100) NOT NULL, source_task_id VARCHAR(100) NOT NULL,
  evidence_revision BIGINT NOT NULL, manifest_sha CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  review_sha CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, publish_approval_sha CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  enabled BOOLEAN NOT NULL, updated_at TIMESTAMP(3) NOT NULL, PRIMARY KEY (tenant_id,client_id,group_sha,sample_id),
  UNIQUE KEY one_task(tenant_id,client_id,group_sha,source_owner,source_task_id), KEY sample_groups(tenant_id,client_id,sample_id,group_sha)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE economy_budget_group_build_evidence (
  tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL, group_sha CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  revision BIGINT NOT NULL, roster_json LONGTEXT NOT NULL, evidence_sha CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  PRIMARY KEY (tenant_id,client_id,group_sha,revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
