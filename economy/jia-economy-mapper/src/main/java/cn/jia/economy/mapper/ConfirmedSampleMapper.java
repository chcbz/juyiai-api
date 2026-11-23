package cn.jia.economy.mapper;

import cn.jia.economy.task.TaskBillingService.Task;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Only Economy projections; never read Agent task or runtime-state tables. */
public interface ConfirmedSampleMapper {
    record Header(String lifecycle,long evidenceRevision,String fact,String outcome,Boolean verified,Long memberCount,
                  String agent,String groupVersion,String executionConfig,String localRoute,String referenceRoute,String tools,String policy) {}
    @Select("""
        SELECT s.lifecycle_state,s.evidence_revision,r.fact_id,r.outcome,r.membership_verified,r.member_count,
          q.snapshot_json->>'$.binding.agentId',q.snapshot_json->>'$.preparation.group.dispatchGroupVersion',
          q.snapshot_json->>'$.preparation.group.executionConfigSha256',q.snapshot_json->>'$.preparation.group.localRouteSha256',
          q.snapshot_json->>'$.preparation.group.routeVersion',q.snapshot_json->>'$.preparation.group.toolConfigSha256',
          q.snapshot_json->>'$.preparation.group.capturePolicyVersion'
        FROM economy_task_cost_sample s JOIN economy_task_charge ch ON ch.tenant_id=s.tenant_id AND ch.client_id=s.client_id
          AND ch.owner_jiacn=s.owner_jiacn AND ch.task_id=s.task_id AND ch.quote_id=s.quote_id
        JOIN economy_task_quote q ON q.tenant_id=ch.tenant_id AND q.client_id=ch.client_id AND q.owner_jiacn=ch.owner_jiacn
          AND q.task_id=ch.task_id AND q.quote_id=ch.quote_id AND q.payer_user_id=ch.payer_user_id
        LEFT JOIN economy_task_membership_receipt r ON r.tenant_id=s.tenant_id AND r.client_id=s.client_id AND r.owner_jiacn=s.owner_jiacn AND r.task_id=s.task_id
        WHERE s.sample_id=#{id} AND s.tenant_id=#{t.scope.tenantId} AND s.client_id=#{t.scope.clientId} AND s.owner_jiacn=#{t.ownerJiacn}
          AND s.task_id=#{t.taskId} AND s.sample_definition_version='TASK_LIFECYCLE_V1' AND s.benchmark_case_id IS NULL
        """) Header confirmed(@Param("t") Task task,@Param("id") String sampleId);
    record Member(long revision,String attached,String coverage,String groupVersion,String executionConfig,String route,String tools,String policy) {}
    @Select("""
        SELECT r.membership_revision,m.execution_id,c.coverage_state,g.group_version,g.execution_config_sha256,g.route_sha256,g.tool_config_sha256,g.capture_policy_version
        FROM economy_task_membership_received_run r
        LEFT JOIN economy_task_cost_sample_member m ON m.sample_id=#{id} AND m.execution_id=r.execution_id AND m.run_id=r.run_id AND m.capture_id <=> r.capture_id
        LEFT JOIN economy_usage_capture c ON c.tenant_id=r.tenant_id AND c.client_id=r.client_id AND c.owner_jiacn=#{t.ownerJiacn} AND c.task_id=#{t.taskId}
          AND c.agent_id=#{agent} AND c.execution_id=r.execution_id AND c.run_id=r.run_id AND c.capture_id=m.capture_id
          AND c.input_revision=c.projection_revision AND c.conflict_reason IS NULL
        LEFT JOIN economy_usage_capture_dispatch_group g ON g.capture_pk=c.id AND g.projection_revision=c.projection_revision
        WHERE r.tenant_id=#{t.scope.tenantId} AND r.client_id=#{t.scope.clientId} AND r.fact_id=#{fact} AND r.membership_revision>#{after}
        ORDER BY r.membership_revision LIMIT 64
        """) List<Member> members(@Param("t") Task task,@Param("id") String id,@Param("fact") String fact,@Param("agent") String agent,@Param("after") long after);
    // LEFT JOIN preserves a member with no associated responses. Never treat missing as a free run.
    @Select("""
        SELECT r.membership_revision,c.capture_id,c.input_revision,e.record_id,e.source_revision,e.evidence_sha256,e.evidence_json
        FROM economy_task_membership_received_run r
        JOIN economy_usage_capture c ON c.tenant_id=r.tenant_id AND c.client_id=r.client_id AND c.owner_jiacn=#{t.ownerJiacn} AND c.task_id=#{t.taskId}
          AND c.agent_id=#{agent} AND c.execution_id=r.execution_id AND c.run_id=r.run_id AND c.capture_id=r.capture_id
          AND c.projection_revision=c.input_revision AND c.coverage_state='COMPLETE' AND c.conflict_reason IS NULL
        LEFT JOIN economy_usage_capture_record e ON e.capture_pk=c.id AND e.is_associated_head=1 AND e.kind='RESPONSE'
        WHERE r.tenant_id=#{t.scope.tenantId} AND r.client_id=#{t.scope.clientId} AND r.fact_id=#{fact}
          AND (r.membership_revision>#{rev} OR (r.membership_revision=#{rev} AND e.record_id>#{record}))
        ORDER BY r.membership_revision,e.record_id LIMIT 64
        """) List<cn.jia.economy.usage.ConfirmedSampleEvidence.Response> responses(@Param("t") Task task,@Param("fact") String fact,
            @Param("agent") String agent,@Param("rev") long after,@Param("record") String afterRecord);
}
