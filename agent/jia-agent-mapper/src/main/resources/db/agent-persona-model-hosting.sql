-- Explicit additive maintenance migration; never executed at application startup.
-- Prerequisite: economy_hosting_rent_plan and its existing immutable version protections.
CREATE TABLE IF NOT EXISTS economy_hosting_model_plan (
    tenant_id VARCHAR(50) NOT NULL,
    client_id VARCHAR(50) NOT NULL,
    model_id VARCHAR(100) NOT NULL,
    plan_id VARCHAR(100) NOT NULL,
    plan_version BIGINT NOT NULL,
    PRIMARY KEY (tenant_id,client_id,model_id),
    CONSTRAINT fk_hosting_model_plan FOREIGN KEY (tenant_id,client_id,plan_id,plan_version)
        REFERENCES economy_hosting_rent_plan(tenant_id,client_id,plan_id,plan_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE IF NOT EXISTS agent_persona_model (
    tenant_id VARCHAR(50) NOT NULL,
    client_id VARCHAR(50) NOT NULL,
    persona_code VARCHAR(100) NOT NULL,
    model_id VARCHAR(100) NOT NULL,
    PRIMARY KEY (tenant_id,client_id,persona_code),
    CONSTRAINT fk_persona_model_price FOREIGN KEY (tenant_id,client_id,model_id)
        REFERENCES economy_hosting_model_plan(tenant_id,client_id,model_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
