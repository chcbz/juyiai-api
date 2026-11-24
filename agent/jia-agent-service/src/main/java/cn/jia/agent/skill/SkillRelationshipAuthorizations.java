package cn.jia.agent.skill;

import cn.jia.agent.entity.skill.SkillInstallationAuthorizationEntity;
import cn.jia.agent.hosting.ManagedHostingProvisioner;
import cn.jia.agent.mapper.skill.SkillManagementMapper;
import cn.jia.agent.service.SkillCapabilityLookup;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Separates public installation provenance from a new owner's paid use of retained skills.
 * The captured INITIAL rent and its frozen inventory ARE the entitlement: no copied order,
 * fabricated installation result, new grant table or background materialization is needed. */
@Service
public final class SkillRelationshipAuthorizations {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final SkillManagementMapper mapper;
    private final boolean schemaEnabled;
    public SkillRelationshipAuthorizations(SkillManagementMapper mapper,
            @org.springframework.beans.factory.annotation.Value("${economy.hosting-rent.schema-enabled:false}") boolean schemaEnabled) {
        this.mapper = mapper; this.schemaEnabled = schemaEnabled;
    }

    /** Load once per Agent operation/catalog; not once per installed skill. No network or writes. */
    public Relationship read(SkillCapabilityLookup.Scope scope) {
        if (!schemaEnabled) return null;
        var row = mapper.paidInventory(scope.tenantId(), scope.clientId(), scope.agentId(), scope.ownerJiacn());
        return decode(row);
    }

    /** Current paid relationship per exact candidate/provider, never a cross-operation cache.
     * Use the single-key path unchanged for management and single-candidate index observations. */
    Map<SkillManagementMapper.InventoryKey,Relationship> readMany(String tenant,String client,List<SkillManagementMapper.InventoryKey> requested) {
        if (!schemaEnabled || requested.isEmpty()) return Map.of();
        var keys=requested.stream().distinct().toList();
        if(keys.size()==1){var key=keys.getFirst();var value=read(new SkillCapabilityLookup.Scope(tenant,client,key.ownerJiacn(),key.agentId()));return value==null?Map.of():Map.of(key,value);}
        var result=new LinkedHashMap<SkillManagementMapper.InventoryKey,Relationship>();
        for(int i=0;i<keys.size();i+=128){
            var batch=keys.subList(i,Math.min(i+128,keys.size()));var expected=java.util.Set.copyOf(batch);
            for(var row:mapper.paidInventories(tenant,client,batch)){
                var key=new SkillManagementMapper.InventoryKey(row.getAgentId(),row.getOwnerJiacn());
                // Do not share a paid inventory across owners, nor silently choose one of two live relationships.
                if(!expected.contains(key)||result.putIfAbsent(key,decode(row))!=null)throw new SkillMarketplaceException(503,"SKILL_STATE_UNAVAILABLE");
            }
        }
        return Map.copyOf(result);
    }

    private Relationship decode(cn.jia.agent.entity.skill.PaidSkillInventoryEntity row) {
        if (row == null) return null;
        try {
            var skills = JSON.readValue(row.getInventorySnapshotJson(), new TypeReference<List<ManagedHostingProvisioner.InstalledSkill>>() { });
            var managed = new LinkedHashMap<String, ManagedHostingProvisioner.InstalledSkill>();
            for (var skill : skills) {
                // Native discoveries remain public engine skills; never invent marketplace IDs.
                if (skill.installation() != null && managed.put(skill.installation().skillInstallationId(), skill) != null)
                    throw new IllegalArgumentException("Duplicate paid installation");
            }
            return new Relationship(row.getPrincipalId(), row.getOwnerJiacn(), row.getBindingId(), row.getLeaseId(),
                    row.getIntentId(), row.getCaptureTransactionId(), row.getRuntimeInstallationId(), row.getRuntimeHostId(), Map.copyOf(managed));
        } catch (Exception invalid) {
            throw new SkillMarketplaceException(503, "SKILL_STATE_UNAVAILABLE");
        }
    }

    public record Relationship(String actorId, String ownerJiacn, long bindingId, String leaseId,
            String intentId, String captureTransactionId, String runtimeInstallationId, String runtimeHostId,
            Map<String, ManagedHostingProvisioner.InstalledSkill> installations) {
        public Relationship { installations = Map.copyOf(installations); }
        /** Exact public reference, not just a skill name or a positive total price. Disabled skills
         * are paid too; their current admission policy is still enforced by SkillManagementService. */
        public boolean covers(SkillInstallationAuthorizationEntity authorization) {
            var skill = installations.get(authorization.getInstallationId());
            if (skill == null) return false;
            try {
                var target = JSON.readTree(authorization.getPackageJson());
                var reference = skill.installation();
                return reference.productVersionId().equals(target.path("productVersionId").asText())
                        && reference.skillVersion().equals(target.path("skillVersion").asText())
                        && reference.kind().equals(target.path("kind").asText())
                        && reference.packageSha256().equals(target.path("packageSha256").asText())
                        && Objects.equals(reference.manifestSha256(), target.path("manifestSha256").isNull()
                                ? null : target.path("manifestSha256").asText(null));
            } catch (Exception invalid) { throw new SkillMarketplaceException(503, "SKILL_STATE_UNAVAILABLE"); }
        }
        public String skillKey(SkillInstallationAuthorizationEntity authorization) {
            return installations.get(authorization.getInstallationId()).skillKey();
        }
    }
}
