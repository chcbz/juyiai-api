-- Dedicated lifecycle transport grants, not Runtime execution/session authority.
-- Applied only by an explicitly authorized schema maintenance operation, never startup.
CREATE TABLE IF NOT EXISTS agent_hosting_authorization (
  tenant_id VARCHAR(50) NOT NULL,
  client_id VARCHAR(50) NOT NULL,
  authorization_id VARCHAR(100) NOT NULL,
  host_id VARCHAR(100) NOT NULL,
  owner_jiacn VARCHAR(50) NOT NULL,
  canonical_agent_id VARCHAR(100) NOT NULL,
  purpose VARCHAR(16) NOT NULL,
  token_hash BINARY(32) NOT NULL,
  status VARCHAR(16) NOT NULL,
  created_at BIGINT NOT NULL,
  revoked_at BIGINT DEFAULT NULL,
  PRIMARY KEY (tenant_id, client_id, authorization_id),
  UNIQUE KEY uk_hosting_authorization_token (token_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE IF NOT EXISTS agent_hosting_exchange (
  id BIGINT NOT NULL AUTO_INCREMENT,
  tenant_id VARCHAR(50) NOT NULL,
  client_id VARCHAR(50) NOT NULL,
  authorization_id VARCHAR(100) NOT NULL,
  message_id VARCHAR(100) NOT NULL,
  command_json MEDIUMTEXT NOT NULL,
  result_message_id VARCHAR(100) DEFAULT NULL,
  result_json MEDIUMTEXT DEFAULT NULL,
  created_at BIGINT NOT NULL,
  completed_at BIGINT DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_hosting_command (tenant_id, client_id, authorization_id, message_id),
  UNIQUE KEY uk_hosting_result (tenant_id, client_id, authorization_id, result_message_id),
  KEY ix_hosting_pending (tenant_id, client_id, authorization_id, completed_at, id),
  CONSTRAINT fk_hosting_exchange_authorization FOREIGN KEY (tenant_id, client_id, authorization_id)
    REFERENCES agent_hosting_authorization (tenant_id, client_id, authorization_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
