-- Explicit Economy migration. Store original encoded bytes, never a normalized MySQL JSON value.
CREATE TABLE IF NOT EXISTS economy_task_quote_preparation (
    tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL, owner_jiacn VARCHAR(100) NOT NULL,
    task_id VARCHAR(100) NOT NULL, payer_user_id VARCHAR(100) NOT NULL, recommendation_id VARCHAR(100) NOT NULL,
    preparation_sha CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, preparation_json LONGTEXT NOT NULL,
    PRIMARY KEY (tenant_id,client_id,owner_jiacn,task_id,payer_user_id,recommendation_id,preparation_sha)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

-- MeasuredSnapshot encodes preparation last. A compact row replaces ONLY that final object with its quoted SHA.
-- Splice original text rather than JSON_SET: the latter normalizes numbers/whitespace and may change price evidence.
CREATE OR REPLACE SQL SECURITY INVOKER VIEW economy_task_quote_resolved AS
SELECT q.id,q.quote_id,q.binding_sha256,q.tenant_id,q.client_id,q.owner_jiacn,q.task_id,q.payer_user_id,q.amount_wen,q.created_at,
    CASE WHEN p.preparation_sha IS NULL THEN q.snapshot_json ELSE
      CONCAT(LEFT(q.snapshot_json,CHAR_LENGTH(q.snapshot_json)-67),p.preparation_json,'}') END AS snapshot_json
FROM economy_task_quote q LEFT JOIN economy_task_quote_preparation p ON
    p.tenant_id=q.tenant_id AND p.client_id=q.client_id AND p.owner_jiacn=q.owner_jiacn AND p.task_id=q.task_id
    AND p.payer_user_id=q.payer_user_id AND p.recommendation_id=q.snapshot_json->>'$.binding.recommendationId'
    AND p.preparation_sha=q.snapshot_json->>'$.preparation'
WHERE (q.quote_id NOT LIKE 'tq!_m1!_%' ESCAPE '!') OR JSON_TYPE(JSON_EXTRACT(q.snapshot_json,'$.preparation'))='OBJECT'
    OR (p.preparation_sha IS NOT NULL AND q.snapshot_json->>'$.schema'='MEASURED_TASK_QUOTE_V1'
      AND BINARY RIGHT(q.snapshot_json,81)=BINARY CONCAT('"preparation":"',p.preparation_sha,'"}')
      AND BINARY p.preparation_sha=BINARY SHA2(p.preparation_json,256) AND JSON_TYPE(p.preparation_json)='OBJECT');
