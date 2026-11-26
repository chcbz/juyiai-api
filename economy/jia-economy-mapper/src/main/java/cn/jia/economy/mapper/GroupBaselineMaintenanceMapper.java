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
    /** All source identity comes from original maintenance approval bytes, not from the quoting actor. */
    record Member(String tenant,String client,String groupSha,String policySha,String sourceOwner,String sourceTask,String caseId,
        String sampleId,long evidenceRevision,String manifestSha,String reviewSha,String approvalSha) {}
    String MKEY="tenant_id=#{tenant} AND client_id=#{client} AND group_sha=#{groupSha}";
    @Select("SELECT authority_json FROM economy_budget_publish_approval WHERE approval_sha=#{sha} AND NOT withdrawn FOR SHARE") String approvedPublication(String sha);
    @Select("SELECT manifest_sha FROM economy_task_benchmark_case WHERE tenant_id=#{tenant} AND client_id=#{client} AND owner_jiacn=#{sourceOwner} AND task_id=#{sourceTask} AND benchmark_case_id=#{caseId} AND manifest_sha=#{manifestSha} AND SHA2(manifest_json,256)=manifest_sha AND NOT retired FOR SHARE") String lockCase(Member member);
    @Select("SELECT review_sha FROM economy_task_benchmark_review WHERE sample_id=#{sampleId} AND review_sha=#{reviewSha} AND SHA2(review_json,256)=review_sha AND NOT withdrawn AND review_json->>'$.decision'='ACCEPTED' AND review_json->>'$.manifestSha'=#{manifestSha} AND review_json->>'$.caseId'=#{caseId} FOR SHARE") String lockReview(Member member);
    @Select("SELECT evidence_revision FROM economy_task_cost_sample WHERE sample_id=#{sampleId} AND tenant_id=#{tenant} AND client_id=#{client} AND owner_jiacn=#{sourceOwner} AND task_id=#{sourceTask} AND benchmark_case_id=#{caseId} AND quote_id IS NULL AND lifecycle_state='ACCEPTED' AND sample_definition_version='TASK_LIFECYCLE_V1' FOR SHARE") Long lockSample(Member member);
    @Insert("INSERT INTO economy_budget_domain_epoch(tenant_id,client_id,book_epoch) VALUES(#{tenant},#{client},1) ON DUPLICATE KEY UPDATE book_epoch=book_epoch") int ensureDomain(Member member);
    record MemberHead(String status,String policySha){}
    @Select("SELECT status,policy_sha FROM economy_budget_group_head WHERE "+MKEY+" FOR UPDATE") MemberHead lockMemberHead(Member member);
    @Select("SELECT publish_approval_sha FROM economy_budget_group_member WHERE "+MKEY+" AND sample_id=#{sampleId} AND enabled AND evidence_revision=#{evidenceRevision} AND manifest_sha=#{manifestSha} AND review_sha=#{reviewSha} AND source_owner=#{sourceOwner} AND source_task_id=#{sourceTask} FOR UPDATE") String existingMember(Member member);
    @Insert("INSERT INTO economy_budget_group_head(tenant_id,client_id,group_sha,generation,status,book_epoch,attempt,next_at,policy_sha,updated_at) SELECT #{tenant},#{client},#{groupSha},1,'DIRTY',book_epoch,0,CURRENT_TIMESTAMP(3),#{policySha},CURRENT_TIMESTAMP(3) FROM economy_budget_domain_epoch WHERE tenant_id=#{tenant} AND client_id=#{client}") int firstGroup(Member member);
    @Update("UPDATE economy_budget_group_head SET status='DIRTY',generation=generation+1,active_revision=NULL,tariff_sha=NULL,claim_id=NULL,lease_until=NULL,attempt=0,next_at=CURRENT_TIMESTAMP(3),updated_at=CURRENT_TIMESTAMP(3) WHERE "+MKEY+" AND status<>'RETIRED'") int dirtyGroup(Member member);
    @Insert("INSERT INTO economy_budget_group_member(tenant_id,client_id,group_sha,sample_id,source_owner,source_task_id,evidence_revision,manifest_sha,review_sha,publish_approval_sha,enabled,updated_at) VALUES(#{tenant},#{client},#{groupSha},#{sampleId},#{sourceOwner},#{sourceTask},#{evidenceRevision},#{manifestSha},#{reviewSha},#{approvalSha},TRUE,CURRENT_TIMESTAMP(3)) ON DUPLICATE KEY UPDATE evidence_revision=#{evidenceRevision},review_sha=#{reviewSha},publish_approval_sha=#{approvalSha},enabled=TRUE,updated_at=CURRENT_TIMESTAMP(3)") int registerMember(Member member);
}
