-- Maintenance-only imports of already verified original publication authorizations. No app/HTTP approval writer.
-- Separate from case, training, budget and dispatch permissions; create empty and never auto-import a user sample. Separator: $$.
CREATE TABLE economy_budget_publish_approval (
  approval_sha CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  authority_json LONGTEXT NOT NULL, withdrawn BOOLEAN NOT NULL DEFAULT FALSE,
  recorded_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), PRIMARY KEY(approval_sha)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin$$
CREATE INDEX group_member_approval ON economy_budget_group_member(publish_approval_sha,group_sha)$$
CREATE TRIGGER economy_budget_approval_insert BEFORE INSERT ON economy_budget_publish_approval FOR EACH ROW BEGIN
  IF NOT JSON_VALID(NEW.authority_json) OR SHA2(NEW.authority_json,256)<>NEW.approval_sha THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Invalid aggregate approval bytes';
  END IF;
END$$
CREATE TRIGGER economy_budget_approval_change BEFORE UPDATE ON economy_budget_publish_approval FOR EACH ROW BEGIN
  IF BINARY OLD.authority_json<>BINARY NEW.authority_json OR OLD.approval_sha<>NEW.approval_sha OR OLD.recorded_at<>NEW.recorded_at OR (OLD.withdrawn AND NOT NEW.withdrawn) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Immutable aggregate approval';
  END IF;
END$$
CREATE TRIGGER economy_budget_approval_withdraw AFTER UPDATE ON economy_budget_publish_approval FOR EACH ROW BEGIN
  IF NEW.withdrawn AND NOT OLD.withdrawn THEN
    UPDATE economy_budget_group_member m JOIN economy_budget_group_head h
      ON h.tenant_id=m.tenant_id AND h.client_id=m.client_id AND h.group_sha=m.group_sha
      SET h.generation=h.generation+1,h.status='DIRTY',h.active_revision=NULL,h.tariff_sha=NULL,h.claim_id=NULL,
          h.lease_until=NULL,h.attempt=0,h.next_at=CURRENT_TIMESTAMP(3),h.updated_at=CURRENT_TIMESTAMP(3)
      WHERE m.publish_approval_sha=OLD.approval_sha AND m.enabled AND h.status<>'RETIRED';
  END IF;
END$$
CREATE TRIGGER economy_budget_approval_delete BEFORE DELETE ON economy_budget_publish_approval FOR EACH ROW
SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Withdraw original aggregate approval instead of deleting it'$$
