package cn.jia.economy.mapper;

import cn.jia.economy.usage.UsageCaptureProjection;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Evidence transaction primitives. A capture PK is obtained from the authenticated binding,
 * never from HTTP input. No mapper operation updates evidence bytes or ledger amounts. */
public interface EconomyUsageCaptureMapper {
    @Select("""
        SELECT input_revision,capture_id FROM economy_usage_capture WHERE id=#{pk} AND tenant_id=#{tenant}
        AND client_id=#{client} AND conflict_reason IS NULL AND (projection_revision IS NULL OR projection_revision<>input_revision)
        """) UsageCaptureProjection.Snapshot pendingProjection(UsageCaptureProjection.Key key);
    // An associated head at revision N proves the prefix [1,N]. Any extra immutable revision is not yet associated.
    @Select("""
        SELECT g.record_id,g.revisions,h.source_revision,h.evidence_sha256,h.evidence_json FROM (
          SELECT r.capture_pk,r.record_id,COUNT(*) AS revisions FROM economy_usage_capture_record r
          JOIN economy_usage_capture c ON c.id=r.capture_pk AND c.tenant_id=#{k.tenant} AND c.client_id=#{k.client}
          WHERE r.capture_pk=#{k.pk} AND r.record_id>#{after} GROUP BY r.capture_pk,r.record_id ORDER BY r.record_id LIMIT 64
        ) g LEFT JOIN economy_usage_capture_record h ON h.capture_pk=g.capture_pk AND h.record_id=g.record_id AND h.is_associated_head=1
        ORDER BY g.record_id
        """) java.util.List<UsageCaptureProjection.Row> projectionPage(@Param("k") UsageCaptureProjection.Key key,@Param("after") String after);
    @Update("""
        UPDATE economy_usage_capture SET projection_revision=#{rev},coverage_state=#{state} WHERE id=#{k.pk}
        AND tenant_id=#{k.tenant} AND client_id=#{k.client} AND input_revision=#{rev} AND conflict_reason IS NULL
        AND (projection_revision IS NULL OR projection_revision<>input_revision)
        """) int publishProjection(@Param("k") UsageCaptureProjection.Key key,@Param("rev") long revision,@Param("state") String state);
    record Binding(String tenantId,String clientId,String executionId,String runId,String captureId,
                   String ownerJiacn,String agentId,String taskId) {}
    record Capture(long id,String ownerJiacn,String agentId,String taskId,long inputRevision,
                   Long projectionRevision,String coverageState,String conflictReason) {}
    record Evidence(long capturePk,String recordId,String revisionKey,String sourceRevision,String kind,
                    String evidenceSha256,String previousEvidenceSha256,String evidenceJson,String bindingSha256,String associationState) {}
    record Receipt(long capturePk,String receiptId,String recordId,String revisionKey,String requestId,
                   String requestSha256,String expectedRevision,String processingState,String ackJson) {}

