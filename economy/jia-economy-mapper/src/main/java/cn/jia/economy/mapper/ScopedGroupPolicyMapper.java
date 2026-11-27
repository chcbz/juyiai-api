package cn.jia.economy.mapper;

import java.util.List;
import org.apache.ibatis.annotations.*;

/** Indexed study selection only; never reads group members or historical usage online. */
public interface ScopedGroupPolicyMapper {
    record Row(String tenantId,String clientId,String inputSha,String groupSha,String approvalSha,
               String body,Long byteLength,Boolean withdrawn) {}
    String BODY="SUBSTRING(a.authority_json,1,1048577) AS body,OCTET_LENGTH(a.authority_json) AS byte_length,a.withdrawn";
    @Select("<script>SELECT s.tenant_id,s.client_id,s.scope_input_sha AS input_sha,s.group_sha,s.approval_sha,"+BODY+
        " FROM economy_budget_group_study s FORCE INDEX (study_by_scope_input) LEFT JOIN economy_budget_publish_approval a USING(approval_sha)"+
        " WHERE s.tenant_id=#{tenant} AND s.client_id=#{client} AND s.scope_input_sha IN"+
        " <foreach collection='keys' item='k' open='(' close=')' separator=','>#{k}</foreach>"+
        " ORDER BY s.scope_input_sha <if test='lock'>FOR SHARE NOWAIT</if></script>")
    List<Row> read(@Param("tenant") String tenant,@Param("client") String client,@Param("keys") List<String> keys,@Param("lock") boolean lock);
    @Select("SELECT NULL AS tenant_id,NULL AS client_id,NULL AS input_sha,NULL AS group_sha,a.approval_sha,"+BODY+
        " FROM economy_budget_publish_approval a WHERE a.approval_sha=#{sha} FOR SHARE")
    Row approved(String sha);
    @Select("SELECT approval_sha FROM economy_budget_group_study WHERE tenant_id=#{tenant} AND client_id=#{client}"+
        " AND scope_input_sha=#{input} FOR UPDATE")
    String lockSelection(@Param("tenant") String tenant,@Param("client") String client,@Param("input") String input);
    @Delete("DELETE FROM economy_budget_group_study WHERE tenant_id=#{tenant} AND client_id=#{client}"+
        " AND scope_input_sha=#{input} AND approval_sha=#{expected}")
    int remove(@Param("tenant") String tenant,@Param("client") String client,@Param("input") String input,@Param("expected") String expected);
    @Insert("INSERT INTO economy_budget_group_study(tenant_id,client_id,group_sha,approval_sha,scope_input_sha)"+
        " VALUES(#{tenantId},#{clientId},#{groupSha},#{approvalSha},#{inputSha})")
    int insert(Row row);
}
