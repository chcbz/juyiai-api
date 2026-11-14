package cn.jia.economy.mapper;

import cn.jia.economy.entity.EconomyHostingLeaseEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import java.util.List;

/** All discoveries partition before ordering/limit. The lease root fences money and status together. */
public interface EconomyHostingRenewalMapper {
    // Explicit archived provenance is not a zero-price recurring plan. Unknown provenance is
    // deliberately not filtered out: the service reports corruption instead of hiding it.
    String RECURRING_SOURCE = " AND NOT EXISTS (SELECT 1 FROM economy_hosting_provisioning_intent retained"
            + " WHERE retained.tenant_id=economy_hosting_lease.tenant_id AND retained.client_id=economy_hosting_lease.client_id"
            + " AND retained.lease_id=economy_hosting_lease.lease_id AND retained.intent_id=economy_hosting_lease.latest_intent_id AND retained.settlement_kind='RETAINED')";
    @Select("SELECT CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)")
    long nowMillis();

    @Update("UPDATE economy_hosting_lease SET status=#{next.status},paid_from=#{next.paidFrom},"
            + "paid_through=#{next.paidThrough},locked_at=#{next.lockedAt},version=version+1,update_time=#{now}"
            + " WHERE " + EconomyHostingRentMapper.EXACT_SCOPE
            + " AND lease_id=#{previous.leaseId} AND version=#{previous.version} AND live_slot=1"
            + " AND status=#{previous.status} AND paid_through=#{previous.paidThrough}"
            + " AND OCTET_LENGTH(lease_id)=OCTET_LENGTH(#{previous.leaseId})")
    int update(@Param("tenantId") String tenantId, @Param("clientId") String clientId,
               @Param("previous") EconomyHostingLeaseEntity previous,
               @Param("next") EconomyHostingLeaseEntity next, @Param("now") long now);

    @Update("UPDATE economy_hosting_lease SET status='CLOSED',live_slot=NULL,version=version+1,update_time=#{now}"
            + " WHERE " + EconomyHostingRentMapper.EXACT_SCOPE
            + " AND lease_id=#{lease.leaseId} AND OCTET_LENGTH(lease_id)=OCTET_LENGTH(#{lease.leaseId})"
            + " AND version=#{lease.version} AND status='FINISHING' AND live_slot=1")
    int closeAfterHandover(@Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("lease") EconomyHostingLeaseEntity lease, @Param("now") long now);

    @Update("UPDATE economy_hosting_lease SET handover_id=#{handoverId},executor_stopped_at=#{now},"
            + "version=version+1,update_time=#{now} WHERE " + EconomyHostingRentMapper.EXACT_SCOPE
            + " AND lease_id=#{lease.leaseId} AND OCTET_LENGTH(lease_id)=OCTET_LENGTH(#{lease.leaseId})"
            + " AND version=#{lease.version} AND status='FINISHING' AND live_slot=1"
            + " AND handover_id IS NULL AND executor_stopped_at IS NULL")
    int recordExecutorStopped(@Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("lease") EconomyHostingLeaseEntity lease, @Param("handoverId") String handoverId, @Param("now") long now);

    @Select("SELECT * FROM economy_hosting_lease WHERE " + EconomyHostingRentMapper.EXACT_SCOPE
            + " AND principal_type='USER' AND principal_id=#{userId}"
            + " AND OCTET_LENGTH(principal_id)=OCTET_LENGTH(#{userId})"
            + RECURRING_SOURCE
            + " AND status IN ('ACTIVE','LOCKED') AND live_slot=1 AND paid_through<=#{now}"
            + " AND (paid_through>#{afterThrough} OR (paid_through=#{afterThrough} AND lease_id>#{afterId}))"
            + " ORDER BY paid_through,lease_id LIMIT 100 FOR UPDATE")
    List<EconomyHostingLeaseEntity> duePageForUpdate(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("userId") String userId,
            @Param("afterThrough") long afterThrough, @Param("afterId") String afterId, @Param("now") long now);
    @Select("SELECT MIN(CASE WHEN status='ACTIVE' THEN paid_through ELSE locked_at+1296000000 END)"
            + " FROM economy_hosting_lease WHERE " + EconomyHostingRentMapper.EXACT_SCOPE
            + " AND principal_type='USER' AND principal_id=#{userId}"
            + " AND OCTET_LENGTH(principal_id)=OCTET_LENGTH(#{userId})"
            + RECURRING_SOURCE
            + " AND live_slot=1 AND status IN ('ACTIVE','LOCKED')")
    Long nextDeadline(@Param("tenantId") String tenantId, @Param("clientId") String clientId,
                      @Param("userId") String userId);
}

