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
    evidence_json LONGTEXT NOT NULL,
    association_state VARCHAR(24) NOT NULL,
    PRIMARY KEY (capture_pk,record_id,revision_key),
    KEY ix_capture_association (association_state,capture_pk),
    CONSTRAINT fk_usage_capture_record FOREIGN KEY (capture_pk) REFERENCES economy_usage_capture(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE IF NOT EXISTS economy_usage_capture_receipt (
    capture_pk BIGINT NOT NULL,
    receipt_id VARCHAR(100) NOT NULL,
    record_id CHAR(64) NOT NULL,
    revision_key CHAR(64) NOT NULL,
    request_id VARCHAR(100) NOT NULL,
    request_sha256 CHAR(64) NOT NULL,
    expected_revision LONGTEXT DEFAULT NULL,
    processing_state VARCHAR(24) NOT NULL,
    ack_json LONGTEXT DEFAULT NULL,
    PRIMARY KEY (capture_pk,receipt_id),
    KEY ix_capture_pending_receipt (processing_state,capture_pk,receipt_id),
    CONSTRAINT fk_usage_capture_receipt_record FOREIGN KEY (capture_pk,record_id,revision_key)
        REFERENCES economy_usage_capture_record(capture_pk,record_id,revision_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
