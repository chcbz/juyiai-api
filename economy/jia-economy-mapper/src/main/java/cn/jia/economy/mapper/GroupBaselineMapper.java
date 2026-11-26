package cn.jia.economy.mapper;

import cn.jia.economy.service.EconomyScope;
import java.util.List;
import java.math.BigDecimal;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Online lookup accesses published aggregates only, never the private sample roster. */
public interface GroupBaselineMapper {
    record Row(String groupSha,long bookEpoch,long generation,String status,Long activeRevision,String tariffSha,String policySha,
               Long revision,Long revisionEpoch,String revisionTariff,String revisionPolicy,Integer sampleCount,BigDecimal sumCostYuan) {}
    record Head(String groupSha,long bookEpoch,long generation,String status,Long activeRevision,String tariffSha,String policySha) {}
    String KEYS = " WHERE h.tenant_id=#{d.tenantId} AND h.client_id=#{d.clientId} AND h.group_sha IN " +
        "<foreach collection='keys' item='key' open='(' separator=',' close=')'>#{key}</foreach> ORDER BY h.group_sha ";
    String HEAD = "SELECT h.group_sha,h.book_epoch,h.generation,h.status,h.active_revision,h.tariff_sha,h.policy_sha";
    @Select("<script>" + HEAD + ",r.revision,r.book_epoch AS revision_epoch,r.tariff_sha AS revision_tariff,r.policy_sha AS revision_policy,r.sample_count,r.sum_cost_yuan " +
        "FROM economy_budget_group_head h LEFT JOIN economy_budget_group_revision r ON r.tenant_id=h.tenant_id AND r.client_id=h.client_id " +
        "AND r.group_sha=h.group_sha AND r.revision=h.active_revision" + KEYS + "</script>")
    List<Row> read(@Param("d") EconomyScope domain,@Param("keys") List<String> keys);
    @Select("<script>" + HEAD + " FROM economy_budget_group_head h" + KEYS + "FOR SHARE</script>")
    List<Head> lock(@Param("d") EconomyScope domain,@Param("keys") List<String> keys);
}
