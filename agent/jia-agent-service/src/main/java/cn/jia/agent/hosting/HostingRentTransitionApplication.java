package cn.jia.agent.hosting;

import cn.jia.agent.config.AgentHostingRentProperties;
import cn.jia.agent.dao.AgentPersonaBindingDao;
import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.agent.service.AgentRuntimeV1Service;
import cn.jia.core.security.FixedTenantAdmissionPolicy;
import cn.jia.economy.hosting.HostingRentRenewalService;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** DB-only operator cutover of existing relationships. No HTTP endpoint, Runtime I/O, enrollment or payment.
 * The fixed operator input selects the deployment plan; a network route is not a deployment fact.
 * Exact installation/binding locks, transition provenance and first-expiry scheduling share one transaction. */
@Service
@Profile("schema-maintenance")
public final class HostingRentTransitionApplication {
    private final HostingRentOwnerResolver owners;
    private final AgentIdentityService identities;
    private final AgentPersonaBindingDao bindings;
    private final AgentRuntimeDao runtimes;
    private final AgentRuntimeV1Service installations;
    private final HostingRentRenewalService renewal;
    private final AgentHostingRentProperties prices;
    private final FixedTenantAdmissionPolicy partition;
    private final TransactionTemplate transactions;

    public HostingRentTransitionApplication(HostingRentOwnerResolver owners, AgentIdentityService identities,
            AgentPersonaBindingDao bindings, AgentRuntimeDao runtimes, AgentRuntimeV1Service installations,
            HostingRentRenewalService renewal, AgentHostingRentProperties prices,
            FixedTenantAdmissionPolicy partition, PlatformTransactionManager manager) {
        this.owners = owners; this.identities = identities; this.bindings = bindings; this.runtimes = runtimes;
        this.installations = installations; this.renewal = renewal; this.prices = prices;
        this.partition = partition; this.transactions = new TransactionTemplate(manager);
    }
    public record Request(HostingRentHttp.Actor actor, String agentId, String installationId,
                          String planId, long cutoverAt) { }

    /** No grant, wallet, schedule, Runtime mutation or network probe. */
    public HostingRentRenewalService.Transition preview(Request request) {
        requireRequest(request);
        return transactions.execute(tx -> inspect(request));
    }
    private static void requireRequest(Request request) {
        Objects.requireNonNull(request);
        if (request.cutoverAt() <= 0 || request.cutoverAt() > System.currentTimeMillis())
            throw new IllegalArgumentException("Cutover input must contain the actual fixed cutover time");
        if (!AgentHostingRentProperties.SELF_HOSTED_PLAN_ID.equals(request.planId())
                && !AgentHostingRentProperties.MANAGED_PLAN_ID.equals(request.planId()))
            throw new IllegalArgumentException("Fixed deployment plan required; no route or heartbeat inference");
    }
    public String register(Request request) {
        requireRequest(request);
        return transactions.execute(tx -> renewal.registerTransition(inspect(request)));
    }
    private HostingRentRenewalService.Transition inspect(Request request) {
        Objects.requireNonNull(request);
        var actor = Objects.requireNonNull(request.actor());
        partition.requireAdmitted(actor.tenantId(), actor.clientId());
        if (!prices.configured()) throw new IllegalStateException("Hosting prices are not configured");
        HostingRentHttp.exact(request.agentId(), 100); HostingRentHttp.exact(request.installationId(), 100);
        String owner = owners.requireOwner(actor);
        // Same installation -> binding -> rent lock order as normal lifecycle mutations.
        var installation = installations.lockExistingInstallation(actor.tenantId(), actor.clientId(), owner, request.installationId());
        if (installation == null || !request.agentId().equals(installation.canonicalAgentId())) throw changed();
        var identity = identities.requireRegistrationIdentityInScope(actor.tenantId(), actor.clientId(), owner, request.agentId());
        if (!request.agentId().equals(identity.getCanonicalAgentId())) throw changed();
        var binding = bindings.findByIdForUpdate(identity.getBindingId());
        if (binding == null || !Integer.valueOf(1).equals(binding.getStatus()) || !owner.equals(binding.getJiacn())
                || !request.agentId().equals(binding.getAgentId()) || !actor.clientId().equals(binding.getClientId())
                || !actor.tenantId().equals(binding.getTenantId())) throw changed();
        var runtime = runtimes.lockInScope(actor.tenantId(), actor.clientId(), request.agentId());
        if (runtime == null || !Objects.equals(binding.getId(), runtime.getBindingId())
                || !owner.equals(runtime.getOwnerJiacn()) || !request.installationId().equals(runtime.getRuntimeInstallationId())) throw changed();
        String plan = request.planId();
        long version = Long.parseLong(AgentHostingRentProperties.SELF_HOSTED_PLAN_ID.equals(plan)
                ? prices.selfHostedPlanVersion() : prices.planVersion());
        String id = UUID.nameUUIDFromBytes((actor.tenantId() + "\0" + actor.clientId() + "\0" + binding.getId())
                .getBytes(StandardCharsets.UTF_8)).toString();
        String lease = "hrl_transition_" + id, intent = "hri_transition_" + id;
        return new HostingRentRenewalService.Transition(actor.scope(), actor.principal(), lease, intent,
                binding.getPersonaCode(), request.agentId(), binding.getId().toString(), plan, version,
                request.cutoverAt(), request.installationId(), installation.manifestSha256());
    }
    private static IllegalStateException changed() { return new IllegalStateException("Existing cutover subject is not authorized"); }
}
