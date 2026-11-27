-- Additive capability semantics and immutable approved-version bindings. No seed or backfill.
CREATE TABLE IF NOT EXISTS agent_capability_quote_spec (
    tenant_id VARCHAR(50) NOT NULL,
    client_id VARCHAR(50) NOT NULL,
    capability_id VARCHAR(128) NOT NULL,
    version VARCHAR(64) NOT NULL,
    spec_sha CHAR(64) NOT NULL,
    spec_json JSON NOT NULL,
    status VARCHAR(16) NOT NULL,
    published_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (tenant_id,client_id,capability_id,version),
    UNIQUE KEY uk_capability_quote_semantic (tenant_id,client_id,capability_id,version,spec_sha),
    CONSTRAINT chk_capability_quote_status CHECK (status IN ('ACTIVE','WITHDRAWN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE IF NOT EXISTS agent_skill_quote_spec_binding (
    tenant_id VARCHAR(50) NOT NULL,
    client_id VARCHAR(50) NOT NULL,
    product_version_id VARCHAR(100) NOT NULL,
    capability_id VARCHAR(128) NOT NULL,
    capability_version VARCHAR(64) NOT NULL,
    semantic_sha CHAR(64) NOT NULL,
    PRIMARY KEY (tenant_id,client_id,product_version_id,capability_id,capability_version),
    CONSTRAINT fk_skill_quote_product_version FOREIGN KEY (tenant_id,client_id,product_version_id)
        REFERENCES agent_skill_product_version (tenant_id,client_id,product_version_id),
    CONSTRAINT fk_skill_quote_semantic FOREIGN KEY (tenant_id,client_id,capability_id,capability_version,semantic_sha)
        REFERENCES agent_capability_quote_spec (tenant_id,client_id,capability_id,version,spec_sha)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
