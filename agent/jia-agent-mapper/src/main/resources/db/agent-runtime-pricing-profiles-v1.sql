-- Reuse private runtime identity/binding instead of duplicating the ACL in a pricing table.
-- A moved installation/binding or new session generation cannot reuse the prior projection.
ALTER TABLE agent_runtime
 ADD COLUMN pricing_installation_id VARCHAR(100) NULL,
 ADD COLUMN pricing_binding_id BIGINT NULL,
 ADD COLUMN pricing_generation BIGINT NULL,
 ADD COLUMN pricing_sequence BIGINT NULL,
 ADD COLUMN pricing_config_sha CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD COLUMN pricing_profiles_json TEXT NULL,
 ADD COLUMN pricing_observed_at TIMESTAMP(3) NULL;
