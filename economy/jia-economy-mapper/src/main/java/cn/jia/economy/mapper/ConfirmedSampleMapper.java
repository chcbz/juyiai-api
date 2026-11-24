package cn.jia.economy.mapper;

import cn.jia.economy.task.TaskBillingService.Task;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Select;

/** Only Economy projections; never read Agent task or runtime-state tables. */
public interface ConfirmedSampleMapper {
    @Select("""
        <script>SELECT client_id,sample_id FROM economy_task_cost_sample WHERE tenant_id=#{tenant}
        <if test="client != null"> AND client_id=#{client}</if> AND projection_pending=1 AND sample_id &gt; #{after}
        ORDER BY sample_id LIMIT #{limit}</script>
        """) List<cn.jia.economy.usage.ConfirmedSampleProjection.Pending> pending(@Param("tenant") String tenant,@Param("client") String client,@Param("after") String after,@Param("limit") int limit);
    record Target(String owner,String taskId) {}
    @Select("SELECT owner_jiacn,task_id FROM economy_task_cost_sample WHERE sample_id=#{id} AND tenant_id=#{tenant} AND client_id=#{client} AND projection_pending=1")
    Target target(@Param("tenant") String tenant,@Param("client") String client,@Param("id") String id);
    // Accepted confirmed rows only. A new task may use another agent's same-config history under this owner,
    // but cannot train on its own record, previous READY revisions, benchmark rows or another namespace.
    String ELIGIBLE_HISTORY="""
        s.tenant_id=#{t.scope.tenantId} AND s.client_id=#{t.scope.clientId} AND s.owner_jiacn=#{t.ownerJiacn}
        AND s.history_group_sha256=#{group} AND s.sample_state='READY'
        AND s.lifecycle_state='ACCEPTED' AND s.evidence_revision=s.projection_revision
        AND s.benchmark_case_id IS NULL AND s.sample_definition_version='TASK_LIFECYCLE_V1'
        """;
    String HISTORY=ELIGIBLE_HISTORY+" AND s.task_id<>#{t.taskId} ";
    record CalibrationRow(String quoteId,String agentId,long amountWen,long featuresAt,String requirementSha,String inputSha,String featureSha,String features,String execution,String operations) { }
    // One explicitly selected original task/revision. No fallback to newer evidence, another owner, or an unapproved benchmark.
    @Select("""
        SELECT q.quote_id,q.snapshot_json->>'$.binding.agentId',q.amount_wen,q.created_at,s.initial_requirement_sha256,s.initial_input_snapshot_sha256,s.initial_feature_sha256,
          JSON_EXTRACT(q.snapshot_json,'$.preparation.features'),JSON_EXTRACT(q.snapshot_json,'$.preparation.group'),r.operation_refs
        FROM economy_task_cost_sample s JOIN economy_task_cost_sample_revision r ON r.sample_id=s.sample_id
          AND r.sample_revision=s.projection_revision AND r.sample_state='READY'
        JOIN economy_task_charge ch ON ch.tenant_id=s.tenant_id AND ch.client_id=s.client_id AND ch.owner_jiacn=s.owner_jiacn
          AND ch.task_id=s.task_id AND ch.quote_id=s.quote_id
        JOIN economy_task_quote_resolved q ON q.tenant_id=ch.tenant_id AND q.client_id=ch.client_id AND q.owner_jiacn=ch.owner_jiacn
          AND q.task_id=ch.task_id AND q.quote_id=ch.quote_id AND q.payer_user_id=ch.payer_user_id WHERE
        """+ELIGIBLE_HISTORY+" AND s.task_id=#{t.taskId} AND s.sample_id=#{id} AND s.projection_revision=#{revision}")
    CalibrationRow selectedForCalibration(@Param("t") Task original,@Param("group") String group,@Param("id") String sampleId,@Param("revision") long revision);
    // Separate statement AFTER the evidence read: an observation upper bound, not an invented historical completion time.
    @Select("SELECT CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)+1") long observedByMillis();
    record HistoryRow(String id,long revision,String taskId,String featureSha,String featuresJson){}
    @Select("""
        SELECT s.sample_id,s.projection_revision,s.task_id,s.initial_feature_sha256,JSON_EXTRACT(q.snapshot_json,'$.preparation.features')
        FROM economy_task_cost_sample s LEFT JOIN economy_task_quote_resolved q ON q.tenant_id=s.tenant_id AND q.client_id=s.client_id
        AND q.owner_jiacn=s.owner_jiacn AND q.task_id=s.task_id AND q.quote_id=s.quote_id WHERE
        """+HISTORY+" AND s.sample_id>#{after} ORDER BY s.sample_id LIMIT #{limit}")
    List<HistoryRow> history(@Param("t") Task query,@Param("group") String group,@Param("after") String after,@Param("limit") int limit);
    @Select("""
        SELECT r.operation_refs FROM economy_task_cost_sample s JOIN economy_task_cost_sample_revision r
        ON r.sample_id=s.sample_id AND r.sample_revision=s.projection_revision AND r.sample_state='READY' WHERE
        """+HISTORY+" AND s.sample_id=#{id} AND s.projection_revision=#{revision}")
    String historyQuantities(@Param("t") Task query,@Param("group") String group,@Param("id") String id,@Param("revision") long revision);
    @Select("SELECT s.projection_revision FROM economy_task_cost_sample s WHERE "+HISTORY+" AND s.sample_id=#{id} AND s.projection_revision=#{revision} FOR SHARE")
    Long lockHistory(@Param("t") Task query,@Param("group") String group,@Param("id") String id,@Param("revision") long revision);
    String GRANT_SCOPE="g.tenant_id=#{t.scope.tenantId} AND g.client_id=#{t.scope.clientId} AND g.owner_jiacn=#{t.ownerJiacn} AND g.group_sha=#{p.group} AND g.generation=#{p.generation} AND g.receipt_sha=#{p.receiptSha256} ";
    String BENCHMARK_USE="""
        FROM economy_task_benchmark_use_grant g JOIN economy_task_benchmark_use_projection p
          USING(tenant_id,client_id,owner_jiacn,group_sha,generation,sample_id)
        JOIN economy_task_cost_sample s ON s.sample_id=g.sample_id AND s.tenant_id=g.tenant_id AND s.client_id=g.client_id AND s.owner_jiacn=g.owner_jiacn
          AND s.history_group_sha256=g.group_sha AND s.evidence_revision=p.evidence_revision AND s.lifecycle_state='ACCEPTED' AND s.quote_id IS NULL
        JOIN economy_task_benchmark_case b ON b.tenant_id=s.tenant_id AND b.client_id=s.client_id AND b.owner_jiacn=s.owner_jiacn AND b.task_id=s.task_id
          AND b.manifest_sha=s.sample_id AND b.benchmark_case_id=s.benchmark_case_id AND NOT b.retired
        JOIN economy_task_benchmark_review v ON v.sample_id=s.sample_id AND v.review_sha=p.review_sha AND NOT v.withdrawn
        JOIN economy_task_benchmark_closure x ON x.sample_id=s.sample_id AND x.terminal_fact_id=p.terminal_fact AND x.completed
        LEFT JOIN economy_task_benchmark_use_stop z ON z.tenant_id=g.tenant_id AND z.client_id=g.client_id AND z.owner_jiacn=g.owner_jiacn
          AND z.group_sha=g.group_sha AND z.generation=g.generation AND z.sample_id=g.sample_id
        WHERE z.sample_id IS NULL AND s.task_id<>#{t.taskId} AND NOT EXISTS(SELECT 1 FROM economy_task_charge ch WHERE ch.tenant_id=s.tenant_id
          AND ch.client_id=s.client_id AND ch.owner_jiacn=s.owner_jiacn AND ch.task_id=s.task_id) AND
        """+GRANT_SCOPE;
    @Select("SELECT g.sample_id,p.evidence_revision,s.task_id,s.initial_feature_sha256,JSON_EXTRACT(b.manifest_json,'$.features') "+BENCHMARK_USE+" AND g.sample_id>#{after} ORDER BY g.sample_id LIMIT #{limit}")
    List<HistoryRow> benchmarkPage(@Param("t") Task t,@Param("p") cn.jia.economy.task.TaskCalibrationCatalog.Pin pin,@Param("after") String after,@Param("limit") int limit);
    String EXACT=BENCHMARK_USE+" AND g.sample_id=#{id} AND p.evidence_revision=#{revision} ";
    @Select("SELECT p.operations "+EXACT) String benchmarkQuantities(@Param("t") Task t,@Param("p") cn.jia.economy.task.TaskCalibrationCatalog.Pin pin,@Param("id") String id,@Param("revision") long revision);
    @Select("SELECT g.generation FROM economy_task_benchmark_use_grant g WHERE "+GRANT_SCOPE+" AND g.sample_id=#{id} FOR SHARE")
    Long lockGrant(@Param("t") Task t,@Param("p") cn.jia.economy.task.TaskCalibrationCatalog.Pin pin,@Param("id") String id);
    @Select("SELECT p.evidence_revision "+EXACT+" FOR SHARE") Long lockBenchmark(@Param("t") Task t,@Param("p") cn.jia.economy.task.TaskCalibrationCatalog.Pin pin,@Param("id") String id,@Param("revision") long revision);
    @Select("""
        SELECT sample_id FROM economy_task_cost_sample WHERE tenant_id=#{t.scope.tenantId} AND client_id=#{t.scope.clientId}
          AND owner_jiacn=#{t.ownerJiacn} AND task_id=#{t.taskId} AND quote_id=#{quote} AND initial_feature_sha256=#{feature}
          AND history_group_sha256=#{grp} AND benchmark_case_id IS NULL AND sample_definition_version='TASK_LIFECYCLE_V1'
        """) String outcomeSample(@Param("t") Task task,@Param("quote") String quote,@Param("grp") String group,@Param("feature") String feature);
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
        JOIN economy_task_quote_resolved q ON q.tenant_id=ch.tenant_id AND q.client_id=ch.client_id AND q.owner_jiacn=ch.owner_jiacn
          AND q.task_id=ch.task_id AND q.quote_id=ch.quote_id AND q.payer_user_id=ch.payer_user_id
        LEFT JOIN economy_task_membership_receipt r ON r.tenant_id=s.tenant_id AND r.client_id=s.client_id AND r.owner_jiacn=s.owner_jiacn AND r.task_id=s.task_id
        WHERE s.sample_id=#{id} AND s.tenant_id=#{t.scope.tenantId} AND s.client_id=#{t.scope.clientId} AND s.owner_jiacn=#{t.ownerJiacn}
          AND s.task_id=#{t.taskId} AND s.sample_definition_version='TASK_LIFECYCLE_V1' AND s.benchmark_case_id IS NULL
        """) Header confirmed(@Param("t") Task task,@Param("id") String sampleId);
    // Benchmark-only source of the header. Below it, all members and responses use the same actual-use checks as confirmed tasks.
    @Select("""
        SELECT s.lifecycle_state,s.evidence_revision,r.fact_id,r.outcome,r.membership_verified,r.member_count,
          b.manifest_json->>'$.agentId',b.manifest_json->>'$.execution.dispatchGroupVersion',b.manifest_json->>'$.execution.executionConfigSha256',
          b.manifest_json->>'$.execution.localRouteSha256',b.manifest_json->>'$.execution.routeVersion',b.manifest_json->>'$.execution.toolConfigSha256',b.manifest_json->>'$.execution.capturePolicyVersion'
        FROM economy_task_cost_sample s JOIN economy_task_benchmark_case b ON b.tenant_id=s.tenant_id AND b.client_id=s.client_id
          AND b.owner_jiacn=s.owner_jiacn AND b.task_id=s.task_id AND b.benchmark_case_id=s.benchmark_case_id
          AND b.manifest_sha=s.sample_id AND SHA2(b.manifest_json,256)=b.manifest_sha AND NOT b.retired
        JOIN economy_task_benchmark_closure x ON x.sample_id=s.sample_id AND x.completed AND x.terminal_fact_id=#{fact}
        JOIN economy_task_membership_receipt r ON r.tenant_id=s.tenant_id AND r.client_id=s.client_id AND r.owner_jiacn=s.owner_jiacn AND r.task_id=s.task_id
          AND r.fact_id=x.terminal_fact_id AND r.membership_verified AND r.outcome='ACCEPTED' AND r.member_count=x.attached_count AND r.membership_revision=x.after_revision
        JOIN economy_task_benchmark_review v ON v.sample_id=s.sample_id AND v.review_sha=#{review} AND SHA2(v.review_json,256)=v.review_sha AND NOT v.withdrawn
          AND v.review_json->>'$.schema'='benchmark-review-v1' AND v.review_json->>'$.decision'='ACCEPTED'
          AND v.review_json->>'$.caseId'=b.benchmark_case_id AND v.review_json->>'$.manifestSha'=b.manifest_sha
          AND v.review_json->>'$.terminalFactId'=r.fact_id AND v.review_json->>'$.membershipSha'=r.set_digest
          AND JSON_TYPE(JSON_EXTRACT(v.review_json,'$.reviewerRef'))='STRING' AND LENGTH(TRIM(v.review_json->>'$.reviewerRef'))>0
          AND JSON_TYPE(JSON_EXTRACT(v.review_json,'$.deliverableEvidenceRef'))='STRING' AND LENGTH(TRIM(v.review_json->>'$.deliverableEvidenceRef'))>0
        WHERE s.tenant_id=#{t.scope.tenantId} AND s.client_id=#{t.scope.clientId} AND s.owner_jiacn=#{t.ownerJiacn} AND s.task_id=#{t.taskId}
          AND s.sample_id=#{sha} AND s.benchmark_case_id=#{id} AND s.quote_id IS NULL AND s.sample_definition_version='TASK_LIFECYCLE_V1' AND s.lifecycle_state='ACCEPTED'
          AND NOT EXISTS(SELECT 1 FROM economy_task_charge c WHERE c.tenant_id=s.tenant_id AND c.client_id=s.client_id AND c.owner_jiacn=s.owner_jiacn AND c.task_id=s.task_id)
        """) Header reviewedBenchmark(@Param("t") Task task,@Param("id") String id,@Param("sha") String sha,@Param("fact") String fact,@Param("review") String review);
    @Update("""
        UPDATE economy_task_cost_sample SET projection_revision=#{rev},sample_state=#{state}
        WHERE sample_id=#{id} AND tenant_id=#{t.scope.tenantId} AND client_id=#{t.scope.clientId} AND owner_jiacn=#{t.ownerJiacn} AND task_id=#{t.taskId}
          AND evidence_revision=#{rev} AND (projection_revision IS NULL OR projection_revision<>#{rev})
        """) int project(@Param("t") Task task,@Param("id") String sample,@Param("rev") long revision,@Param("state") String state);
    @Insert("""
        INSERT INTO economy_task_cost_sample_revision(sample_id,sample_revision,terminal_fact_id,sample_state,reason_code,member_count,operation_refs)
        VALUES(#{id},#{rev},#{fact},#{state},#{reason},#{count},#{refs})
        """) void append(@Param("id") String sample,@Param("rev") long revision,@Param("fact") String fact,@Param("state") String state,
                          @Param("reason") String reason,@Param("count") long count,@Param("refs") String refs);
    record Originals(String manifest,String review,long featuresAt){}
    // Called only after the scoped review, closure and measurement checks in the very same snapshot.
    @Select("""
        SELECT b.manifest_json,v.review_json,CAST(UNIX_TIMESTAMP(b.recorded_at)*1000 AS UNSIGNED)
        FROM economy_task_benchmark_case b JOIN economy_task_benchmark_review v ON v.sample_id=b.manifest_sha
        WHERE b.tenant_id=#{t.scope.tenantId} AND b.client_id=#{t.scope.clientId} AND b.owner_jiacn=#{t.ownerJiacn} AND b.task_id=#{t.taskId}
          AND b.benchmark_case_id=#{id} AND b.manifest_sha=#{sha} AND v.review_sha=#{review} AND NOT b.retired AND NOT v.withdrawn
        """) Originals benchmarkOriginals(@Param("t") Task task,@Param("id") String id,@Param("sha") String sha,@Param("review") String review);
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
