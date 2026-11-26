-- Restricted selection of externally reviewed study receipts, never a user-facing price switch. No seeds.
CREATE TABLE economy_budget_group_study (
 tenant_id VARCHAR(50) NOT NULL,client_id VARCHAR(50) NOT NULL,group_sha CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 approval_sha CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,PRIMARY KEY(tenant_id,client_id,group_sha),KEY study_by_approval(approval_sha),
 FOREIGN KEY(approval_sha) REFERENCES economy_budget_publish_approval(approval_sha)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin$$
CREATE PROCEDURE economy_budget_study_changed(IN t VARCHAR(50),IN c VARCHAR(50),IN k CHAR(64)) MODIFIES SQL DATA
UPDATE economy_budget_group_head SET generation=generation+1,status='DIRTY',active_revision=NULL,tariff_sha=NULL,claim_id=NULL,
 lease_until=NULL,attempt=0,next_at=CURRENT_TIMESTAMP(3),updated_at=CURRENT_TIMESTAMP(3)
WHERE tenant_id=t AND client_id=c AND group_sha=k AND status<>'RETIRED'$$
CREATE TRIGGER economy_budget_study_insert AFTER INSERT ON economy_budget_group_study FOR EACH ROW CALL economy_budget_study_changed(NEW.tenant_id,NEW.client_id,NEW.group_sha)$$
CREATE TRIGGER economy_budget_study_delete AFTER DELETE ON economy_budget_group_study FOR EACH ROW CALL economy_budget_study_changed(OLD.tenant_id,OLD.client_id,OLD.group_sha)$$
CREATE TRIGGER economy_budget_study_update BEFORE UPDATE ON economy_budget_group_study FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Replace study selection by delete and insert in one transaction'$$
CREATE TRIGGER economy_budget_study_revoked AFTER UPDATE ON economy_budget_publish_approval FOR EACH ROW BEGIN
 IF NEW.withdrawn AND NOT OLD.withdrawn THEN
 UPDATE economy_budget_group_study s JOIN economy_budget_group_head h USING(tenant_id,client_id,group_sha)
 SET h.generation=h.generation+1,h.status='DIRTY',h.active_revision=NULL,h.tariff_sha=NULL,h.claim_id=NULL,h.lease_until=NULL,h.attempt=0,h.next_at=CURRENT_TIMESTAMP(3),h.updated_at=CURRENT_TIMESTAMP(3)
 WHERE s.approval_sha=OLD.approval_sha AND h.status<>'RETIRED';
 END IF;
END$$
