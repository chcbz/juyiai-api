-- Run through the existing explicit schema-maintenance procedure before COST_PROFILES_V1 writes.
-- Keep the original private registration row/fence; 1MiB bounded reports exceed TEXT's 64KiB.
-- Widen only: no fabricated reports, sequence changes or historical quote rewrites.
ALTER TABLE agent_runtime MODIFY COLUMN pricing_profiles_json MEDIUMTEXT NULL;
