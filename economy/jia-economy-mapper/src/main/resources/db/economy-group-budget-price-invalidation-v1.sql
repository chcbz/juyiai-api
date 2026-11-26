-- Apply after economy-group-budget-v1 and the price catalog. Statement separator: $$.
-- Database-owned invalidation covers maintenance SQL and Java in their original writer transaction.
CREATE PROCEDURE economy_budget_price_changed(IN t VARCHAR(50), IN c VARCHAR(50))
MODIFIES SQL DATA
BEGIN
  DECLARE epoch_now BIGINT;
  INSERT INTO economy_budget_domain_epoch(tenant_id,client_id,book_epoch) VALUES(t,c,1)
      ON DUPLICATE KEY UPDATE book_epoch=book_epoch+1;
  SELECT book_epoch INTO epoch_now FROM economy_budget_domain_epoch WHERE tenant_id=t AND client_id=c FOR UPDATE;
  UPDATE economy_budget_group_head SET generation=generation+1,book_epoch=epoch_now,active_revision=NULL,
      tariff_sha=NULL,claim_id=NULL,lease_until=NULL,attempt=0,next_at=CURRENT_TIMESTAMP(3),
      status='DIRTY',updated_at=CURRENT_TIMESTAMP(3) WHERE tenant_id=t AND client_id=c AND status<>'RETIRED' ORDER BY group_sha;
END$$
CREATE TRIGGER economy_budget_model_price_insert AFTER INSERT ON economy_model_price_current
FOR EACH ROW CALL economy_budget_price_changed(NEW.tenant_id,NEW.client_id)$$
CREATE TRIGGER economy_budget_model_price_delete AFTER DELETE ON economy_model_price_current
FOR EACH ROW CALL economy_budget_price_changed(OLD.tenant_id,OLD.client_id)$$
CREATE TRIGGER economy_budget_model_price_update AFTER UPDATE ON economy_model_price_current
FOR EACH ROW BEGIN
  IF OLD.tenant_id<>NEW.tenant_id OR OLD.client_id<>NEW.client_id THEN
    CALL economy_budget_price_changed(OLD.tenant_id,OLD.client_id);
    CALL economy_budget_price_changed(NEW.tenant_id,NEW.client_id);
  ELSEIF OLD.channel<>NEW.channel OR OLD.model<>NEW.model OR OLD.tier<>NEW.tier OR OLD.price_version<>NEW.price_version THEN
    CALL economy_budget_price_changed(NEW.tenant_id,NEW.client_id);
  END IF;
END$$
CREATE TRIGGER economy_budget_tool_price_insert AFTER INSERT ON economy_tool_price_current
FOR EACH ROW CALL economy_budget_price_changed(NEW.tenant_id,NEW.client_id)$$
CREATE TRIGGER economy_budget_tool_price_delete AFTER DELETE ON economy_tool_price_current
FOR EACH ROW CALL economy_budget_price_changed(OLD.tenant_id,OLD.client_id)$$
CREATE TRIGGER economy_budget_tool_price_update AFTER UPDATE ON economy_tool_price_current
FOR EACH ROW BEGIN
  IF OLD.tenant_id<>NEW.tenant_id OR OLD.client_id<>NEW.client_id THEN
    CALL economy_budget_price_changed(OLD.tenant_id,OLD.client_id);
    CALL economy_budget_price_changed(NEW.tenant_id,NEW.client_id);
  ELSEIF OLD.channel<>NEW.channel OR OLD.tool<>NEW.tool OR OLD.unit<>NEW.unit OR OLD.price_version<>NEW.price_version THEN
    CALL economy_budget_price_changed(NEW.tenant_id,NEW.client_id);
  END IF;
END$$

-- A revision names immutable rate bytes. Change price by appending a version and moving the pointer.
CREATE TRIGGER economy_budget_model_version_no_update BEFORE UPDATE ON economy_model_price
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Published rate version is immutable'$$
CREATE TRIGGER economy_budget_model_version_no_delete BEFORE DELETE ON economy_model_price
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Published rate version is immutable'$$
CREATE TRIGGER economy_budget_tool_version_no_update BEFORE UPDATE ON economy_tool_price
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Published rate version is immutable'$$
CREATE TRIGGER economy_budget_tool_version_no_delete BEFORE DELETE ON economy_tool_price
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Published rate version is immutable'$$
