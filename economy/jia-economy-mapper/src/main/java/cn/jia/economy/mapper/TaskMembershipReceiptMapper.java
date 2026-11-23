package cn.jia.economy.mapper;

import cn.jia.economy.usage.TaskMembership.*;
import org.apache.ibatis.annotations.*;

public interface TaskMembershipReceiptMapper {
    @Insert("""
        INSERT INTO economy_task_membership_receipt(tenant_id,client_id,owner_jiacn,task_id,fact_id,outcome,terminal_revision,closed_at,membership_revision,member_count,set_digest,received_revision,received_count,received_digest,membership_verified)
        VALUES(#{t.tenant},#{t.client},#{t.owner},#{t.task},#{t.fact},#{t.outcome},#{t.terminalRevision},#{t.closedAt},#{t.membershipRevision},#{t.memberCount},#{t.digest},0,0,#{empty},FALSE)
        ON DUPLICATE KEY UPDATE fact_id=fact_id
        """) void ensure(@Param("t") Terminal terminal,@Param("empty") String empty);
    @Select("""
        SELECT received_revision,received_count,received_digest,membership_verified FROM economy_task_membership_receipt
        WHERE tenant_id=#{tenant} AND client_id=#{client} AND owner_jiacn=#{owner} AND task_id=#{task} AND fact_id=#{fact} AND outcome=#{outcome}
        AND terminal_revision=#{terminalRevision} AND closed_at=#{closedAt} AND membership_revision=#{membershipRevision} AND member_count=#{memberCount} AND set_digest=#{digest} FOR UPDATE
        """) Cursor lock(Terminal t);
    @Insert("""
        <script>INSERT INTO economy_task_membership_received_run(tenant_id,client_id,fact_id,membership_revision,execution_id,run_id,capture_id,admission_sha256) VALUES
        <foreach collection="rows" item="r" separator=",">(#{t.tenant},#{t.client},#{t.fact},#{r.revision},#{r.execution},#{r.run},#{r.capture},#{r.admission})</foreach></script>
        """) int add(@Param("t") Terminal t,@Param("rows") java.util.List<Member> rows);
    // Only the confirmed fixed quote of this task; never attach benchmark or legacy rows by task alone.
    @Select("""
        SELECT s.sample_id FROM economy_task_cost_sample s JOIN economy_task_charge q
        ON q.tenant_id=s.tenant_id AND q.client_id=s.client_id AND q.owner_jiacn=s.owner_jiacn AND q.task_id=s.task_id AND q.quote_id=s.quote_id
        WHERE s.tenant_id=#{tenant} AND s.client_id=#{client} AND s.owner_jiacn=#{owner} AND s.task_id=#{task}
          AND s.sample_definition_version='TASK_LIFECYCLE_V1' AND s.benchmark_case_id IS NULL
        """) String confirmed(Terminal t);
    @Insert("""
        INSERT INTO economy_task_cost_sample_member(sample_id,execution_id,run_id,capture_id)
        SELECT #{sid},execution_id,run_id,capture_id FROM economy_task_membership_received_run
        WHERE tenant_id=#{t.tenant} AND client_id=#{t.client} AND fact_id=#{t.fact}
          AND membership_revision>#{before} AND membership_revision<=#{after}
        """) int attachReceived(@Param("sid") String sampleId,@Param("t") Terminal terminal,@Param("before") long before,@Param("after") long after);
    @Update("""
        UPDATE economy_task_cost_sample SET lifecycle_state=#{t.outcome}, evidence_revision=evidence_revision+1,
          sample_state=CASE WHEN #{t.outcome}='CANCELLED' OR sample_state='EXCLUDED' THEN 'EXCLUDED' ELSE 'WAITING_EVIDENCE' END
        WHERE sample_id=#{sid} AND lifecycle_state='ACTIVE'
        """) int closeSample(@Param("sid") String sampleId,@Param("t") Terminal terminal);
    @Update("""
        UPDATE economy_task_membership_receipt SET received_revision=#{c.revision},received_count=#{c.count},received_digest=#{c.digest},membership_verified=#{c.verified}
        WHERE tenant_id=#{t.tenant} AND client_id=#{t.client} AND owner_jiacn=#{t.owner} AND task_id=#{t.task} AND fact_id=#{t.fact} AND received_revision=#{before} AND membership_verified=FALSE
        """) int advance(@Param("t") Terminal t,@Param("before") long before,@Param("c") Cursor c);
}
