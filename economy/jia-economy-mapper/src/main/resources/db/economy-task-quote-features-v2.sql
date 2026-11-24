-- Explicit Economy V2 integrity patch. No new intermediate view or change to the history query.
CREATE OR REPLACE SQL SECURITY INVOKER VIEW economy_task_quote_resolved AS
SELECT q.id,q.quote_id,q.binding_sha256,q.tenant_id,q.client_id,q.owner_jiacn,q.task_id,q.payer_user_id,q.amount_wen,q.created_at,
    CASE WHEN BINARY LEFT(q.quote_id,6)<>BINARY 'tq_m1_' AND COALESCE(q.snapshot_json->>'$.schema','')<>'MEASURED_TASK_QUOTE_V1' THEN q.snapshot_json
         WHEN q.snapshot_json->>'$.schema'='MEASURED_TASK_QUOTE_V1' AND JSON_TYPE(JSON_EXTRACT(q.snapshot_json,'$.preparation'))='OBJECT' THEN q.snapshot_json
         WHEN p.preparation_sha IS NOT NULL AND CHAR_LENGTH(q.snapshot_json)-CHAR_LENGTH(REPLACE(q.snapshot_json,CONCAT('"preparation":"',p.preparation_sha,'"'),''))=80
          AND JSON_TYPE(JSON_EXTRACT(REPLACE(q.snapshot_json,CONCAT('"preparation":"',p.preparation_sha,'"'),'"preparation":null'),'$.preparation'))='NULL'
          AND BINARY p.preparation_sha=BINARY SHA2(p.preparation_json,256) AND JSON_TYPE(p.preparation_json)='OBJECT'
         THEN REPLACE(q.snapshot_json,CONCAT('"preparation":"',p.preparation_sha,'"'),CONCAT('"preparation":',p.preparation_json)) END AS snapshot_json
FROM economy_task_quote q LEFT JOIN economy_task_quote_preparation p ON
    p.tenant_id=q.tenant_id AND p.client_id=q.client_id AND p.owner_jiacn=q.owner_jiacn AND p.task_id=q.task_id
    AND p.payer_user_id=q.payer_user_id AND p.recommendation_id=q.snapshot_json->>'$.binding.recommendationId'
    AND p.preparation_sha=CASE WHEN BINARY LEFT(q.quote_id,6)=BINARY 'tq_m1_' AND q.snapshot_json->>'$.schema'='MEASURED_TASK_QUOTE_V1'
      AND JSON_TYPE(JSON_EXTRACT(q.snapshot_json,'$.preparation'))='STRING' THEN (q.snapshot_json->>'$.preparation' COLLATE utf8mb4_0900_bin) END;
