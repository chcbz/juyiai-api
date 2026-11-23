package cn.jia.agent.mapper;

import cn.jia.agent.entity.AgentExternalServicePolicyEntity;
import cn.jia.agent.entity.AgentIdentityRegistryEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
import java.util.List;

public interface AgentExternalServicePolicyMapper extends BaseMapper<AgentExternalServicePolicyEntity> {
    @Select("""
        SELECT * FROM agent_external_service_policy
        WHERE tenant_id=#{tenant} AND client_id=#{client} AND agent_id=#{agent} AND binding_id=#{binding}
        ORDER BY revision DESC LIMIT 1 FOR UPDATE
        """)
    AgentExternalServicePolicyEntity latestForUpdate(@Param("tenant") String tenant, @Param("client") String client,
            @Param("agent") String agent, @Param("binding") long binding);
    @Select("""
        SELECT * FROM agent_external_service_policy
        WHERE tenant_id=#{tenant} AND client_id=#{client} AND provider_owner_jiacn=#{owner}
          AND idempotency_key=#{key} FOR UPDATE
        """)
    AgentExternalServicePolicyEntity receiptForUpdate(@Param("tenant") String tenant, @Param("client") String client,
            @Param("owner") String owner, @Param("key") String key);
    @Select("""
        SELECT * FROM agent_external_service_policy
        WHERE tenant_id=#{tenant} AND client_id=#{client} AND agent_id=#{agent} AND binding_id=#{binding}
        ORDER BY revision DESC LIMIT 1
        """)
    AgentExternalServicePolicyEntity latest(@Param("tenant") String tenant, @Param("client") String client,
            @Param("agent") String agent, @Param("binding") long binding);
    @Select("""
        SELECT * FROM agent_external_service_policy
        WHERE tenant_id=#{tenant} AND client_id=#{client} AND provider_owner_jiacn=#{owner}
          AND idempotency_key=#{key}
        """)
    AgentExternalServicePolicyEntity receipt(@Param("tenant") String tenant, @Param("client") String client,
            @Param("owner") String owner, @Param("key") String key);
    @Select("""
        SELECT * FROM agent_identity_registry
        WHERE BINARY tenant_id=BINARY #{tenant} AND BINARY client_id=BINARY #{client}
          AND BINARY canonical_agent_id=BINARY #{agent} AND canonical_type != 'SYSTEM'
          AND lifecycle_status IN ('PROVISIONED','ACTIVE')
        """)
    AgentIdentityRegistryEntity identity(@Param("tenant") String tenant, @Param("client") String client,
            @Param("agent") String agent);
    String BOUND_IDENTITIES="""
        FROM agent_identity_registry i
        JOIN agent_persona_binding b ON b.id=i.binding_id
          AND BINARY b.client_id=BINARY i.client_id AND BINARY b.jiacn=BINARY i.owner_jiacn
          AND (b.tenant_id IS NULL OR BINARY b.tenant_id=BINARY i.tenant_id)
          AND b.status=1 AND b.bound_at>0 AND b.persona_code IS NOT NULL AND b.persona_code!=''
          AND (BINARY b.agent_id=BINARY i.canonical_agent_id OR EXISTS (
            SELECT 1 FROM agent_identity_alias a WHERE a.registry_id=i.id
              AND BINARY a.tenant_id=BINARY i.tenant_id AND BINARY a.client_id=BINARY i.client_id
              AND BINARY a.owner_jiacn=BINARY i.owner_jiacn AND BINARY a.alias_value=BINARY b.agent_id
              AND BINARY a.canonical_agent_id=BINARY i.canonical_agent_id
              AND a.alias_type='LEGACY_AGENT_ID' AND a.alias_status='ACTIVE'
              AND a.valid_from>0 AND a.valid_to IS NULL AND a.audit_reason IS NOT NULL AND a.audit_reason!=''))
        """;
    String VISIBLE="""
        WHERE BINARY i.tenant_id=BINARY #{tenant} AND BINARY i.client_id=BINARY #{client}
          AND i.lifecycle_status='ACTIVE' AND i.canonical_type != 'SYSTEM' AND i.binding_id IS NOT NULL
          AND (BINARY i.owner_jiacn=BINARY #{owner} OR EXISTS (
            SELECT 1 FROM agent_external_service_policy p
            WHERE p.tenant_id=i.tenant_id AND p.client_id=i.client_id
              AND p.agent_id=i.canonical_agent_id AND p.binding_id=i.binding_id AND p.enabled=1
              AND p.revision=(SELECT MAX(p2.revision) FROM agent_external_service_policy p2
                WHERE p2.tenant_id=p.tenant_id AND p2.client_id=p.client_id
                  AND p2.agent_id=p.agent_id AND p2.binding_id=p.binding_id)))
        """;
    @Select("""
        SELECT i.canonical_agent_id AS agentId, i.owner_jiacn AS ownerJiacn,
               COALESCE(NULLIF(r.name,''), i.canonical_agent_id) AS displayName, r.status AS status
        """+BOUND_IDENTITIES+"""
        LEFT JOIN agent_runtime r ON r.agent_id=i.canonical_agent_id
          AND BINARY r.agent_id=BINARY i.canonical_agent_id AND BINARY r.tenant_id=BINARY i.tenant_id
          AND BINARY r.client_id=BINARY i.client_id AND BINARY r.owner_jiacn=BINARY i.owner_jiacn
          AND r.binding_id=i.binding_id
        """+VISIBLE+" AND BINARY i.canonical_agent_id > BINARY #{after} ORDER BY BINARY i.canonical_agent_id LIMIT #{limit}")
    List<cn.jia.agent.dao.AgentExternalServicePolicyDao.CandidateRow> candidates(@Param("tenant") String tenant, @Param("client") String client,
            @Param("owner") String owner, @Param("after") String after, @Param("limit") int limit);
    // Quote authority does not read role abilities, runtime display/health, all other candidates or external skill data.
    @Select("""
        <script> SELECT i.canonical_agent_id,i.owner_jiacn,i.binding_id,
          CASE WHEN BINARY i.owner_jiacn=BINARY #{owner} THEN 0 ELSE (
            SELECT CASE WHEN p.enabled=1 AND BINARY p.provider_owner_jiacn=BINARY i.owner_jiacn THEN p.revision ELSE NULL END
            FROM agent_external_service_policy p WHERE p.tenant_id=i.tenant_id AND p.client_id=i.client_id
              AND p.agent_id=i.canonical_agent_id AND p.binding_id=i.binding_id ORDER BY p.revision DESC LIMIT 1 <if test="locking">FOR SHARE</if>
          ) END AS access_revision
        """ + BOUND_IDENTITIES + VISIBLE + """
           AND i.canonical_agent_id IN <foreach collection="ids" item="id" open="(" close=")" separator=",">#{id}</foreach>
           ORDER BY BINARY i.canonical_agent_id <if test="locking">FOR SHARE</if></script>
        """)
    List<cn.jia.agent.dao.AgentExternalServicePolicyDao.QuoteIdentity> selected(@Param("tenant") String tenant,@Param("client") String client,
            @Param("owner") String owner,@Param("ids") List<String> ids,@Param("locking") boolean locking);
}
