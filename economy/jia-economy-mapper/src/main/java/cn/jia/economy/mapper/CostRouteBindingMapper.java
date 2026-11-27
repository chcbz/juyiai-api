package cn.jia.economy.mapper;

import java.util.List;
import org.apache.ibatis.annotations.*;

/** Exact domain/hash keys under maintenance-verified utf8mb4_0900_bin; keep key predicates indexable. Business validation and hashes belong to Economy service. */
public interface CostRouteBindingMapper {
    record Row(String tenantId,String clientId,String referenceSha,long revision,String referenceJson,String routeJson,String bindingSha) {}
    @Select("""
        <script>SELECT r.tenant_id,r.client_id,r.reference_sha,r.revision,r.reference_json,r.route_json,r.binding_sha
        FROM economy_cost_route_head h JOIN economy_cost_route_revision r
          ON r.tenant_id=h.tenant_id AND r.client_id=h.client_id AND r.reference_sha=h.reference_sha AND r.revision=h.revision
        WHERE h.tenant_id=#{tenant} AND h.client_id=#{client}
          AND h.reference_sha IN <foreach collection='keys' item='key' open='(' separator=',' close=')'>#{key}</foreach>
        ORDER BY h.reference_sha <if test='lock'>FOR SHARE</if></script>
        """)
    List<Row> read(@Param("tenant")String tenant,@Param("client")String client,@Param("keys")List<String> keys,@Param("lock")boolean lock);
    @Select("SELECT revision FROM economy_cost_route_head WHERE tenant_id=#{tenant} AND client_id=#{client} AND reference_sha=#{key} FOR UPDATE")
    Long lockHead(@Param("tenant")String tenant,@Param("client")String client,@Param("key")String key);
    @Insert("INSERT INTO economy_cost_route_revision(tenant_id,client_id,reference_sha,revision,reference_json,route_json,binding_sha) VALUES(#{tenantId},#{clientId},#{referenceSha},#{revision},#{referenceJson},#{routeJson},#{bindingSha})")
    int insertRevision(Row row);
    @Insert("INSERT INTO economy_cost_route_head(tenant_id,client_id,reference_sha,revision) VALUES(#{tenantId},#{clientId},#{referenceSha},#{revision})")
    int insertHead(Row row);
    @Update("UPDATE economy_cost_route_head SET revision=#{row.revision} WHERE tenant_id=#{row.tenantId} AND client_id=#{row.clientId} AND reference_sha=#{row.referenceSha} AND revision=#{expected}")
    int advance(@Param("row")Row row,@Param("expected")long expected);
}
