-- Explicit maintenance-only additive migration; separator $$.
-- Requires sponsorship, group-member/head and original source invalidation schemas.
-- Withdrawal blocks future activation, not already accepted spending or separate sharing approval.
CREATE INDEX group_member_source_task ON economy_budget_group_member(tenant_id,client_id,source_owner,source_task_id)$$
CREATE TRIGGER economy_budget_sponsor_update AFTER UPDATE ON economy_task_benchmark_sponsorship FOR EACH ROW
BEGIN
  IF BINARY OLD.authority_json<>BINARY NEW.authority_json OR OLD.authority_sha<>NEW.authority_sha OR OLD.state<>NEW.state
      OR OLD.task_id<>NEW.task_id OR OLD.owner_jiacn<>NEW.owner_jiacn OR OLD.tenant_id<>NEW.tenant_id OR OLD.client_id<>NEW.client_id THEN
  UPDATE economy_budget_group_member m JOIN economy_budget_group_head h
    ON h.tenant_id=m.tenant_id AND h.client_id=m.client_id AND h.group_sha=m.group_sha
    SET h.status='DIRTY',h.generation=h.generation+1,h.active_revision=NULL,h.tariff_sha=NULL,
        h.claim_id=NULL,h.lease_until=NULL,h.attempt=0,h.next_at=CURRENT_TIMESTAMP(3),h.updated_at=CURRENT_TIMESTAMP(3)
    WHERE m.tenant_id=OLD.tenant_id AND m.client_id=OLD.client_id AND m.source_owner=OLD.owner_jiacn
      AND m.source_task_id=OLD.task_id AND m.enabled AND h.status<>'RETIRED';
  END IF;
END$$
CREATE TRIGGER economy_budget_sponsor_delete AFTER DELETE ON economy_task_benchmark_sponsorship FOR EACH ROW
BEGIN
  UPDATE economy_budget_group_member m JOIN economy_budget_group_head h
    ON h.tenant_id=m.tenant_id AND h.client_id=m.client_id AND h.group_sha=m.group_sha
    SET h.status='DIRTY',h.generation=h.generation+1,h.active_revision=NULL,h.tariff_sha=NULL,
        h.claim_id=NULL,h.lease_until=NULL,h.attempt=0,h.next_at=CURRENT_TIMESTAMP(3),h.updated_at=CURRENT_TIMESTAMP(3)
    WHERE m.tenant_id=OLD.tenant_id AND m.client_id=OLD.client_id AND m.source_owner=OLD.owner_jiacn
      AND m.source_task_id=OLD.task_id AND m.enabled AND h.status<>'RETIRED';
END$$
