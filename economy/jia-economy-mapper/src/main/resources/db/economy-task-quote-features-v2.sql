-- V2: views only. No columns, indexes, triggers, stored vectors or persistent caches on the foundation tables.
CREATE OR REPLACE SQL SECURITY INVOKER VIEW economy_task_quote_sources AS
SELECT q.id,q.quote_id,q.binding_sha256,q.tenant_id,q.client_id,q.owner_jiacn,q.task_id,q.payer_user_id,q.amount_wen,q.created_at,
 q.snapshot_json AS envelope,
 CASE WHEN BINARY LEFT(q.quote_id,6)<>BINARY 'tq_m1_' AND COALESCE(q.snapshot_json->>'$.schema','')<>'MEASURED_TASK_QUOTE_V1' THEN TRUE
      WHEN q.snapshot_json->>'$.schema'='MEASURED_TASK_QUOTE_V1' AND JSON_TYPE(q.snapshot_json->'$.preparation')='OBJECT' THEN TRUE ELSE FALSE END AS use_envelope,
 p.preparation_sha,p.preparation_json AS evidence
FROM economy_task_quote q LEFT JOIN economy_task_quote_preparation p ON
 p.tenant_id=q.tenant_id AND p.client_id=q.client_id AND p.owner_jiacn=q.owner_jiacn AND p.task_id=q.task_id AND p.payer_user_id=q.payer_user_id
 AND p.recommendation_id=q.snapshot_json->>'$.binding.recommendationId' AND BINARY LEFT(q.quote_id,6)=BINARY 'tq_m1_'
 AND q.snapshot_json->>'$.schema'='MEASURED_TASK_QUOTE_V1' AND JSON_TYPE(q.snapshot_json->'$.preparation')='STRING'
 AND p.preparation_sha=(q.snapshot_json->>'$.preparation' COLLATE utf8mb4_0900_bin)
 AND CHAR_LENGTH(q.snapshot_json)-CHAR_LENGTH(REPLACE(q.snapshot_json,CONCAT('"preparation":"',p.preparation_sha,'"'),''))=80
 AND JSON_TYPE(JSON_EXTRACT(REPLACE(q.snapshot_json,CONCAT('"preparation":"',p.preparation_sha,'"'),'"preparation":null'),'$.preparation'))='NULL'
 AND BINARY p.preparation_sha=BINARY SHA2(p.preparation_json,256) AND JSON_TYPE(p.preparation_json)='OBJECT';
CREATE OR REPLACE SQL SECURITY INVOKER VIEW economy_task_quote_resolved AS
SELECT id,quote_id,binding_sha256,tenant_id,client_id,owner_jiacn,task_id,payer_user_id,amount_wen,created_at,
 CASE WHEN use_envelope THEN envelope WHEN evidence IS NOT NULL THEN
 REPLACE(envelope,CONCAT('"preparation":"',preparation_sha,'"'),CONCAT('"preparation":',evidence)) END AS snapshot_json
FROM economy_task_quote_sources;
CREATE OR REPLACE SQL SECURITY INVOKER VIEW economy_task_quote_history_features AS
SELECT quote_id,tenant_id,client_id,owner_jiacn,task_id,
 CASE WHEN use_envelope THEN JSON_EXTRACT(envelope,'$.preparation.features') WHEN evidence IS NOT NULL THEN JSON_EXTRACT(evidence,'$.features') END AS features_json
FROM economy_task_quote_sources;
