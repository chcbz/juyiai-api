-- Explicit maintenance migration, separator $$. Apply after original source invalidation and scoped sample columns.
-- Changes neither approvals nor sample evidence. No production application performed by this file.
CREATE INDEX group_member_manifest ON economy_budget_group_member(tenant_id,client_id,manifest_sha,source_owner,source_task_id)$$
DROP TRIGGER economy_budget_case_update$$
CREATE TRIGGER economy_budget_case_update AFTER UPDATE ON economy_task_benchmark_case FOR EACH ROW BEGIN
  IF OLD.retired<>NEW.retired OR BINARY OLD.manifest_json<>BINARY NEW.manifest_json OR OLD.manifest_sha<>NEW.manifest_sha
      OR OLD.task_id<>NEW.task_id OR OLD.owner_jiacn<>NEW.owner_jiacn OR OLD.tenant_id<>NEW.tenant_id OR OLD.client_id<>NEW.client_id OR OLD.benchmark_case_id<>NEW.benchmark_case_id THEN
  UPDATE economy_budget_group_member m JOIN economy_budget_group_head h
    ON h.tenant_id=m.tenant_id AND h.client_id=m.client_id AND h.group_sha=m.group_sha
    SET h.status='DIRTY',h.generation=h.generation+1,h.active_revision=NULL,h.tariff_sha=NULL,
        h.claim_id=NULL,h.lease_until=NULL,h.attempt=0,h.next_at=CURRENT_TIMESTAMP(3),h.updated_at=CURRENT_TIMESTAMP(3)
    WHERE m.tenant_id=OLD.tenant_id AND m.client_id=OLD.client_id AND m.source_owner=OLD.owner_jiacn
      AND m.source_task_id=OLD.task_id AND m.manifest_sha=OLD.manifest_sha AND m.enabled AND h.status<>'RETIRED';
  END IF;
END$$
DROP TRIGGER economy_budget_case_delete$$
CREATE TRIGGER economy_budget_case_delete AFTER DELETE ON economy_task_benchmark_case FOR EACH ROW BEGIN
  UPDATE economy_budget_group_member m JOIN economy_budget_group_head h
    ON h.tenant_id=m.tenant_id AND h.client_id=m.client_id AND h.group_sha=m.group_sha
    SET h.status='DIRTY',h.generation=h.generation+1,h.active_revision=NULL,h.tariff_sha=NULL,
        h.claim_id=NULL,h.lease_until=NULL,h.attempt=0,h.next_at=CURRENT_TIMESTAMP(3),h.updated_at=CURRENT_TIMESTAMP(3)
    WHERE m.tenant_id=OLD.tenant_id AND m.client_id=OLD.client_id AND m.source_owner=OLD.owner_jiacn
      AND m.source_task_id=OLD.task_id AND m.manifest_sha=OLD.manifest_sha AND m.enabled AND h.status<>'RETIRED';
END$$
CREATE TRIGGER economy_budget_scoped_sample_update AFTER UPDATE ON economy_task_cost_sample FOR EACH ROW
FOLLOWS economy_budget_sample_update
BEGIN
  IF OLD.benchmark_case_id IS NOT NULL AND (NOT(OLD.scope_sha256 <=> NEW.scope_sha256)
      OR NOT(OLD.scope_shape_sha256 <=> NEW.scope_shape_sha256)
      OR NOT(OLD.scoped_group_sha256 <=> NEW.scoped_group_sha256)
      OR NOT(OLD.scoped_policy_sha256 <=> NEW.scoped_policy_sha256)) THEN
    CALL economy_budget_source_changed(OLD.sample_id);
  END IF;
END$$
