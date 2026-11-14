-- Fresh installation only: any existing table is a catalog conflict, not an implicit migration.
-- Host identity is registered explicitly; no startup DDL or per-Agent secrets.
CREATE TABLE agent_hosting_host (
  tenant_id VARCHAR(50) NOT NULL,
  client_id VARCHAR(50) NOT NULL,
  host_id VARCHAR(100) NOT NULL,
  deployment_mode VARCHAR(16) NOT NULL,
  owner_jiacn VARCHAR(50) DEFAULT NULL,
  token_hash BINARY(32) NOT NULL,
  status VARCHAR(16) NOT NULL,
  idempotency_key VARCHAR(36) NOT NULL,
  request_hash BINARY(32) NOT NULL,
  created_at BIGINT NOT NULL,
  revoked_at BIGINT DEFAULT NULL,
  PRIMARY KEY (tenant_id,client_id,host_id),
  UNIQUE KEY uk_hosting_host_token (token_hash),
  UNIQUE KEY uk_hosting_host_request (tenant_id,client_id,owner_jiacn,idempotency_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

-- Dedicated lifecycle transport grants, not Runtime execution/session authority.
-- Applied only by an explicitly authorized schema maintenance operation, never startup.
CREATE TABLE agent_hosting_authorization (
  tenant_id VARCHAR(50) NOT NULL,
  client_id VARCHAR(50) NOT NULL,
  authorization_id VARCHAR(100) NOT NULL,
  host_id VARCHAR(100) NOT NULL,
  owner_jiacn VARCHAR(50) NOT NULL,
  canonical_agent_id VARCHAR(100) NOT NULL,
  purpose VARCHAR(16) NOT NULL,
  status VARCHAR(16) NOT NULL,
  created_at BIGINT NOT NULL,
  revoked_at BIGINT DEFAULT NULL,
  PRIMARY KEY (tenant_id, client_id, authorization_id),
  KEY ix_hosting_authorization_host (tenant_id,client_id,host_id,status,authorization_id),
  CONSTRAINT fk_hosting_authorization_host FOREIGN KEY (tenant_id,client_id,host_id)
    REFERENCES agent_hosting_host (tenant_id,client_id,host_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE agent_hosting_exchange (
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

-- Setup allocates an invitation target, NOT a paid binding, installation or execution session.
CREATE TABLE agent_hosting_setup (
  tenant_id VARCHAR(50) NOT NULL,
  client_id VARCHAR(50) NOT NULL,
  setup_id VARCHAR(100) NOT NULL,
  actor_id VARCHAR(100) NOT NULL,
  owner_jiacn VARCHAR(50) NOT NULL,
  persona_code VARCHAR(100) NOT NULL,
  canonical_agent_id VARCHAR(100) NOT NULL,
  host_id VARCHAR(100) NOT NULL,
  deployment_mode VARCHAR(16) NOT NULL,
  idempotency_key VARCHAR(36) NOT NULL,
  request_hash BINARY(32) NOT NULL,
  created_at BIGINT NOT NULL,
  PRIMARY KEY (tenant_id, client_id, setup_id),
  UNIQUE KEY uk_hosting_setup_request (tenant_id, client_id, actor_id, idempotency_key),
  CONSTRAINT fk_hosting_setup_grant FOREIGN KEY (tenant_id, client_id, setup_id)
    REFERENCES agent_hosting_authorization (tenant_id, client_id, authorization_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE agent_hosting_setup_quote (
  tenant_id VARCHAR(50) NOT NULL,
  client_id VARCHAR(50) NOT NULL,
  quote_id VARCHAR(100) NOT NULL,
  setup_id VARCHAR(100) NOT NULL,
  PRIMARY KEY (tenant_id, client_id, quote_id),
  CONSTRAINT fk_hosting_setup_quote FOREIGN KEY (tenant_id, client_id, setup_id)
    REFERENCES agent_hosting_setup (tenant_id, client_id, setup_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

-- Exact paid relationship; one setup cannot authorize a second lease or user.
CREATE TABLE agent_hosting_setup_activation (
  tenant_id VARCHAR(50) NOT NULL,
  client_id VARCHAR(50) NOT NULL,
  setup_id VARCHAR(100) DEFAULT NULL,
  authorization_id VARCHAR(100) NOT NULL,
  binding_id VARCHAR(100) NOT NULL,
  lease_id VARCHAR(100) NOT NULL,
  PRIMARY KEY (tenant_id,client_id,authorization_id),
  UNIQUE KEY uk_hosting_activation_setup (tenant_id,client_id,setup_id),
  UNIQUE KEY uk_hosting_activation_lease (tenant_id,client_id,lease_id),
  CONSTRAINT fk_hosting_activation_setup FOREIGN KEY (tenant_id,client_id,setup_id)
    REFERENCES agent_hosting_setup (tenant_id,client_id,setup_id),
  CONSTRAINT fk_hosting_activation_grant FOREIGN KEY (tenant_id,client_id,authorization_id)
    REFERENCES agent_hosting_authorization (tenant_id,client_id,authorization_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
