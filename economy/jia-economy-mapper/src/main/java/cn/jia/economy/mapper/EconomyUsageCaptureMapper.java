package cn.jia.economy.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Evidence transaction primitives. A capture PK is obtained from the authenticated binding,
 * never from HTTP input. No mapper operation updates evidence bytes or ledger amounts. */
public interface EconomyUsageCaptureMapper {
    record Binding(String tenantId,String clientId,String executionId,String runId,String captureId,
                   String ownerJiacn,String agentId,String taskId) {}
    record Capture(long id,String ownerJiacn,String agentId,String taskId,long inputRevision,
                   Long projectionRevision,String coverageState,String conflictReason) {}
    record Evidence(long capturePk,String recordId,String revisionKey,String sourceRevision,String kind,
                    String evidenceSha256,String previousEvidenceSha256,String evidenceJson,String associationState) {}
    record Receipt(long capturePk,String receiptId,String recordId,String revisionKey,String requestId,
                   String requestSha256,String expectedRevision,String processingState,String ackJson) {}

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
            previous_evidence_sha256,evidence_json,association_state) VALUES(#{capturePk},#{recordId},#{revisionKey},
            #{sourceRevision},#{kind},#{evidenceSha256},#{previousEvidenceSha256},#{evidenceJson},#{associationState})
        """) int appendEvidence(Evidence evidence);
    @Insert("""
        INSERT INTO economy_usage_capture_receipt(capture_pk,receipt_id,record_id,revision_key,request_id,request_sha256,
            expected_revision,processing_state) VALUES(#{capturePk},#{receiptId},#{recordId},#{revisionKey},#{requestId},
            #{requestSha256},#{expectedRevision},'PENDING')
        """) int appendSubmission(Receipt submission);
    @Select("""
        SELECT capture_pk,receipt_id,record_id,revision_key,request_id,request_sha256,expected_revision,processing_state,ack_json
        FROM economy_usage_capture_receipt WHERE capture_pk=#{pk} AND receipt_id=#{receiptId}
        """) Receipt receipt(@Param("pk") long pk,@Param("receiptId") String receiptId);
    @Update("""
        UPDATE economy_usage_capture_receipt SET ack_json=#{ack},processing_state='ACKED'
        WHERE capture_pk=#{pk} AND receipt_id=#{receiptId} AND request_sha256=#{requestSha256}
        AND processing_state='PENDING' AND ack_json IS NULL
        """) int finishReceipt(@Param("pk") long pk,@Param("receiptId") String receiptId,
                              @Param("requestSha256") String requestSha256,@Param("ack") String ack);
}
