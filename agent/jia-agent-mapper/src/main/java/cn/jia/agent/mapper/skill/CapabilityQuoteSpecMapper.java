package cn.jia.agent.mapper.skill;

import org.apache.ibatis.annotations.*;
import java.util.List;

/** Skill-owned immutable specification persistence; no publication authorization here. */
public interface CapabilityQuoteSpecMapper {
    record StoredSpec(String capabilityId,String version,String specSha,String specJson,String status) { }
    record Binding(String capabilityId,String capabilityVersion,String semanticSha) { }
    String SCOPE=" tenant_id=#{tenantId} AND client_id=#{clientId} "
            +"AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId}) AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId}) ";
    @Select("SELECT capability_id,version,spec_sha,spec_json,status FROM agent_capability_quote_spec WHERE "+SCOPE
            +" AND capability_id=#{capabilityId} AND version=#{version} FOR UPDATE")
    StoredSpec lockSpec(@Param("tenantId") String tenant,@Param("clientId") String client,
            @Param("capabilityId") String capability,@Param("version") String version);
    @Insert("INSERT INTO agent_capability_quote_spec(tenant_id,client_id,capability_id,version,spec_sha,spec_json,status) "
            +"VALUES(#{tenantId},#{clientId},#{capabilityId},#{version},#{sha},#{json},'ACTIVE')")
    int insertSpec(@Param("tenantId") String tenant,@Param("clientId") String client,
            @Param("capabilityId") String capability,@Param("version") String version,@Param("sha") String sha,@Param("json") String json);
    @Select("SELECT capability_id,capability_version,semantic_sha FROM agent_skill_quote_spec_binding WHERE "+SCOPE
            +" AND product_version_id=#{productVersionId} ORDER BY capability_id,capability_version FOR UPDATE")
    List<Binding> lockBindings(@Param("tenantId") String tenant,@Param("clientId") String client,@Param("productVersionId") String productVersion);
    @Insert("INSERT INTO agent_skill_quote_spec_binding(tenant_id,client_id,product_version_id,capability_id,capability_version,semantic_sha) "
            +"VALUES(#{tenantId},#{clientId},#{productVersionId},#{capabilityId},#{version},#{sha})")
    int insertBinding(@Param("tenantId") String tenant,@Param("clientId") String client,@Param("productVersionId") String productVersion,
            @Param("capabilityId") String capability,@Param("version") String version,@Param("sha") String sha);
    record SpecKey(String capabilityId,String version) { }
    @Select("""
            <script>
            SELECT s.capability_id,s.version,s.spec_sha,s.spec_json,s.status
            FROM agent_capability_quote_spec s
            WHERE s.tenant_id=#{tenantId} AND s.client_id=#{clientId}
              AND OCTET_LENGTH(s.tenant_id)=OCTET_LENGTH(#{tenantId})
              AND OCTET_LENGTH(s.client_id)=OCTET_LENGTH(#{clientId})
              AND (s.capability_id,s.version) IN
                <foreach collection="keys" item="key" open="(" separator="," close=")">(#{key.capabilityId},#{key.version})</foreach>
              AND EXISTS (SELECT 1 FROM agent_skill_quote_spec_binding b
                  JOIN agent_skill_product_version v ON v.tenant_id=b.tenant_id AND v.client_id=b.client_id
                    AND v.product_version_id=b.product_version_id
                  WHERE b.tenant_id=s.tenant_id AND b.client_id=s.client_id AND b.capability_id=s.capability_id
                    AND b.capability_version=s.version AND b.semantic_sha=s.spec_sha AND v.review_status='APPROVED')
            ORDER BY s.capability_id,s.version
            <if test="locking">FOR SHARE</if>
            </script>
            """)
    List<StoredSpec> published(@Param("tenantId") String tenant,@Param("clientId") String client,
            @Param("keys") List<SpecKey> keys,@Param("locking") boolean locking);
    @Update("UPDATE agent_capability_quote_spec SET status='WITHDRAWN' WHERE "+SCOPE
            +" AND capability_id=#{capabilityId} AND version=#{version} AND spec_sha=#{sha} AND status='ACTIVE'")
    int withdraw(@Param("tenantId") String tenant,@Param("clientId") String client,
            @Param("capabilityId") String capability,@Param("version") String version,@Param("sha") String sha);
}
