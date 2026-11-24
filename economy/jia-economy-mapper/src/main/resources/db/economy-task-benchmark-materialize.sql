-- Maintenance prepared statement: exact training-list bytes, exact observation bytes, calibration generation.
INSERT INTO economy_task_benchmark_use_projection
WITH inputs AS (SELECT ? AS roster, ? AS obs, ? AS gen)
SELECT g.tenant_id,g.client_id,g.owner_jiacn,g.group_sha,g.generation,g.sample_id,m.rev,m.review_sha,m.fact,JSON_EXTRACT(i.obs,'$.operations')
FROM inputs i JOIN JSON_TABLE(i.roster,'$.members[*]' COLUMNS (
 task_id VARCHAR(100) PATH '$.task', sample_id CHAR(64) PATH '$.source.sampleId',rev BIGINT PATH '$.source.revision',
 obs_sha CHAR(64) PATH '$.observationSha256', manifest_sha CHAR(64) PATH '$.benchmark.manifestSha',
 review_sha CHAR(64) PATH '$.benchmark.reviewSha',fact VARCHAR(100) PATH '$.benchmark.fact')) m ON m.obs_sha=SHA2(i.obs,256)
JOIN economy_task_benchmark_use_grant g ON g.sample_id=m.sample_id AND g.generation=i.gen AND g.observation_sha=m.obs_sha
 AND g.tenant_id=JSON_UNQUOTE(JSON_EXTRACT(i.roster,'$.owner.tenantId')) AND g.client_id=JSON_UNQUOTE(JSON_EXTRACT(i.roster,'$.owner.clientId'))
 AND g.owner_jiacn=JSON_UNQUOTE(JSON_EXTRACT(i.roster,'$.owner.ownerJiacn')) AND g.group_sha=JSON_UNQUOTE(JSON_EXTRACT(i.roster,'$.owner.compatibilitySha256'))
JOIN economy_task_estimator_current c USING(tenant_id,client_id,owner_jiacn,group_sha,generation,receipt_sha)
JOIN economy_task_estimator_evidence e USING(tenant_id,client_id,owner_jiacn,group_sha,receipt_sha)
LEFT JOIN economy_task_benchmark_use_stop z ON z.tenant_id=g.tenant_id AND z.client_id=g.client_id AND z.owner_jiacn=g.owner_jiacn AND z.group_sha=g.group_sha AND z.generation=g.generation AND z.sample_id=g.sample_id
WHERE z.sample_id IS NULL AND e.receipt_sha=SHA2(e.receipt_json,256) AND e.receipt_json->>'$.trainingBenchmarksSha256'=SHA2(i.roster,256)
 AND JSON_UNQUOTE(JSON_EXTRACT(i.obs,'$.assignment.split'))='TRAIN' AND JSON_UNQUOTE(JSON_EXTRACT(i.obs,'$.assignment.task'))=m.task_id
 AND m.sample_id=m.manifest_sha AND m.manifest_sha=SHA2(JSON_UNQUOTE(JSON_EXTRACT(i.obs,'$.manifest')),256)
 AND m.review_sha=SHA2(JSON_UNQUOTE(JSON_EXTRACT(i.obs,'$.review')),256) AND JSON_LENGTH(i.obs,'$.operations')>0
