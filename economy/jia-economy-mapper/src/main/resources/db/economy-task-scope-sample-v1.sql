-- Maintenance-only additive scope identity. No backfill, sample enrollment, cost or money changes.
ALTER TABLE economy_task_cost_sample
 ADD COLUMN scope_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD COLUMN scope_shape_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD COLUMN scoped_group_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD COLUMN scoped_policy_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL;
