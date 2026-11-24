-- Read-only inventory, not a grant, proof of delivery, or permission to switch.
-- Bind tenant, client, owner, task (in order); run with an already authorized read-only identity.
WITH requested AS (SELECT ? AS tenant, ? AS client, ? AS owner_id, ? AS task)
SELECT s.tenant_id,s.client_id,s.owner_jiacn,s.task_id,s.membership_revision AS set_revision,s.execution_count,s.execution_set_sha256,
       m.membership_revision AS member_revision,m.capture_id,m.execution_id,m.run_id,m.admission_sha256,t.target_agent_id,
       d.event_id,o.context_hash,SHA2(d.payload_json,256) AS payload_sha256,
       o.installation_id,o.host_id,o.runtime_instance_id,o.session_generation,
       CASE WHEN m.membership_revision IS NULL OR m.capture_id IS NULL THEN 'UNVERIFIED'
            WHEN d.status='SENT' AND o.mixed_origin=FALSE AND o.session_generation>0
             AND BINARY o.context_hash=BINARY t.context_digest AND BINARY r.request_digest=BINARY CONCAT('sha256:',m.admission_sha256)
             AND BINARY d.payload_json->>'$.contextHash'=BINARY t.context_digest
             AND BINARY d.payload_json->>'$.contextSnapshotId'=BINARY t.snapshot_id
             AND BINARY d.payload_json->>'$.messageId'=BINARY d.event_id
             AND BINARY d.payload_json->>'$.turnId'=BINARY m.execution_id AND BINARY d.payload_json->>'$.dispatchId'=BINARY m.run_id
             AND BINARY d.payload_json->>'$.requestId'=BINARY r.request_id AND BINARY d.payload_json->>'$.taskId'=BINARY s.task_id
             AND BINARY d.payload_json->>'$.targetAgentId'=BINARY t.target_agent_id
             AND BINARY d.payload_json->>'$.tenantId'=BINARY s.tenant_id AND BINARY d.payload_json->>'$.clientId'=BINARY s.client_id
             AND BINARY d.payload_json->>'$.ownerJiacn'=BINARY s.owner_jiacn
             AND d.payload_json->>'$.route'='CHAT' AND t.route='CHAT' AND d.task_grant_id IS NOT NULL AND d.task_grant_version IS NOT NULL
             AND d.payload_json->>'$.factsManifest.taskUsageCapture.protocol'='METERED_CAPTURE_V2'
             AND BINARY d.payload_json->>'$.factsManifest.taskUsageCapture.captureId'=BINARY m.capture_id
             THEN 'MAPPED_SENT_ORIGIN' ELSE 'UNVERIFIED' END AS source_state
FROM requested q JOIN agent_task_execution_set s
 ON s.tenant_id=q.tenant AND s.client_id=q.client AND s.owner_jiacn=q.owner_id AND s.task_id=q.task
LEFT JOIN agent_task_execution_member m USING(tenant_id,client_id,owner_jiacn,task_id)
LEFT JOIN chat_turn t ON t.turn_id=m.execution_id COLLATE utf8mb4_0900_bin AND t.dispatch_id=m.run_id COLLATE utf8mb4_0900_bin
 AND t.tenant_id=s.tenant_id COLLATE utf8mb4_0900_bin AND t.client_id=s.client_id COLLATE utf8mb4_0900_bin AND t.owner_jiacn=s.owner_jiacn COLLATE utf8mb4_0900_bin
LEFT JOIN chat_request r ON r.tenant_id=t.tenant_id AND r.client_id=t.client_id AND r.owner_jiacn=t.owner_jiacn
 AND r.request_id=t.request_id AND r.request_revision=t.request_revision
LEFT JOIN chat_dispatch_outbox d ON d.turn_id=t.turn_id AND d.dispatch_id=t.dispatch_id AND d.event_type='DISPATCH'
 AND d.tenant_id=t.tenant_id AND d.client_id=t.client_id AND d.owner_jiacn=t.owner_jiacn
LEFT JOIN chat_task_dispatch_origin o ON o.event_id=d.event_id
WHERE BINARY s.tenant_id=BINARY q.tenant AND BINARY s.client_id=BINARY q.client
 AND BINARY s.owner_jiacn=BINARY q.owner_id AND BINARY s.task_id=BINARY q.task
ORDER BY m.membership_revision
