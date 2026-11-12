package cn.jia.agent.hosting;

import cn.jia.agent.common.AgentConstants;
import cn.jia.agent.config.AgentHostingRentProperties;
import cn.jia.agent.skill.SkillManagementService;
import cn.jia.agent.dao.AgentPersonaBindingDao;
import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.dao.AgentRuntimeV1InstallationDao;
import cn.jia.agent.mapper.HostingRentFinishingMapper;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.agent.service.AgentRuntimeV1Service;
import cn.jia.core.security.FixedTenantAdmissionPolicy;
import cn.jia.economy.common.EconomyPrincipalType;
import cn.jia.economy.hosting.HostingRentRenewalService;
import cn.jia.economy.service.EconomyPrincipal;
import cn.jia.economy.service.EconomyScope;
import cn.jia.oauth.service.ApiKeyService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Objects;

/** DB-only relationship release. Managed reuse requires Runtime cleanup; self-hosted assets never do.
 * Both paths lock the exact original installation/binding and preserve unresolved platform business facts. */
@Service
public class HostingRentHandoverCompletion {
    private final AgentRuntimeV1InstallationDao installations;
    private final AgentPersonaBindingDao bindings;
    private final HostingRentRenewalService renewal;
    private final AgentIdentityService identities;
    private final AgentRuntimeDao runtimes;
    private final AgentRuntimeV1Service runtime;
    private final HostingRentFinishingMapper tasks;
    private final ObjectProvider<ApiKeyService> keys;
    private final FixedTenantAdmissionPolicy scope;

    public HostingRentHandoverCompletion(AgentRuntimeV1InstallationDao installations, AgentPersonaBindingDao bindings,
            HostingRentRenewalService renewal, AgentIdentityService identities,
            AgentRuntimeDao runtimes, AgentRuntimeV1Service runtime, HostingRentFinishingMapper tasks,
            ObjectProvider<ApiKeyService> keys, FixedTenantAdmissionPolicy scope) {
        this.installations=installations; this.bindings=bindings; this.renewal=renewal;
        this.identities=identities; this.runtimes=runtimes; this.runtime=runtime; this.tasks=tasks; this.keys=keys; this.scope=scope;
    }

    private SkillManagementService skills;
    @org.springframework.beans.factory.annotation.Autowired
    public void setSkills(SkillManagementService value) { this.skills = value; }

    /** Returns false for a managed lease; never calls a Runtime or fabricates a stop/cleanup receipt.
     * The original binding lock fences new skill work while checking pending platform settlements. */
    @Transactional(rollbackFor = Exception.class)
    public boolean completeSelfHostedIfApplicable(EconomyScope partition,
            cn.jia.agent.entity.AgentPersonaBindingEntity binding,
            cn.jia.economy.entity.EconomyHostingProvisioningIntentEntity intent) {
        var original = lockOriginal(partition.tenantId(), partition.clientId(), binding.getJiacn(),
                binding.getAgentId(), String.valueOf(binding.getId()), intent.getIntentId(), intent.getLeaseId(),
                intent.associationStartedAt(), intent.getRuntimeInstallationId(), intent.getRuntimeManifestSha256(), null);
        if (!AgentHostingRentProperties.SELF_HOSTED_PLAN_ID.equals(original.lease().getPlanId())) return false;
        if ("CLOSED".equals(original.lease().getStatus())) return true;
        require(skills != null && skills.selfHostedHandoverSettled(partition, binding.getJiacn(), binding.getAgentId(),
                intent.getRuntimeInstallationId()));
        release(original, partition.tenantId(), partition.clientId(), binding.getJiacn(), binding.getAgentId(),
                String.valueOf(binding.getId()), intent.getIntentId(), intent.getLeaseId(), intent.getRuntimeInstallationId());
        return true;
    }

