-- Explicit migration; not executed by application startup. No deployment-specific seeds.
CREATE TABLE IF NOT EXISTS economy_model_price (
 tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL,
 channel VARCHAR(100) NOT NULL, model VARCHAR(200) NOT NULL,
 tier VARCHAR(100) NOT NULL, price_version VARCHAR(100) NOT NULL,
 price_data_json LONGTEXT NOT NULL, created_at BIGINT NOT NULL,
 PRIMARY KEY(tenant_id,client_id,channel,model,tier,price_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE IF NOT EXISTS economy_model_price_current (
 tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL,
 channel VARCHAR(100) NOT NULL, model VARCHAR(200) NOT NULL,
 tier VARCHAR(100) NOT NULL, price_version VARCHAR(100) NOT NULL,
 updated_at BIGINT NOT NULL,
 PRIMARY KEY(tenant_id,client_id,channel,model,tier),
 CONSTRAINT fk_model_price_current_version
 FOREIGN KEY(tenant_id,client_id,channel,model,tier,price_version)
 REFERENCES economy_model_price(tenant_id,client_id,channel,model,tier,price_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE IF NOT EXISTS economy_task_model_tariff (
 tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL,
 model VARCHAR(200) NOT NULL, channel VARCHAR(100) NOT NULL,
 tier VARCHAR(100) NOT NULL, tools_json LONGTEXT NOT NULL,
 updated_at BIGINT NOT NULL,
 PRIMARY KEY(tenant_id,client_id,model)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE IF NOT EXISTS economy_tool_price (
 tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL,
 channel VARCHAR(100) NOT NULL, tool VARCHAR(100) NOT NULL,
 unit VARCHAR(100) NOT NULL, price_version VARCHAR(100) NOT NULL,
 price_data_json LONGTEXT NOT NULL, created_at BIGINT NOT NULL,
 PRIMARY KEY(tenant_id,client_id,channel,tool,unit,price_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE IF NOT EXISTS economy_tool_price_current (
 tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL,
 channel VARCHAR(100) NOT NULL, tool VARCHAR(100) NOT NULL,
 unit VARCHAR(100) NOT NULL, price_version VARCHAR(100) NOT NULL,
 updated_at BIGINT NOT NULL,
 PRIMARY KEY(tenant_id,client_id,channel,tool,unit),
 CONSTRAINT fk_tool_price_current_version
 FOREIGN KEY(tenant_id,client_id,channel,tool,unit,price_version)
 REFERENCES economy_tool_price(tenant_id,client_id,channel,tool,unit,price_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
