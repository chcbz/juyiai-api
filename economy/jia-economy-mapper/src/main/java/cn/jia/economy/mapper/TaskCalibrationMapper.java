package cn.jia.economy.mapper;

import cn.jia.economy.task.TaskBillingService.Task;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface TaskCalibrationMapper {
    String SCOPE="c.tenant_id=#{t.scope.tenantId} AND c.client_id=#{t.scope.clientId} AND c.owner_jiacn=#{t.ownerJiacn} AND c.group_sha=#{g}";
    record Row(long generation,String sha,String json){}
    @Select("""
        SELECT c.generation,c.receipt_sha,e.receipt_json FROM economy_task_estimator_current c
        JOIN economy_task_estimator_evidence e ON e.tenant_id=c.tenant_id AND e.client_id=c.client_id AND e.owner_jiacn=c.owner_jiacn
        AND e.group_sha=c.group_sha AND e.receipt_sha=c.receipt_sha WHERE
        """+SCOPE) Row active(@Param("t") Task task,@Param("g") String group);
    @Select("SELECT c.generation FROM economy_task_estimator_current c WHERE "+SCOPE+" AND c.generation=#{generation} AND c.receipt_sha=#{sha} FOR SHARE")
    Long lockCurrent(@Param("t") Task task,@Param("g") String group,@Param("generation") long generation,@Param("sha") String sha);
}
