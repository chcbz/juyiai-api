package cn.jia.economy.mapper;

import cn.jia.economy.service.EconomyScope;
import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.annotations.*;

/** Restricted to offline maintenance: never injected into the online pricing service. Times use database UTC. */
public interface GroupBaselineMaintenanceMapper {
    record Work(String tenantId,String clientId,String groupSha,long generation,long bookEpoch,String policySha,int attempt) {}
    String ID="tenant_id=#{w.tenantId} AND client_id=#{w.clientId} AND group_sha=#{w.groupSha}";
    String VERSION=ID+" AND generation=#{w.generation} AND book_epoch=#{w.bookEpoch} AND policy_sha=#{w.policySha}";
    String OWN=VERSION+" AND status='BUILDING' AND claim_id=#{claim} AND lease_until>CURRENT_TIMESTAMP(3)";
    String DUE="((status IN ('DIRTY','RETRY') AND next_at<=CURRENT_TIMESTAMP(3)) OR (status='BUILDING' AND lease_until<=CURRENT_TIMESTAMP(3)))";
    @Select("SELECT tenant_id,client_id,group_sha,generation,book_epoch,policy_sha,attempt FROM economy_budget_group_head WHERE "+DUE+
        " ORDER BY COALESCE(next_at,lease_until),tenant_id,client_id,group_sha LIMIT #{limit}") List<Work> due(int limit);
    @Update("UPDATE economy_budget_group_head SET status='BUILDING',claim_id=#{claim},lease_until=TIMESTAMPADD(SECOND,#{seconds},CURRENT_TIMESTAMP(3)),"+
        "next_at=NULL,attempt=attempt+1,updated_at=CURRENT_TIMESTAMP(3) WHERE "+VERSION+" AND attempt=#{w.attempt} AND "+DUE)
    int claim(@Param("w") Work w,@Param("claim") String claim,@Param("seconds") int seconds);
    @Update("UPDATE economy_budget_group_head SET lease_until=TIMESTAMPADD(SECOND,#{seconds},CURRENT_TIMESTAMP(3)),updated_at=CURRENT_TIMESTAMP(3) WHERE "+OWN)
    int renew(@Param("w") Work w,@Param("claim") String claim,@Param("seconds") int seconds);
    @Update("UPDATE economy_budget_group_head SET status='WAIT_INPUT',claim_id=NULL,lease_until=NULL,next_at=NULL,updated_at=CURRENT_TIMESTAMP(3) WHERE "+OWN)
    int waitForInput(@Param("w") Work w,@Param("claim") String claim);
    @Update("UPDATE economy_budget_group_head SET status='RETRY',claim_id=NULL,lease_until=NULL,next_at=TIMESTAMPADD(SECOND,#{delay},CURRENT_TIMESTAMP(3)),updated_at=CURRENT_TIMESTAMP(3) WHERE "+OWN)
    int retry(@Param("w") Work w,@Param("claim") String claim,@Param("delay") int delay);
    @Select("SELECT book_epoch FROM economy_budget_domain_epoch WHERE tenant_id=#{tenantId} AND client_id=#{clientId}") Long readDomainEpoch(EconomyScope domain);
    @Select("SELECT book_epoch FROM economy_budget_domain_epoch WHERE tenant_id=#{tenantId} AND client_id=#{clientId} FOR SHARE") Long lockDomain(EconomyScope domain);
    @Select("SELECT generation FROM economy_budget_group_head WHERE "+OWN+" FOR UPDATE") Long lockOwned(@Param("w") Work w,@Param("claim") String claim);
    @Insert("INSERT INTO economy_budget_group_revision(tenant_id,client_id,group_sha,revision,book_epoch,tariff_sha,policy_sha,sample_count,sum_cost_yuan,evidence_sha,built_at) VALUES " +
        "(#{w.tenantId},#{w.clientId},#{w.groupSha},#{w.generation},#{w.bookEpoch},#{tariff},#{w.policySha},#{n},#{total},#{evidence},CURRENT_TIMESTAMP(3))")
    int insertRevision(@Param("w") Work w,@Param("tariff") String tariff,@Param("n") int count,@Param("total") BigDecimal total,@Param("evidence") String evidence);
    @Insert("INSERT INTO economy_budget_group_build_evidence(tenant_id,client_id,group_sha,revision,roster_json,evidence_sha) VALUES " +
        "(#{w.tenantId},#{w.clientId},#{w.groupSha},#{w.generation},#{json},#{evidence})")
    int insertEvidence(@Param("w") Work w,@Param("json") String json,@Param("evidence") String evidence);
    @Update("UPDATE economy_budget_group_head SET active_revision=generation,status='ACTIVE',tariff_sha=#{tariff},claim_id=NULL,lease_until=NULL,next_at=NULL,updated_at=CURRENT_TIMESTAMP(3) WHERE "+OWN)
    int publish(@Param("w") Work w,@Param("claim") String claim,@Param("tariff") String tariff);
}
