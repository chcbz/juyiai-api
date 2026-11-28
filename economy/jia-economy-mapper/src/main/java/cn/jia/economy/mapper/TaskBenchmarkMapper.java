package cn.jia.economy.mapper;

import cn.jia.economy.task.TaskBillingService.Task;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;

public interface TaskBenchmarkMapper {
    String SCOPE="tenant_id=#{t.scope.tenantId} AND client_id=#{t.scope.clientId} AND owner_jiacn=#{t.ownerJiacn} AND task_id=#{t.taskId}";
    // No approval writer in application code. Caller serializes enrollment on its task root; fence retirement of the exact authority row.
    @Select("SELECT manifest_json FROM economy_task_benchmark_case WHERE "+SCOPE+" AND benchmark_case_id=#{id} AND retired=FALSE AND manifest_sha=#{sha} FOR SHARE")
    String approved(@Param("t") Task task,@Param("id") String id,@Param("sha") String sha);
    record ScopedCase(String manifestJson,String manifestSha,String benchmarkCaseId,boolean retired) {}
    @Select("SELECT manifest_json,manifest_sha,benchmark_case_id,retired FROM economy_task_benchmark_case WHERE "+SCOPE+" FOR SHARE")
    ScopedCase scopedCase(@Param("t") Task task);
    @Select("SELECT sample_id FROM economy_task_cost_sample WHERE "+SCOPE+" AND benchmark_case_id=#{id} AND quote_id IS NULL AND sample_definition_version='TASK_LIFECYCLE_V1' FOR UPDATE")
    String existing(@Param("t") Task task,@Param("id") String id);
    // Refuse retrospective enrollment of an already measured/closed/charged business task. Agent also fences admission at source.
    @Insert("INSERT INTO economy_task_cost_sample(sample_id,tenant_id,client_id,owner_jiacn,task_id,benchmark_case_id,sample_definition_version,initial_requirement_sha256,initial_input_snapshot_sha256,initial_feature_sha256,history_group_sha256,lifecycle_state,evidence_revision,sample_state) "
        +"SELECT #{sha},#{t.scope.tenantId},#{t.scope.clientId},#{t.ownerJiacn},#{t.taskId},#{id},'TASK_LIFECYCLE_V1',#{req},#{input},#{features},#{grp},'ACTIVE',1,'WAITING_EVIDENCE' FROM DUAL WHERE "
        +"NOT EXISTS(SELECT 1 FROM economy_task_membership_receipt WHERE "+SCOPE+") AND NOT EXISTS(SELECT 1 FROM economy_task_charge WHERE "+SCOPE+") AND NOT EXISTS(SELECT 1 FROM economy_usage_capture WHERE "+SCOPE+")")
    int seed(@Param("t") Task task,@Param("id") String id,@Param("sha") String sha,@Param("req") String req,@Param("input") String input,@Param("features") String features,@Param("grp") String group);

    @Select("SELECT COUNT(*) FROM economy_task_cost_sample WHERE "+SCOPE)
    int samples(@Param("t") Task task);
    @Select("SELECT (EXISTS(SELECT 1 FROM economy_task_membership_receipt WHERE "+SCOPE+") OR EXISTS(SELECT 1 FROM economy_usage_capture WHERE "+SCOPE+"))")
    boolean hasEvidence(@Param("t") Task task);

