-- Maintenance only. No grants, projections or changes to existing history.
CREATE TABLE economy_task_benchmark_use_grant (
 tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL, owner_jiacn VARCHAR(100) NOT NULL,
 group_sha CHAR(64) NOT NULL, generation BIGINT NOT NULL, receipt_sha CHAR(64) NOT NULL,
 sample_id CHAR(64) NOT NULL, observation_sha CHAR(64) NOT NULL, authority_ref VARCHAR(255) NOT NULL,
 PRIMARY KEY(tenant_id,client_id,owner_jiacn,group_sha,generation,sample_id),
 CHECK(generation>0 AND LENGTH(TRIM(authority_ref))>0),
 FOREIGN KEY(tenant_id,client_id,owner_jiacn,group_sha,receipt_sha) REFERENCES economy_task_estimator_evidence(tenant_id,client_id,owner_jiacn,group_sha,receipt_sha)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE economy_task_benchmark_use_stop (
 tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL, owner_jiacn VARCHAR(100) NOT NULL,
 group_sha CHAR(64) NOT NULL, generation BIGINT NOT NULL, sample_id CHAR(64) NOT NULL,
 PRIMARY KEY(tenant_id,client_id,owner_jiacn,group_sha,generation,sample_id),
 FOREIGN KEY(tenant_id,client_id,owner_jiacn,group_sha,generation,sample_id) REFERENCES economy_task_benchmark_use_grant(tenant_id,client_id,owner_jiacn,group_sha,generation,sample_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE economy_task_benchmark_use_projection (
 tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL, owner_jiacn VARCHAR(100) NOT NULL,
 group_sha CHAR(64) NOT NULL, generation BIGINT NOT NULL, sample_id CHAR(64) NOT NULL,
 evidence_revision BIGINT NOT NULL, review_sha CHAR(64) NOT NULL, terminal_fact VARCHAR(100) NOT NULL, operations LONGTEXT NOT NULL,
 PRIMARY KEY(tenant_id,client_id,owner_jiacn,group_sha,generation,sample_id),
 FOREIGN KEY(tenant_id,client_id,owner_jiacn,group_sha,generation,sample_id) REFERENCES economy_task_benchmark_use_grant(tenant_id,client_id,owner_jiacn,group_sha,generation,sample_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
