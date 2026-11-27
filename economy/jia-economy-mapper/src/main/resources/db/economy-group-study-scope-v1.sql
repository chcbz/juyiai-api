-- Explicit maintenance migration AFTER economy-group-study-v1.sql. No seeds or legacy backfill.
-- Existing insert/delete/approval withdrawal triggers and immutable approval bytes are retained.
ALTER TABLE economy_budget_group_study
 ADD COLUMN scope_input_sha CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD UNIQUE KEY study_by_scope_input(tenant_id,client_id,scope_input_sha);