    // Called only after the same transaction's new wallet preoccupation succeeded. No second ordinary
    // sample and no retrospective UPDATE to relabel an existing task as an approved benchmark.
    @Insert("""
        INSERT INTO economy_task_cost_sample(sample_id,tenant_id,client_id,owner_jiacn,task_id,quote_id,benchmark_case_id,
          sample_definition_version,initial_requirement_sha256,initial_input_snapshot_sha256,initial_feature_sha256,
          history_group_sha256,scope_sha256,scope_shape_sha256,scoped_group_sha256,scoped_policy_sha256,
          lifecycle_state,evidence_revision,sample_state)
        SELECT #{sample},q.tenant_id,q.client_id,q.owner_jiacn,q.task_id,q.quote_id,#{m.caseId},'TASK_LIFECYCLE_V1',
          #{m.scopePin.requirementSha256},#{m.scopePin.inputSnapshotDigest},#{feature},NULL,
          #{m.scopePin.scopeSha256},#{shape},#{m.groupSha},#{policy},'ACTIVE',1,'WAITING_EVIDENCE'
        FROM economy_task_quote_resolved q
        JOIN economy_task_charge c ON c.tenant_id=q.tenant_id AND c.client_id=q.client_id AND c.owner_jiacn=q.owner_jiacn
          AND c.task_id=q.task_id AND c.quote_id=q.quote_id AND c.payer_user_id=q.payer_user_id AND c.state='HELD'
        JOIN economy_task_benchmark_case b ON b.tenant_id=q.tenant_id AND b.client_id=q.client_id AND b.owner_jiacn=q.owner_jiacn
          AND b.task_id=q.task_id AND b.benchmark_case_id=#{m.caseId} AND b.manifest_sha=#{sha}
          AND SHA2(b.manifest_json,256)=b.manifest_sha AND NOT b.retired
        WHERE q.tenant_id=#{m.task.scope.tenantId} AND q.client_id=#{m.task.scope.clientId} AND q.owner_jiacn=#{m.task.ownerJiacn}
          AND q.task_id=#{m.task.taskId} AND q.quote_id=#{quote}
          AND q.snapshot_json->>'$.schema'='SCOPED_FIXED_TASK_QUOTE_V1'
          AND q.snapshot_json->>'$.scopePin.scopeSha256'=#{m.scopePin.scopeSha256}
          AND q.snapshot_json->>'$.binding.agentId'=#{m.agentId}
          AND NOT EXISTS(SELECT 1 FROM economy_task_membership_receipt r WHERE r.tenant_id=q.tenant_id AND r.client_id=q.client_id AND r.owner_jiacn=q.owner_jiacn AND r.task_id=q.task_id)
          AND NOT EXISTS(SELECT 1 FROM economy_usage_capture r WHERE r.tenant_id=q.tenant_id AND r.client_id=q.client_id AND r.owner_jiacn=q.owner_jiacn AND r.task_id=q.task_id)
        """)
    int seedScopedPaid(@Param("m") cn.jia.economy.task.ScopedBenchmarkManifest manifest,@Param("sha") String manifestSha,
            @Param("quote") String quoteId,@Param("sample") String sampleId,@Param("feature") String featureSha,
            @Param("shape") String shapeSha,@Param("policy") String policySha);

    @Insert("""
        INSERT INTO economy_task_cost_sample(sample_id,tenant_id,client_id,owner_jiacn,task_id,benchmark_case_id,
          sample_definition_version,initial_requirement_sha256,initial_input_snapshot_sha256,initial_feature_sha256,
          history_group_sha256,scope_sha256,scope_shape_sha256,scoped_group_sha256,scoped_policy_sha256,
          lifecycle_state,evidence_revision,sample_state)
        VALUES(#{sha},#{m.task.scope.tenantId},#{m.task.scope.clientId},#{m.task.ownerJiacn},#{m.task.taskId},#{m.caseId},
          'TASK_LIFECYCLE_V1',#{m.scopePin.requirementSha256},#{m.scopePin.inputSnapshotDigest},#{feature},NULL,
          #{m.scopePin.scopeSha256},#{shape},#{m.groupSha},#{m.policy.sha},'ACTIVE',1,'WAITING_EVIDENCE')
        """)
    int seedScopedSponsored(@Param("m") cn.jia.economy.task.ScopedBenchmarkManifest manifest,@Param("sha") String manifestSha,
            @Param("feature") String featureSha,@Param("shape") String shapeSha);

    record FundingRow(String authorityJson, String authoritySha, String state, boolean withdrawn) {}
    @Select("SELECT authority_json,authority_sha,state,withdrawn FROM economy_task_benchmark_sponsorship WHERE " + SCOPE + " FOR UPDATE")
    FundingRow funding(@Param("t") Task task);
    @Select("SELECT COUNT(*) FROM economy_task_charge WHERE " + SCOPE)
    int charges(@Param("t") Task task);
    @Update("UPDATE economy_task_benchmark_sponsorship SET state=#{next},updated_at=UTC_TIMESTAMP(3) WHERE " + SCOPE
        + " AND authority_sha=#{sha} AND state=#{previous}")
    int transitionFunding(@Param("t") Task task, @Param("sha") String sha,
            @Param("previous") String previous, @Param("next") String next);
}
