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
    @Select("SELECT sample_id FROM economy_task_cost_sample WHERE "+SCOPE+" AND benchmark_case_id=#{id} AND quote_id IS NULL AND sample_definition_version='TASK_LIFECYCLE_V1' FOR UPDATE")
    String existing(@Param("t") Task task,@Param("id") String id);
    // Refuse retrospective enrollment of an already measured/closed/charged business task. Agent also fences admission at source.
    @Insert("INSERT INTO economy_task_cost_sample(sample_id,tenant_id,client_id,owner_jiacn,task_id,benchmark_case_id,sample_definition_version,initial_requirement_sha256,initial_input_snapshot_sha256,initial_feature_sha256,history_group_sha256,lifecycle_state,evidence_revision,sample_state) "
        +"SELECT #{sha},#{t.scope.tenantId},#{t.scope.clientId},#{t.ownerJiacn},#{t.taskId},#{id},'TASK_LIFECYCLE_V1',#{req},#{input},#{features},#{grp},'ACTIVE',1,'WAITING_EVIDENCE' FROM DUAL WHERE "
        +"NOT EXISTS(SELECT 1 FROM economy_task_membership_receipt WHERE "+SCOPE+") AND NOT EXISTS(SELECT 1 FROM economy_task_charge WHERE "+SCOPE+") AND NOT EXISTS(SELECT 1 FROM economy_usage_capture WHERE "+SCOPE+")")
    int seed(@Param("t") Task task,@Param("id") String id,@Param("sha") String sha,@Param("req") String req,@Param("input") String input,@Param("features") String features,@Param("grp") String group);

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
