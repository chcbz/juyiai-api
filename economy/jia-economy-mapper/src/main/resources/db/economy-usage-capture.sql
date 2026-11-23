-- Explicit maintenance entry only. Runtime evidence is not a wallet posting or a quote.
CREATE TABLE IF NOT EXISTS economy_usage_capture (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id VARCHAR(50) NOT NULL,
    client_id VARCHAR(50) NOT NULL,
    execution_id VARCHAR(100) NOT NULL,
    run_id VARCHAR(100) NOT NULL,
    capture_id CHAR(64) NOT NULL,
    owner_jiacn VARCHAR(100) NOT NULL,
    agent_id VARCHAR(100) NOT NULL,
    task_id VARCHAR(100) DEFAULT NULL,
    input_revision BIGINT NOT NULL DEFAULT 0,
    projection_revision BIGINT DEFAULT NULL,
    coverage_state VARCHAR(24) NOT NULL DEFAULT 'WAITING_EVIDENCE',
    conflict_reason VARCHAR(100) DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_capture_binding (tenant_id,client_id,execution_id,run_id,capture_id),
    KEY ix_capture_task (tenant_id,client_id,owner_jiacn,task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE IF NOT EXISTS economy_usage_capture_record (
    capture_pk BIGINT NOT NULL,
    record_id CHAR(64) NOT NULL,
    -- Index SHA-256 of the canonical decimal string, retain the exact decimal for comparison.
    revision_key CHAR(64) NOT NULL,
    source_revision LONGTEXT NOT NULL,
    kind VARCHAR(24) NOT NULL,
    evidence_sha256 CHAR(64) NOT NULL,
    previous_evidence_sha256 CHAR(64) DEFAULT NULL,
    binding_sha256 CHAR(64) NOT NULL,
    evidence_json LONGTEXT NOT NULL,
    association_state VARCHAR(24) NOT NULL,
    PRIMARY KEY (capture_pk,record_id,revision_key),
    KEY ix_capture_association (association_state,capture_pk),
    CONSTRAINT fk_usage_capture_record FOREIGN KEY (capture_pk) REFERENCES economy_usage_capture(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

-- A source identity has one original capture per ledger scope. Not a second cost ledger.
CREATE TABLE IF NOT EXISTS economy_usage_response_origin (
    tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL, source_attempt_id CHAR(64) NOT NULL,
    capture_pk BIGINT NOT NULL, record_id CHAR(64) NOT NULL,
    PRIMARY KEY (tenant_id,client_id,source_attempt_id),
    CONSTRAINT fk_usage_response_origin FOREIGN KEY (capture_pk) REFERENCES economy_usage_capture(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE IF NOT EXISTS economy_usage_capture_receipt (
    capture_pk BIGINT NOT NULL,
    receipt_id VARCHAR(100) NOT NULL,
    record_id CHAR(64) NOT NULL,
    revision_key CHAR(64) NOT NULL,
    request_id LONGTEXT NOT NULL,
    request_sha256 CHAR(64) NOT NULL,
    expected_revision LONGTEXT DEFAULT NULL,
    processing_state VARCHAR(24) NOT NULL,
    ack_json LONGTEXT DEFAULT NULL,
    PRIMARY KEY (capture_pk,receipt_id),
    KEY ix_capture_pending_receipt (processing_state,capture_pk,receipt_id),
    CONSTRAINT fk_usage_capture_receipt_record FOREIGN KEY (capture_pk,record_id,revision_key)
        REFERENCES economy_usage_capture_record(capture_pk,record_id,revision_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

-- Current lifecycle projection; source ingestion never creates a sample or membership.
CREATE TABLE IF NOT EXISTS economy_task_cost_sample (
    sample_id CHAR(64) NOT NULL, tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL,
    owner_jiacn VARCHAR(100) NOT NULL, task_id VARCHAR(100) NOT NULL, quote_id VARCHAR(100) DEFAULT NULL,
    benchmark_case_id VARCHAR(100) DEFAULT NULL, sample_definition_version VARCHAR(64) NOT NULL,
    initial_requirement_sha256 CHAR(64) NOT NULL, initial_input_snapshot_sha256 CHAR(64) NOT NULL,
    initial_feature_sha256 CHAR(64) NOT NULL, lifecycle_state VARCHAR(24) NOT NULL,
    evidence_revision BIGINT NOT NULL, projection_revision BIGINT DEFAULT NULL, sample_state VARCHAR(24) NOT NULL,
    PRIMARY KEY (sample_id),
    UNIQUE KEY uk_sample_quote (tenant_id,client_id,owner_jiacn,task_id,quote_id,sample_definition_version),
    UNIQUE KEY uk_sample_benchmark (tenant_id,client_id,owner_jiacn,task_id,benchmark_case_id,sample_definition_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE IF NOT EXISTS economy_task_cost_sample_member (
    sample_id CHAR(64) NOT NULL, execution_id VARCHAR(100) NOT NULL, run_id VARCHAR(100) NOT NULL, capture_id CHAR(64) NOT NULL,
    PRIMARY KEY (sample_id,execution_id,run_id,capture_id),
    KEY ix_sample_capture (capture_id,sample_id),
    CONSTRAINT fk_sample_member FOREIGN KEY (sample_id) REFERENCES economy_task_cost_sample(sample_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