    @Transactional(rollbackFor = Exception.class)
    public void completeManaged(ManagedHostingProvisioner.CurrentOperation cleaned) {
        var original=lockOriginal(cleaned);
        require(AgentHostingRentProperties.MANAGED_PLAN_ID.equals(original.lease().getPlanId()));
        if ("CLOSED".equals(original.lease().getStatus())) return;
        var p=cleaned.preparation();
        release(original, p.tenantId(), p.clientId(), p.ownerJiacn(), p.agentId(), p.bindingId(),
                p.intentId(), p.leaseId(), cleaned.candidate().installationId());
    }
    private void release(Original original, String tenant, String client, String owner, String agent,
                         String bindingId, String intentId, String leaseId, String installationId) {
        var binding=original.binding(); var intent=original.intent(); var projection=original.projection();
        long now=System.currentTimeMillis();
        runtime.revoke(tenant, client, owner, installationId, now);
        if (intent.getManagedApiKeyId()!=null) {
            var keyService=keys.getIfAvailable();
            require(keyService!=null && keyService.disableManagedKey(intent.getManagedApiKeyId(), tenant,
                    client, owner, "hosting:"+intentId));
        }
        renewal.completeHandover(new EconomyScope(tenant, client),
                new EconomyPrincipal(EconomyPrincipalType.USER, intent.getPrincipalId()), leaseId, bindingId, agent);
        binding.setStatus(AgentConstants.BINDING_STATUS_SUSPENDED);
        require(bindings.updateById(binding)==1);
        identities.suspendForBinding(tenant, client, owner, binding.getId());
        require(runtimes.clearBindingAfterUnbind(projection.getId(), agent, binding.getId(), client, owner, now)==1);
    }
    /** A DB-only transition need not contact a host at registration time. Managed asset reclamation
     * may later attach its original association, but only from the exact locked transition facts. */
    @Transactional(rollbackFor = Exception.class)
    public ManagedHostingProvisioner.CurrentOperation managedTransitionAssociation(ManagedHostingProvisioner.Preparation p) {
        var intent = renewal.initialHandoverIntent(new EconomyScope(p.tenantId(), p.clientId()), p.leaseId());
        if (intent == null || !"TRANSITION".equals(intent.getSettlementKind())) return null;
        var original = lockOriginal(p.tenantId(), p.clientId(), p.ownerJiacn(), p.agentId(), p.bindingId(),
                p.intentId(), p.leaseId(), p.reservedAt(), intent.getRuntimeInstallationId(),
                intent.getRuntimeManifestSha256(), null);
        if ("CLOSED".equals(original.lease().getStatus())) return null;
        require(AgentHostingRentProperties.MANAGED_PLAN_ID.equals(original.lease().getPlanId()));
        var host = original.projection().getRuntimeHostId();
        require(host != null && !host.isBlank());
        return new ManagedHostingProvisioner.CurrentOperation(p, new ManagedHostingProvisioner.Candidate(
                original.intent().getRuntimeInstallationId(), original.intent().getRuntimeManifestSha256(),
                null, 0, 1, host));
    }

    /** Pre-stop business transaction, before external process control. Pending old tasks never permit a stop. */
    @Transactional(rollbackFor = Exception.class)
    public boolean prepareManagedStop(ManagedHostingProvisioner.CurrentOperation current) {
        var original = lockOriginal(current);
        require(AgentHostingRentProperties.MANAGED_PLAN_ID.equals(original.lease().getPlanId()));
        return !"CLOSED".equals(original.lease().getStatus());
    }

    /** Called by the stop coordinator only after the exact original Runtime returned QUIESCED.
     * Rechecks the original roots before publishing the skill activation fence. */
    @Transactional(rollbackFor = Exception.class)
    public void recordManagedStop(ManagedHostingProvisioner.CurrentOperation current) {
        var original=lockOriginal(current);var p=current.preparation();
        require(AgentHostingRentProperties.MANAGED_PLAN_ID.equals(original.lease().getPlanId()));
        renewal.recordExecutorStopped(new EconomyScope(p.tenantId(),p.clientId()),
                new EconomyPrincipal(EconomyPrincipalType.USER,original.intent().getPrincipalId()),
                p.leaseId(),p.bindingId(),p.agentId(),p.leaseId());
    }

