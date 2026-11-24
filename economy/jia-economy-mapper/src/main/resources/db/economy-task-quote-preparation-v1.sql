-- Explicit Economy migration. Store original encoded bytes, never a normalized MySQL JSON value.
CREATE TABLE IF NOT EXISTS economy_task_quote_preparation (
    tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL, owner_jiacn VARCHAR(100) NOT NULL,
    task_id VARCHAR(100) NOT NULL, payer_user_id VARCHAR(100) NOT NULL, recommendation_id VARCHAR(100) NOT NULL,
    preparation_sha CHAR(64) NOT NULL, preparation_json LONGTEXT NOT NULL,
    PRIMARY KEY (tenant_id,client_id,owner_jiacn,task_id,payer_user_id,recommendation_id,preparation_sha)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

-- A compact row replaces only the typed preparation object with its quoted SHA; envelope field order is immaterial.
-- Splice original text rather than JSON_SET: the latter normalizes numbers/whitespace and may change price evidence.
CREATE OR REPLACE SQL SECURITY INVOKER VIEW economy_task_quote_resolved AS
SELECT q.id,q.quote_id,q.binding_sha256,q.tenant_id,q.client_id,q.owner_jiacn,q.task_id,q.payer_user_id,q.amount_wen,q.created_at,
    CASE WHEN BINARY LEFT(q.quote_id,6)<>BINARY 'tq_m1_' AND COALESCE(q.snapshot_json->>'$.schema','')<>'MEASURED_TASK_QUOTE_V1' THEN q.snapshot_json
         WHEN q.snapshot_json->>'$.schema'='MEASURED_TASK_QUOTE_V1' AND JSON_TYPE(JSON_EXTRACT(q.snapshot_json,'$.preparation'))='OBJECT' THEN q.snapshot_json
         WHEN p.preparation_sha IS NOT NULL AND CHAR_LENGTH(q.snapshot_json)-CHAR_LENGTH(REPLACE(q.snapshot_json,CONCAT('"preparation":"',p.preparation_sha,'"'),''))=80
          AND BINARY p.preparation_sha=BINARY SHA2(p.preparation_json,256) AND JSON_TYPE(p.preparation_json)='OBJECT'
         THEN REPLACE(q.snapshot_json,CONCAT('"preparation":"',p.preparation_sha,'"'),CONCAT('"preparation":',p.preparation_json)) END AS snapshot_json
FROM economy_task_quote q LEFT JOIN economy_task_quote_preparation p ON
    p.tenant_id=q.tenant_id AND p.client_id=q.client_id AND p.owner_jiacn=q.owner_jiacn AND p.task_id=q.task_id
    AND p.payer_user_id=q.payer_user_id AND p.recommendation_id=q.snapshot_json->>'$.binding.recommendationId'
    AND p.preparation_sha=CASE WHEN BINARY LEFT(q.quote_id,6)=BINARY 'tq_m1_' AND q.snapshot_json->>'$.schema'='MEASURED_TASK_QUOTE_V1'
      AND JSON_TYPE(JSON_EXTRACT(q.snapshot_json,'$.preparation'))='STRING' THEN (q.snapshot_json->>'$.preparation' COLLATE utf8mb4_0900_bin) END;

-- Same transaction as the quote. Reject future UPDATE/DELETE so a retained quote cannot lose its evidence.
CREATE TRIGGER trg_economy_quote_preparation_no_update BEFORE UPDATE ON economy_task_quote_preparation
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Frozen quote preparation is immutable';
CREATE TRIGGER trg_economy_quote_preparation_no_delete BEFORE DELETE ON economy_task_quote_preparation
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Frozen quote preparation is immutable';
