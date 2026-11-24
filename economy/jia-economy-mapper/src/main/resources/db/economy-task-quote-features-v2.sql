-- Explicit Economy maintenance AFTER quote-preparation-v1. Store only a locator + validity bit, not a second vector.
-- NULL means unusable; empty means use the embedded envelope; otherwise the exact original preparation SHA.
ALTER TABLE economy_task_quote ADD COLUMN preparation_locator VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin
 GENERATED ALWAYS AS (CASE WHEN JSON_VALID(snapshot_json) THEN CASE
 WHEN BINARY LEFT(quote_id,6)<>BINARY 'tq_m1_' AND COALESCE(snapshot_json->>'$.schema','')<>'MEASURED_TASK_QUOTE_V1' THEN ''
 WHEN snapshot_json->>'$.schema'='MEASURED_TASK_QUOTE_V1' AND JSON_TYPE(snapshot_json->'$.preparation')='OBJECT' THEN ''
 WHEN BINARY LEFT(quote_id,6)=BINARY 'tq_m1_' AND snapshot_json->>'$.schema'='MEASURED_TASK_QUOTE_V1'
 AND JSON_TYPE(snapshot_json->'$.preparation')='STRING' AND CHAR_LENGTH(snapshot_json->>'$.preparation')=64
 AND CHAR_LENGTH(snapshot_json)-CHAR_LENGTH(REPLACE(snapshot_json,CONCAT('"preparation":"',snapshot_json->>'$.preparation','"'),''))=80
 AND JSON_TYPE(JSON_EXTRACT(REPLACE(snapshot_json,CONCAT('"preparation":"',snapshot_json->>'$.preparation','"'),'"preparation":null'),'$.preparation'))='NULL'
 THEN snapshot_json->>'$.preparation' END END) STORED;
ALTER TABLE economy_task_quote_preparation ADD COLUMN evidence_bytes_valid BOOLEAN GENERATED ALWAYS AS
 (CASE WHEN JSON_VALID(preparation_json) THEN BINARY preparation_sha=BINARY SHA2(preparation_json,256) AND JSON_TYPE(preparation_json)='OBJECT' ELSE FALSE END) STORED;

-- Common scoped resolution for historical features and exact byte-for-byte quote replay. No target chooses the source.
CREATE OR REPLACE SQL SECURITY INVOKER VIEW economy_task_quote_sources AS
SELECT q.id,q.quote_id,q.binding_sha256,q.tenant_id,q.client_id,q.owner_jiacn,q.task_id,q.payer_user_id,q.amount_wen,q.created_at,
 q.preparation_locator,q.snapshot_json AS envelope,p.preparation_json AS evidence
FROM economy_task_quote q LEFT JOIN economy_task_quote_preparation p ON p.preparation_sha=q.preparation_locator AND q.preparation_locator<>''
 AND p.tenant_id=q.tenant_id AND p.client_id=q.client_id AND p.owner_jiacn=q.owner_jiacn AND p.task_id=q.task_id
 AND p.payer_user_id=q.payer_user_id AND p.recommendation_id=q.snapshot_json->>'$.binding.recommendationId' AND p.evidence_bytes_valid;
CREATE OR REPLACE SQL SECURITY INVOKER VIEW economy_task_quote_resolved AS
SELECT id,quote_id,binding_sha256,tenant_id,client_id,owner_jiacn,task_id,payer_user_id,amount_wen,created_at,
 CASE WHEN preparation_locator='' THEN envelope WHEN evidence IS NOT NULL THEN
 REPLACE(envelope,CONCAT('"preparation":"',preparation_locator,'"'),CONCAT('"preparation":',evidence)) END AS snapshot_json
FROM economy_task_quote_sources;
CREATE OR REPLACE SQL SECURITY INVOKER VIEW economy_task_quote_history_features AS
SELECT quote_id,tenant_id,client_id,owner_jiacn,task_id,
 CASE WHEN preparation_locator='' THEN JSON_EXTRACT(envelope,'$.preparation.features') WHEN evidence IS NOT NULL THEN JSON_EXTRACT(evidence,'$.features') END AS features_json
FROM economy_task_quote_sources;
