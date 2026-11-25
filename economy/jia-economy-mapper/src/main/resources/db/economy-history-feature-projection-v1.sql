-- Optional derived history read representation. The sample owns its lifetime; never a source of eligibility.
CREATE TABLE economy_history_feature_projection (
    sample_id CHAR(64) PRIMARY KEY, sample_revision BIGINT NOT NULL, quote_id VARCHAR(100) NOT NULL,
    envelope_sha CHAR(64) NOT NULL, resolved_sha CHAR(64) NOT NULL,
    recommendation_id VARCHAR(100), preparation_sha CHAR(64), feature_meta LONGTEXT NOT NULL, vector_bytes BLOB NOT NULL,
    CONSTRAINT fk_history_feature_sample FOREIGN KEY (sample_id) REFERENCES economy_task_cost_sample(sample_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
