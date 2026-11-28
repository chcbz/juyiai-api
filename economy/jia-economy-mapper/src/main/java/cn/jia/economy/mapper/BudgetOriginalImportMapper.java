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
}