    private record Original(cn.jia.agent.entity.AgentPersonaBindingEntity binding,
            cn.jia.economy.entity.EconomyHostingProvisioningIntentEntity intent,
            cn.jia.economy.entity.EconomyHostingLeaseEntity lease,
            cn.jia.agent.entity.AgentRuntimeEntity projection) { }

    private Original lockOriginal(ManagedHostingProvisioner.CurrentOperation cleaned) {
        var p=cleaned.preparation(); var candidate=cleaned.candidate();
        return lockOriginal(p.tenantId(), p.clientId(), p.ownerJiacn(), p.agentId(), p.bindingId(), p.intentId(),
                p.leaseId(), p.reservedAt(), candidate.installationId(), candidate.manifestSha256(), candidate.hostId());
    }
    private Original lockOriginal(String tenant, String client, String owner, String agent, String bindingKey,
                                  String intentId, String leaseId, long associationAt, String installationId,
                                  String manifestSha256, String expectedHostId) {
        scope.requireAdmitted(tenant, client);
        // Same root order as installation authorization: installation -> binding -> intent -> lease.
        var installation=installations.lock(installationId);
        long bindingId=Long.parseLong(bindingKey);
        var binding=bindings.findByIdForUpdate(bindingId);
        var financial=renewal.lockHandover(new EconomyScope(tenant, client), intentId, leaseId);
        var intent=financial.intent(); var lease=financial.lease();
        require(binding!=null && Objects.equals(binding.getId(), bindingId)
                && tenant.equals(binding.getTenantId()) && client.equals(binding.getClientId())
                && owner.equals(binding.getJiacn()) && agent.equals(binding.getAgentId()));
        require(intent!=null && "INITIAL".equals(intent.getQuotePurpose()) && "USER".equals(intent.getPrincipalType())
                && "ACTIVE".equals(intent.getStatus()) && intent.getRefundTransactionId()==null
                && agent.equals(intent.getAgentId()) && leaseId.equals(intent.getLeaseId())
                && Objects.equals(associationAt, intent.associationStartedAt())
                && installationId.equals(intent.getRuntimeInstallationId())
                && manifestSha256.equals(intent.getRuntimeManifestSha256()));
        require(lease!=null && "USER".equals(lease.getPrincipalType())
                && intent.getPrincipalId().equals(lease.getPrincipalId()) && agent.equals(lease.getAgentId())
                && bindingKey.equals(lease.getBindingId()) && binding.getPersonaCode().equals(lease.getPersonaCode()));
        // Historical completion replay never revokes a later invitation's authorization or clears its projection.
        if ("CLOSED".equals(lease.getStatus())) return new Original(binding, intent, lease, null);
        require("FINISHING".equals(lease.getStatus()) && Integer.valueOf(AgentConstants.BINDING_STATUS_ACTIVE).equals(binding.getStatus()));
        require(installation!=null && tenant.equals(installation.getTenantId()) && client.equals(installation.getClientId())
                && agent.equals(installation.getCanonicalAgentId()) && manifestSha256.equals(installation.getManifestSha256()));
        require(tasks.unfinishedPage(tenant, client, owner, agent, 0).isEmpty());
        var projection=runtimes.findByAgentIdForUpdate(agent);
        require(projection!=null && projection.getId()!=null && Objects.equals(projection.getBindingId(), bindingId)
                && client.equals(projection.getClientId()) && owner.equals(projection.getOwnerJiacn())
                && installationId.equals(projection.getRuntimeInstallationId())
                && (expectedHostId == null || expectedHostId.equals(projection.getRuntimeHostId())));
        return new Original(binding,intent,lease,projection);
    }

    private static void require(boolean condition) {
        if (!condition) throw new IllegalStateException("Original hosting handover relationship changed or remains unfinished");
    }
}
