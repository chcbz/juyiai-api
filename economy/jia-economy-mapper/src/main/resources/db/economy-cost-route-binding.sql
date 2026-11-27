-- Explicit maintenance migration only. No runtime/client/owner-derived seed rows.
CREATE TABLE IF NOT EXISTS economy_cost_route_revision (
 tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL,
 reference_sha CHAR(64) NOT NULL, revision BIGINT NOT NULL,
 reference_json JSON NOT NULL, route_json JSON NULL, binding_sha CHAR(64) NOT NULL,
 PRIMARY KEY(tenant_id,client_id,reference_sha,revision),
 CONSTRAINT ck_cost_route_revision_positive CHECK(revision>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
-- A SQL NULL route is an explicit immutable withdrawal; never interpreted as free.
CREATE TABLE IF NOT EXISTS economy_cost_route_head (
 tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL,
 reference_sha CHAR(64) NOT NULL, revision BIGINT NOT NULL,
 PRIMARY KEY(tenant_id,client_id,reference_sha),
 CONSTRAINT fk_cost_route_head_revision FOREIGN KEY(tenant_id,client_id,reference_sha,revision)
 REFERENCES economy_cost_route_revision(tenant_id,client_id,reference_sha,revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
