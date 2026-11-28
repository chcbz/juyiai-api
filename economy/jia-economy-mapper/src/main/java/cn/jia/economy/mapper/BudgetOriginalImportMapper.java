package cn.jia.economy.mapper;

import cn.jia.economy.task.ScopedBenchmarkManifest;
import org.apache.ibatis.annotations.*;

/** Used only by the isolated, operator-authorized original importer. No update, delete or activation. */
public interface BudgetOriginalImportMapper {
    record Original(String body,String sha,boolean withdrawn) {}
    @Select("SELECT authority_json AS body,approval_sha AS sha,withdrawn FROM economy_budget_publish_approval WHERE approval_sha=#{sha} FOR UPDATE")
    Original approval(String sha);
    @Insert("INSERT INTO economy_budget_publish_approval(approval_sha,authority_json) VALUES(#{sha},#{body})")
    int insertApproval(@Param("sha") String sha,@Param("body") String body);

    @Select("SELECT manifest_json AS body,manifest_sha AS sha,retired AS withdrawn FROM economy_task_benchmark_case WHERE tenant_id=#{m.task.scope.tenantId} AND client_id=#{m.task.scope.clientId} AND benchmark_case_id=#{m.caseId} FOR UPDATE")
    Original benchmark(@Param("m") ScopedBenchmarkManifest m);
    @Select("SELECT manifest_json AS body,manifest_sha AS sha,retired AS withdrawn FROM economy_task_benchmark_case WHERE tenant_id=#{m.task.scope.tenantId} AND client_id=#{m.task.scope.clientId} AND owner_jiacn=#{m.task.ownerJiacn} AND task_id=#{m.task.taskId} FOR UPDATE")
    Original taskBenchmark(@Param("m") ScopedBenchmarkManifest m);
    @Insert("INSERT INTO economy_task_benchmark_case(tenant_id,client_id,owner_jiacn,task_id,benchmark_case_id,manifest_json,manifest_sha) VALUES(#{m.task.scope.tenantId},#{m.task.scope.clientId},#{m.task.ownerJiacn},#{m.task.taskId},#{m.caseId},#{body},#{sha})")
    int insertBenchmark(@Param("m") ScopedBenchmarkManifest manifest,@Param("sha") String sha,@Param("body") String body);

    @Select("SELECT manifest_json AS body,manifest_sha AS sha,retired AS withdrawn FROM economy_task_benchmark_case WHERE tenant_id=#{s.tenantId} AND client_id=#{s.clientId} AND benchmark_case_id=#{caseId} FOR SHARE")
    Original sourceCase(@Param("s") cn.jia.economy.service.EconomyScope scope,@Param("caseId") String caseId);
    @Select("SELECT authority_json AS body,authority_sha AS sha,withdrawn FROM economy_task_benchmark_sponsorship WHERE "+TaskBenchmarkMapper.SCOPE+" FOR UPDATE")
    Original sponsorship(@Param("t") cn.jia.economy.task.TaskBillingService.Task task);
    @Select("SELECT (EXISTS(SELECT 1 FROM economy_task_charge WHERE "+TaskBenchmarkMapper.SCOPE+") OR EXISTS(SELECT 1 FROM economy_task_cost_sample WHERE "+TaskBenchmarkMapper.SCOPE+") OR EXISTS(SELECT 1 FROM economy_task_membership_receipt WHERE "+TaskBenchmarkMapper.SCOPE+") OR EXISTS(SELECT 1 FROM economy_usage_capture WHERE "+TaskBenchmarkMapper.SCOPE+"))")
    boolean hasActivity(@Param("t") cn.jia.economy.task.TaskBillingService.Task task);
    @Insert("INSERT INTO economy_task_benchmark_sponsorship(tenant_id,client_id,owner_jiacn,task_id,authority_json,authority_sha) VALUES(#{t.scope.tenantId},#{t.scope.clientId},#{t.ownerJiacn},#{t.taskId},#{body},#{sha})")
    int insertSponsorship(@Param("t") cn.jia.economy.task.TaskBillingService.Task task,@Param("sha") String sha,@Param("body") String body);
    @Select("""
        SELECT s.sample_id FROM economy_task_cost_sample s
        JOIN economy_task_benchmark_closure x ON x.sample_id=s.sample_id AND x.completed AND x.terminal_fact_id=#{r.terminalFactId}
        JOIN economy_task_membership_receipt m ON m.tenant_id=s.tenant_id AND m.client_id=s.client_id
          AND m.owner_jiacn=s.owner_jiacn AND m.task_id=s.task_id AND m.fact_id=x.terminal_fact_id
          AND m.membership_verified AND m.outcome='ACCEPTED' AND m.member_count=x.attached_count
          AND m.membership_revision=x.after_revision AND m.set_digest=#{r.membershipSha}
        WHERE s.tenant_id=#{t.scope.tenantId} AND s.client_id=#{t.scope.clientId} AND s.owner_jiacn=#{t.ownerJiacn}
          AND s.task_id=#{t.taskId} AND s.benchmark_case_id=#{r.caseId}
          AND s.sample_definition_version='TASK_LIFECYCLE_V1' AND s.lifecycle_state='ACCEPTED'
        FOR SHARE
        """)
    String reviewedSample(@Param("t") cn.jia.economy.task.TaskBillingService.Task task,@Param("r") cn.jia.economy.usage.BenchmarkSampleEvidence.Review review);
    @Select("SELECT review_json AS body,review_sha AS sha,withdrawn FROM economy_task_benchmark_review WHERE sample_id=#{sample} AND review_sha=#{sha} FOR UPDATE")
    Original review(@Param("sample") String sample,@Param("sha") String sha);
    @Insert("INSERT INTO economy_task_benchmark_review(sample_id,review_sha,review_json) VALUES(#{sample},#{sha},#{body})")
    int insertReview(@Param("sample") String sample,@Param("sha") String sha,@Param("body") String body);
}
