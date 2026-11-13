package cn.jia.agent.hosting;

import cn.jia.agent.config.AgentHostingRentProperties;
import cn.jia.agent.dao.AgentPersonaBindingDao;
import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.mapper.hosting.HostingHostStore;
import cn.jia.agent.mapper.hosting.HostingOutboundStore;
import cn.jia.agent.mapper.hosting.HostingSetupStore;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.agent.service.AgentRuntimeV1Service;
import cn.jia.core.security.FixedTenantAdmissionPolicy;
import cn.jia.economy.hosting.HostingRentRenewalService;
import cn.jia.economy.service.EconomyScope;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;

/** Explicit operator maintenance, never an HTTP bean or online lifecycle coordinator.
 * Old journal/queue reconciliation is an operator precondition; this imports only
 * exact existing DB relationships and never dispatches or replays a command. */
public final class HostingControlMaintenance {
    public record Command(String operation, String hostId, String mode, String actorId, String ownerJiacn,
                          String tokenSha256, String expectedTokenSha256, String idempotencyKey,
                          String authorizationId, String agentId, String installationId, String leaseId) { }
    private final HostingHostStore hosts;
    private final HostingHostService registration;
    private final HostingOutboundStore grants;
    private final HostingSetupStore setups;
    private final HostingOutboundExchangeService exchange;
    private final HostingRentOwnerResolver owners;
    private final AgentIdentityService identities;
    private final AgentPersonaBindingDao bindings;
    private final AgentRuntimeDao runtimes;
    private final AgentRuntimeV1Service installations;
    private final HostingRentRenewalService leases;
    private final FixedTenantAdmissionPolicy partition;
    private final TransactionTemplate transactions;
    public HostingControlMaintenance(HostingHostStore hosts, HostingHostService registration, HostingOutboundStore grants,
            HostingSetupStore setups, HostingOutboundExchangeService exchange, HostingRentOwnerResolver owners,
            AgentIdentityService identities, AgentPersonaBindingDao bindings, AgentRuntimeDao runtimes,
            AgentRuntimeV1Service installations, HostingRentRenewalService leases,
            FixedTenantAdmissionPolicy partition, PlatformTransactionManager manager) {
        this.hosts=hosts; this.registration=registration; this.grants=grants; this.setups=setups; this.exchange=exchange;
        this.owners=owners; this.identities=identities; this.bindings=bindings; this.runtimes=runtimes;
        this.installations=installations; this.leases=leases; this.partition=partition; this.transactions=new TransactionTemplate(manager);
    }
    public Object execute(Command c, boolean apply) {
        Objects.requireNonNull(c); HostingRentHttp.exact(c.hostId(),100);
        return transactions.execute(tx -> {
            Object result=switch (c.operation()) {
                case "REGISTER" -> register(c);
                case "ROTATE", "REVOKE" -> credential(c);
                case "IMPORT" -> importRelationship(c);
                default -> throw new IllegalArgumentException("Unknown host maintenance operation");
            };
            // Preview runs the same constraints but rolls back all writes, including grant mapping.
            if (!apply) tx.setRollbackOnly();
            return result;
        });
    }
    private HostingRentHttp.Actor actor(Command c) {
        return new HostingRentHttp.Actor(c.actorId(),partition.businessTenantId(),partition.clientId(),c.ownerJiacn());
    }
    private Object register(Command c) {
        if ("server".equals(c.mode())) {
            if (c.ownerJiacn()!=null || c.actorId()!=null) throw new IllegalArgumentException("Managed host has no owner");
            return registration.registerManaged(partition.businessTenantId(),partition.clientId(),c.hostId(),c.tokenSha256(),c.idempotencyKey());
        }
        if (!"local".equals(c.mode())) throw new IllegalArgumentException("Explicit deployment mode required");
        return registration.register(actor(c),c.idempotencyKey(),Map.of("hostId",c.hostId(),"tokenSha256",c.tokenSha256()));
    }
    private static byte[] digest(String value) {
        if (value==null || !value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Exact host hash required");
        return HexFormat.of().parseHex(value);
    }
    private Object credential(Command c) {
        var expected=digest(c.expectedTokenSha256());
        byte[] replacement="ROTATE".equals(c.operation()) ? digest(c.tokenSha256()) : null;
        if (replacement!=null && MessageDigest.isEqual(expected,replacement)) throw new IllegalArgumentException("Rotation requires a new hash");
        // One CAS update takes the host lock, with no grant/financial callbacks or lock upgrade.
        int changed=hosts.replaceCredential(partition.businessTenantId(),partition.clientId(),c.hostId(),expected,replacement,System.currentTimeMillis());
        var host=hosts.find(partition.businessTenantId(),partition.clientId(),c.hostId());
        if (changed!=1 && (host==null || (replacement==null
                ? !"REVOKED".equals(host.status()) || !MessageDigest.isEqual(expected,host.tokenHash())
                : !"ACTIVE".equals(host.status()) || !MessageDigest.isEqual(replacement,host.tokenHash()))))
            throw new IllegalStateException("Host credential changed; no mutation applied");
        return HostingHostService.view(host);
    }
    private Object importRelationship(Command c) {
        var actor=actor(c); String owner=owners.requireOwner(actor);
        HostingRentHttp.exact(c.authorizationId(),100); HostingRentHttp.exact(c.agentId(),100);
        HostingRentHttp.exact(c.installationId(),100); HostingRentHttp.exact(c.leaseId(),100);
        // Preserve existing installation -> binding -> runtime -> lease -> grant order.
        var installation=installations.lockExistingInstallation(actor.tenantId(),actor.clientId(),owner,c.installationId());
        if (installation==null || !"ACTIVE".equals(installation.status()) || !c.agentId().equals(installation.canonicalAgentId())) throw mismatch();
        var identity=identities.requireRegistrationIdentityInScope(actor.tenantId(),actor.clientId(),owner,c.agentId());
        var binding=bindings.findByIdForUpdate(identity.getBindingId());
        if (binding==null || !actor.tenantId().equals(binding.getTenantId()) || !actor.clientId().equals(binding.getClientId())
                || !owner.equals(binding.getJiacn()) || !c.agentId().equals(binding.getAgentId()) || !Integer.valueOf(1).equals(binding.getStatus())) throw mismatch();
        var runtime=runtimes.lockInScope(actor.tenantId(),actor.clientId(),c.agentId());
        if (runtime==null || !binding.getId().equals(runtime.getBindingId()) || !owner.equals(runtime.getOwnerJiacn())
                || !c.installationId().equals(runtime.getRuntimeInstallationId()) || !c.hostId().equals(runtime.getRuntimeHostId())) throw mismatch();
        var lease=leases.lockLatestLease(new EconomyScope(actor.tenantId(),actor.clientId()),c.agentId());
        if (lease==null || !c.leaseId().equals(lease.getLeaseId()) || !binding.getId().toString().equals(lease.getBindingId())
                || !"USER".equals(lease.getPrincipalType()) || !actor.actorId().equals(lease.getPrincipalId()) || !java.util.Set.of("ACTIVE","LOCKED").contains(lease.getStatus())) throw mismatch();
        var host=hosts.find(actor.tenantId(),actor.clientId(),c.hostId());
        String mode=AgentHostingRentProperties.SELF_HOSTED_PLAN_ID.equals(lease.getPlanId()) ? "local"
                : AgentHostingRentProperties.MANAGED_PLAN_ID.equals(lease.getPlanId()) ? "server" : null;
        if (host==null || !"ACTIVE".equals(host.status()) || !host.mode().equals(mode)
                || "local".equals(mode) && !owner.equals(host.ownerJiacn())) throw mismatch();
        var expected=new HostingOutboundStore.Grant(actor.tenantId(),actor.clientId(),c.authorizationId(),c.hostId(),owner,c.agentId(),"CONTROL","ACTIVE");
        var existing=setups.leaseActivation(actor.tenantId(),actor.clientId(),c.leaseId());
        if (existing!=null) {
            if (existing.setupId()!=null || !existing.authorizationId().equals(c.authorizationId()) || !existing.bindingId().equals(lease.getBindingId())
                    || !expected.equals(grants.lock(actor.tenantId(),actor.clientId(),c.authorizationId()))) throw mismatch();
        } else {
            if (grants.hasSubject(actor.tenantId(),actor.clientId(),owner,c.agentId())) throw mismatch();
            exchange.register(expected,System.currentTimeMillis());
            setups.importActivation(actor.tenantId(),actor.clientId(),c.authorizationId(),lease.getBindingId(),c.leaseId());
        }
        return Map.of("authorizationId",c.authorizationId(),"hostId",c.hostId(),"agentId",c.agentId(),"leaseId",c.leaseId());
    }
    private static IllegalStateException mismatch() { return new IllegalStateException("Existing control relationship is not authorized or changed"); }
}
