-- Install after benchmark case/review, task evidence and group schemas. Separator: $$.
-- review sample IDs are global: exact reverse lookup also when the writer has no tenant/client columns.
CREATE INDEX group_member_sample ON economy_budget_group_member(sample_id,group_sha)$$
CREATE PROCEDURE economy_budget_source_changed(IN src CHAR(64) CHARACTER SET ascii)
MODIFIES SQL DATA
BEGIN
  UPDATE economy_budget_group_member m JOIN economy_budget_group_head h
    ON h.tenant_id=m.tenant_id AND h.client_id=m.client_id AND h.group_sha=m.group_sha
    SET h.status='DIRTY',h.generation=h.generation+1,h.active_revision=NULL,h.tariff_sha=NULL,
        h.claim_id=NULL,h.lease_until=NULL,h.attempt=0,h.next_at=CURRENT_TIMESTAMP(3),h.updated_at=CURRENT_TIMESTAMP(3)
    WHERE m.sample_id=src AND m.enabled AND h.status<>'RETIRED';
END$$
CREATE TRIGGER economy_budget_case_update AFTER UPDATE ON economy_task_benchmark_case FOR EACH ROW BEGIN
  IF OLD.retired<>NEW.retired OR BINARY OLD.manifest_json<>BINARY NEW.manifest_json OR OLD.manifest_sha<>NEW.manifest_sha
      OR OLD.task_id<>NEW.task_id OR OLD.owner_jiacn<>NEW.owner_jiacn OR OLD.tenant_id<>NEW.tenant_id OR OLD.client_id<>NEW.client_id OR OLD.benchmark_case_id<>NEW.benchmark_case_id THEN
    CALL economy_budget_source_changed(OLD.manifest_sha);
  END IF;
END$$
CREATE TRIGGER economy_budget_case_delete AFTER DELETE ON economy_task_benchmark_case FOR EACH ROW CALL economy_budget_source_changed(OLD.manifest_sha)$$
CREATE TRIGGER economy_budget_review_update AFTER UPDATE ON economy_task_benchmark_review FOR EACH ROW BEGIN
  IF OLD.withdrawn<>NEW.withdrawn OR BINARY OLD.review_json<>BINARY NEW.review_json OR OLD.review_sha<>NEW.review_sha OR OLD.sample_id<>NEW.sample_id THEN
    CALL economy_budget_source_changed(OLD.sample_id);
  END IF;
END$$
CREATE TRIGGER economy_budget_review_delete AFTER DELETE ON economy_task_benchmark_review FOR EACH ROW CALL economy_budget_source_changed(OLD.sample_id)$$
CREATE TRIGGER economy_budget_sample_update AFTER UPDATE ON economy_task_cost_sample FOR EACH ROW BEGIN
  IF OLD.benchmark_case_id IS NOT NULL AND (OLD.evidence_revision<>NEW.evidence_revision OR OLD.lifecycle_state<>NEW.lifecycle_state OR OLD.sample_state<>NEW.sample_state
      OR NOT(OLD.benchmark_case_id <=> NEW.benchmark_case_id) OR OLD.sample_id<>NEW.sample_id) THEN
    CALL economy_budget_source_changed(OLD.sample_id);
  END IF;
END$$
CREATE TRIGGER economy_budget_sample_delete AFTER DELETE ON economy_task_cost_sample FOR EACH ROW BEGIN
  IF OLD.benchmark_case_id IS NOT NULL THEN CALL economy_budget_source_changed(OLD.sample_id); END IF;
END$$
