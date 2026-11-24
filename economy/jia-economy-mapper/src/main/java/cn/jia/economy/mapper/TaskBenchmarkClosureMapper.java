package cn.jia.economy.mapper;

import cn.jia.economy.task.TaskBillingService.Task;
import cn.jia.economy.usage.TaskMembership.Terminal;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface TaskBenchmarkClosureMapper {
    // Lock only this enrolled private case and its live approval, never the whole Agent task or a quote.
    @Select("""
        SELECT s.sample_id FROM economy_task_cost_sample s JOIN economy_task_benchmark_case b
          ON b.tenant_id=s.tenant_id AND b.client_id=s.client_id AND b.owner_jiacn=s.owner_jiacn AND b.task_id=s.task_id
          AND b.benchmark_case_id=s.benchmark_case_id AND b.manifest_sha=s.sample_id AND SHA2(b.manifest_json,256)=b.manifest_sha
        WHERE s.tenant_id=#{t.scope.tenantId} AND s.client_id=#{t.scope.clientId} AND s.owner_jiacn=#{t.ownerJiacn}
          AND s.task_id=#{t.taskId} AND s.sample_id=#{sha} AND s.benchmark_case_id=#{id} AND b.retired=FALSE
          AND s.quote_id IS NULL AND s.sample_definition_version='TASK_LIFECYCLE_V1'
          AND NOT EXISTS(SELECT 1 FROM economy_task_charge c WHERE c.tenant_id=s.tenant_id AND c.client_id=s.client_id AND c.owner_jiacn=s.owner_jiacn AND c.task_id=s.task_id)
        FOR UPDATE OF s FOR SHARE OF b
        """) String admitted(@Param("t") Task t,@Param("id") String id,@Param("sha") String sha);
    @Select("""
        SELECT tenant_id,client_id,owner_jiacn,task_id,fact_id,outcome,terminal_revision,closed_at,membership_revision,member_count,set_digest
        FROM economy_task_membership_receipt WHERE tenant_id=#{t.scope.tenantId} AND client_id=#{t.scope.clientId} AND owner_jiacn=#{t.ownerJiacn}
          AND task_id=#{t.taskId} AND fact_id=#{fact} AND membership_verified=TRUE AND received_revision=membership_revision
          AND received_count=member_count AND received_digest=set_digest
        """) Terminal verified(@Param("t") Task t,@Param("fact") String fact);
    @Insert("INSERT INTO economy_task_benchmark_closure VALUES(#{sha},#{fact},0,0,FALSE) ON DUPLICATE KEY UPDATE sample_id=sample_id")
    void start(@Param("sha") String sha,@Param("fact") String fact);
    record Cursor(String fact,long afterRevision,long count,boolean complete){}
    @Select("SELECT terminal_fact_id,after_revision,attached_count,completed FROM economy_task_benchmark_closure WHERE sample_id=#{sha} FOR UPDATE") Cursor lock(String sha);
    @Select("SELECT membership_revision FROM economy_task_membership_received_run WHERE tenant_id=#{t.tenant} AND client_id=#{t.client} AND fact_id=#{t.fact} AND membership_revision>#{after} ORDER BY membership_revision LIMIT #{limit}")
    java.util.List<Long> revisions(@Param("t") Terminal terminal,@Param("after") long after,@Param("limit") int limit);
    @Update("UPDATE economy_task_benchmark_closure SET after_revision=#{after},attached_count=#{count},completed=#{done} WHERE sample_id=#{sha} AND terminal_fact_id=#{fact} AND after_revision=#{before} AND completed=FALSE")
    int advance(@Param("sha") String sha,@Param("fact") String fact,@Param("before") long before,@Param("after") long after,@Param("count") long count,@Param("done") boolean done);
}