    @Select("""
        SELECT s.sample_id FROM economy_task_cost_sample s JOIN economy_task_cost_sample_member m ON m.sample_id=s.sample_id
        WHERE s.tenant_id=#{tenantId} AND s.client_id=#{clientId} AND s.owner_jiacn=#{ownerJiacn} AND s.task_id=#{taskId}
        AND m.execution_id=#{executionId} AND m.run_id=#{runId} AND m.capture_id=#{captureId} ORDER BY s.sample_id FOR UPDATE
        """) java.util.List<String> lockMembers(Binding binding);
    @Update("UPDATE economy_task_cost_sample SET evidence_revision=evidence_revision+1, sample_state=CASE WHEN sample_state='EXCLUDED' THEN 'EXCLUDED' ELSE 'WAITING_EVIDENCE' END WHERE sample_id=#{id}")
    int invalidateSample(@Param("id") String id);
    @Update("UPDATE economy_usage_capture SET input_revision=input_revision+1,coverage_state=CASE WHEN #{reason} IS NULL THEN 'WAITING_EVIDENCE' ELSE 'INCOMPLETE' END,conflict_reason=#{reason} WHERE id=#{pk}")
    int invalidateCapture(@Param("pk") long pk,@Param("reason") String reason);
    @Select("SELECT capture_pk,record_id,revision_key,source_revision,kind,evidence_sha256,previous_evidence_sha256,evidence_json,binding_sha256,association_state FROM economy_usage_capture_record WHERE capture_pk=#{pk} AND record_id=#{recordId} AND revision_key=#{key} FOR UPDATE")
    Evidence evidence(@Param("pk") long pk,@Param("recordId") String recordId,@Param("key") String key);
    // Caller holds the capture lock. Every admitted revision has the same binding, even across gaps.
    @Select("SELECT binding_sha256 FROM economy_usage_capture_record WHERE capture_pk=#{pk} AND record_id=#{recordId} LIMIT 1 FOR UPDATE")
    String recordBinding(@Param("pk") long pk,@Param("recordId") String recordId);
    @Insert("""
        INSERT INTO economy_usage_capture(tenant_id,client_id,execution_id,run_id,capture_id,owner_jiacn,agent_id,task_id)
        VALUES(#{tenantId},#{clientId},#{executionId},#{runId},#{captureId},#{ownerJiacn},#{agentId},#{taskId})
        ON DUPLICATE KEY UPDATE id=id
        """) int ensureCapture(Binding binding);
    @Select("""
        SELECT id,owner_jiacn,agent_id,task_id,input_revision,projection_revision,coverage_state,conflict_reason
        FROM economy_usage_capture WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND execution_id=#{executionId}
        AND run_id=#{runId} AND capture_id=#{captureId} FOR UPDATE
        """) Capture lockCapture(Binding binding);
    @Insert("""
        INSERT INTO economy_usage_capture_record(capture_pk,record_id,revision_key,source_revision,kind,evidence_sha256,
            previous_evidence_sha256,evidence_json,binding_sha256,association_state) VALUES(#{capturePk},#{recordId},#{revisionKey},
            #{sourceRevision},#{kind},#{evidenceSha256},#{previousEvidenceSha256},#{evidenceJson},#{bindingSha256},#{associationState})
        """) int appendEvidence(Evidence evidence);
    @Insert("""
        INSERT INTO economy_usage_capture_receipt(capture_pk,receipt_id,record_id,revision_key,request_id,request_sha256,
            expected_revision,processing_state) VALUES(#{capturePk},#{receiptId},#{recordId},#{revisionKey},#{requestId},
            #{requestSha256},#{expectedRevision},'PENDING')
        """) int appendSubmission(Receipt submission);
    @Select("""
        SELECT capture_pk,receipt_id,record_id,revision_key,request_id,request_sha256,expected_revision,processing_state,ack_json
        FROM economy_usage_capture_receipt WHERE capture_pk=#{pk} AND receipt_id=#{receiptId} FOR UPDATE
        """) Receipt receipt(@Param("pk") long pk,@Param("receiptId") String receiptId);
    @Select("SELECT applying_receipt FROM economy_usage_capture_record WHERE capture_pk=#{pk} AND record_id=#{record} AND revision_key=#{key} FOR UPDATE")
    String applying(@Param("pk") long pk,@Param("record") String record,@Param("key") String key);
    @Update("UPDATE economy_usage_capture_record SET applying_receipt=#{receipt},association_state=#{state} WHERE capture_pk=#{pk} AND record_id=#{record} AND revision_key=#{key}")
    int application(@Param("pk") long pk,@Param("record") String record,@Param("key") String key,@Param("receipt") String receipt,@Param("state") String state);
    @Update("UPDATE economy_usage_capture_receipt SET processing_state='CAS_CONFLICT' WHERE capture_pk=#{pk} AND receipt_id=#{receipt} AND processing_state='PENDING'")
    int rejectCas(@Param("pk") long pk,@Param("receipt") String receipt);
    record Head(String sourceRevision,String evidenceSha256,String ledgerAttemptId) {}
    @Select("SELECT source_revision,evidence_sha256,ledger_attempt_id FROM economy_usage_capture_record WHERE capture_pk=#{pk} AND record_id=#{id} AND is_associated_head=1 FOR UPDATE")
    Head head(@Param("pk") long pk,@Param("id") String id);
    @Update("UPDATE economy_usage_capture_record SET is_associated_head=0 WHERE capture_pk=#{pk} AND record_id=#{id} AND is_associated_head=1")
    int clearHead(@Param("pk") long pk,@Param("id") String id);
    @Update("UPDATE economy_usage_capture_record SET is_associated_head=1,ledger_attempt_id=#{attempt} WHERE capture_pk=#{pk} AND record_id=#{id} AND revision_key=#{key}")
    int publishHead(@Param("pk") long pk,@Param("id") String id,@Param("key") String key,@Param("attempt") String attempt);
    @Select("SELECT revision FROM economy_usage_attempt WHERE tenant_id=#{tenant} AND client_id=#{client} AND attempt_id=#{attempt} FOR UPDATE")
    Long ledgerRevision(@Param("tenant") String tenant,@Param("client") String client,@Param("attempt") String attempt);
    record Recovery(String tenantId,String clientId,String ownerJiacn,String agentId,String taskId,String executionId,String runId,String captureId,String kind) {}
    @Select("""
        SELECT c.tenant_id,c.client_id,c.owner_jiacn,c.agent_id,c.task_id,c.execution_id,c.run_id,c.capture_id,e.kind
        FROM economy_usage_capture c JOIN economy_usage_capture_receipt r ON r.capture_pk=c.id
        JOIN economy_usage_capture_record e ON e.capture_pk=r.capture_pk AND e.record_id=r.record_id AND e.revision_key=r.revision_key
        WHERE c.id=#{pk} AND c.tenant_id=#{tenant} AND c.client_id=#{client} AND r.receipt_id=#{receipt}
        """) Recovery recovery(@Param("tenant") String tenant,@Param("client") String client,@Param("pk") long pk,@Param("receipt") String receipt);
    record Origin(String tenantId,String clientId,String sourceAttemptId,long capturePk,String recordId) {}
    @Insert("""
        INSERT INTO economy_usage_response_origin (tenant_id,client_id,source_attempt_id,capture_pk,record_id)
        VALUES(#{tenantId},#{clientId},#{sourceAttemptId},#{capturePk},#{recordId}) ON DUPLICATE KEY UPDATE capture_pk=capture_pk
        """) int ensureOrigin(Origin origin);
    @Select("""
        SELECT tenant_id,client_id,source_attempt_id,capture_pk,record_id FROM economy_usage_response_origin
        WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND source_attempt_id=#{sourceAttemptId} FOR UPDATE
        """) Origin lockOrigin(Origin origin);
    @Update("""
        UPDATE economy_usage_capture_receipt SET ack_json=#{ack},processing_state='ACKED'
        WHERE capture_pk=#{pk} AND receipt_id=#{receiptId} AND request_sha256=#{requestSha256}
        AND processing_state='PENDING' AND ack_json IS NULL
        """) int finishReceipt(@Param("pk") long pk,@Param("receiptId") String receiptId,
                              @Param("requestSha256") String requestSha256,@Param("ack") String ack);
}
