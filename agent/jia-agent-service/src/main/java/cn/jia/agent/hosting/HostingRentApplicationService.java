package cn.jia.agent.hosting;

import cn.jia.agent.common.AgentConstants;
import cn.jia.agent.config.AgentHostingRentProperties;
import cn.jia.agent.dao.AgentPersonaBindingDao;
import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.entity.AgentIdentityRegistryEntity;
import cn.jia.agent.entity.AgentPersonaBindingEntity;
import cn.jia.agent.entity.AgentPersonaEntity;
import cn.jia.agent.entity.AgentRuntimeEntity;
import cn.jia.agent.mapper.AgentHostingRentBindingMapper;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.economy.config.EconomyPreviewGate;
import cn.jia.economy.entity.EconomyHostingLeaseEntity;
import cn.jia.economy.entity.EconomyHostingReprovisionEntity;
import cn.jia.economy.entity.EconomyHostingProvisioningIntentEntity;
import cn.jia.economy.entity.EconomyHostingRentPlanEntity;
import cn.jia.economy.entity.EconomyHostingRentQuoteEntity;
import cn.jia.economy.hosting.*;
import cn.jia.economy.mapper.EconomyHostingRentMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static cn.jia.agent.hosting.HostingRentHttp.*;

/** HTTP application boundary with transaction-external admission probe. The legacy fallback-based bind service is never called. */
@Service
public final class HostingRentApplicationService {

    private final AgentHostingRentProperties properties;
    private final EconomyPreviewGate preview;
    private final HostingRentOwnerResolver owners;
    private final EconomyHostingRentMapper rent;
    private final HostingRentLedgerService ledger;
    private final HostingRentRenewalService renewal;
    private final AgentHostingRentBindingMapper roots;
    private final AgentPersonaBindingDao bindings;
    private final AgentRuntimeDao runtimes;
    private final AgentIdentityService identities;
    private final ObjectProvider<ManagedHostingProvisioner> provisioners;
    private final HostingRentReconciler reconciler;
    private final TransactionTemplate transactions;
    private final TransactionTemplate reads;
    private final boolean schemaEnabled;
    private cn.jia.agent.mapper.hosting.PersonaModelHostingStore modelPrices;
    @org.springframework.beans.factory.annotation.Autowired
    public void setModelPrices(cn.jia.agent.mapper.hosting.PersonaModelHostingStore modelPrices) { this.modelPrices = modelPrices; }
    private HostingSetupService setups;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setHostingSetups(HostingSetupService setups) { this.setups = setups; }

    public HostingRentApplicationService(AgentHostingRentProperties properties, EconomyPreviewGate preview,
            HostingRentOwnerResolver owners, EconomyHostingRentMapper rent, HostingRentLedgerService ledger,
            HostingRentRenewalService renewal,
            AgentHostingRentBindingMapper roots, AgentPersonaBindingDao bindings, AgentRuntimeDao runtimes,
            AgentIdentityService identities, ObjectProvider<ManagedHostingProvisioner> provisioners,
            HostingRentReconciler reconciler, PlatformTransactionManager transactionManager,
            @org.springframework.beans.factory.annotation.Value("${economy.hosting-rent.schema-enabled:false}") boolean schemaEnabled) {
        this.properties = properties; this.preview = preview; this.owners = owners; this.rent = rent;
        this.ledger = ledger; this.renewal = renewal; this.roots = roots; this.bindings = bindings; this.runtimes = runtimes;
        this.identities = identities; this.provisioners = provisioners; this.reconciler = reconciler;
        this.transactions = new TransactionTemplate(transactionManager);
        this.reads = new TransactionTemplate(transactionManager);
        this.reads.setReadOnly(true);
        this.reads.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.schemaEnabled = schemaEnabled;
    }

    /** Pre-payment target authority only. No installation, wallet or binding is created. */
    public HostingSetupService.View startSetup(Actor actor, String personaCode, String idempotencyKey, Map<String,String> body) {
        requireEnabled(actor); key(idempotencyKey); exact(personaCode, 100);
        var service = requireSetups();
        String mode = required(body, "mode");
        byte[] hash = hash("SETUP:" + personaCode, body);
        return transactions.execute(status -> {
            owners.requireOwner(actor); persona(personaCode);
            var replay = service.replay(actor, personaCode, idempotencyKey, hash);
            if (replay != null) return replay;
            String hostId=service.selectHost(actor,mode,body.get("hostId"));
            var retry=safeRetryBinding(actor,actor.ownerJiacn(),personaCode);
            // Self-hosted invitations allocate a new target, never a retained other-user installation.
            var released = retry==null && "server".equals(mode) ? releasedInstance(actor, personaCode) : null;
            String canonical = retry != null ? retry.getAgentId() : released == null ? "agt_" + UUID.randomUUID().toString().replace("-", "") : released.getAgentId();
            return service.create(actor, personaCode, idempotencyKey, hash, canonical, mode, hostId, System.currentTimeMillis());
        });
    }
    public HostingSetupService.View lookupSetup(Actor actor, String personaCode, String setupId) {
        requireEnabled(actor); var service = requireSetups();
        return transactions.execute(status -> { owners.requireOwner(actor); return service.lookup(actor, personaCode, setupId); });
    }
    public HostingSetupService.View cancelSetup(Actor actor, String personaCode, String setupId) {
        requireEnabled(actor); var service = requireSetups();
        return transactions.execute(status -> { owners.requireOwner(actor); return service.revoke(actor, personaCode, setupId, System.currentTimeMillis()); });
    }
    private HostingSetupService requireSetups() {
        if (setups == null) throw new HostingRentApplicationException(503, "HOSTING_SETUP_UNAVAILABLE");
        return setups;
    }
    private record SetupQuotePreparation(cn.jia.agent.mapper.hosting.HostingSetupStore.Setup target, boolean ready, QuoteView replay) { }
    private QuoteView quoteSetup(Actor actor, String personaCode, String idempotencyKey, Map<String,String> body) {
        String setupId = required(body, "setupId");
        byte[] hash = hash("INITIAL:" + personaCode, body);
        // Immutable metadata selects the plan; lock order remains plan -> persona -> grant.
        var prepared = transactions.execute(status -> {
            owners.requireOwner(actor);
            var replay = actorQuote(actor, idempotencyKey);
            if (replay != null) return new SetupQuotePreparation(null, true, initialQuoteReplay(actor, personaCode, idempotencyKey, hash, replay));
            var target = setups.find(actor, personaCode, setupId);
            return new SetupQuotePreparation(target, setups.requestInventory(actor, personaCode, setupId, idempotencyKey), null);
        });
        if (prepared.replay() != null) return prepared.replay();
        if (!prepared.ready()) throw new HostingRentApplicationException(409, "HOSTING_SETUP_INVENTORY_PENDING");
        var target = prepared.target();
        return transactions.execute(status -> {
            String owner = owners.requireOwner(actor);
            var replay = actorQuote(actor, idempotencyKey);
            if (replay != null) return initialQuoteReplay(actor, personaCode, idempotencyKey, hash, replay);
            EconomyHostingRentPlanEntity plan = plan(actor, "local".equals(target.mode())
                    ? AgentHostingRentProperties.SELF_HOSTED_PLAN_ID : AgentHostingRentProperties.MANAGED_PLAN_ID, personaCode);
            persona(personaCode);
            var retry=safeRetryBinding(actor,owner,personaCode);
            if (retry!=null && !retry.getAgentId().equals(target.canonicalAgentId())) throw forbidden();
            if (body.get("agentId") != null && !body.get("agentId").equals(target.canonicalAgentId())) throw forbidden();
            if (retry==null && "server".equals(target.mode())) {
                var released = releasedInstance(actor, personaCode);
                if (released != null && !released.getAgentId().equals(target.canonicalAgentId())) throw conflict("HOSTING_RENT_QUOTE_STALE");
            }
            var actual = setups.inventory(actor, personaCode, setupId, idempotencyKey);
            var inventory = actual.inventory();
            var receipt = ledger.quote(quoteCommand(actor, idempotencyKey, hash, personaCode,
                    target.canonicalAgentId(), plan.getPlanId(), plan.getPlanVersion(), HostingRentQuotePurpose.INITIAL, null, null,
                    new HostingRentInitialSkills(inventory.installedSkillCount(), inventory.inventorySha256(), inventory.hostId(), inventory.snapshotJson())));
            setups.attachQuote(actual, receipt.quoteId());
            return quoteView(receipt);
        });
    }

    private record InitialQuoteSubject(String owner, String agentId, QuoteView replay) { }

    public QuoteView quote(Actor actor, String personaCode, String idempotencyKey, Map<String, String> body) {
        requireEnabled(actor);
        key(idempotencyKey); exact(personaCode, 100);
        if (!"INITIAL".equals(body.get("purpose"))) throw badRequest();
        if (setups != null && body.get("setupId") != null) return quoteSetup(actor, personaCode, idempotencyKey, body);
        if (body.get("setupId") != null) throw new HostingRentApplicationException(503, "HOSTING_SETUP_UNAVAILABLE");
        String requestedAgent = body.get("agentId");
        if (requestedAgent != null) requireAgentId(requestedAgent);
        byte[] hash = hash("INITIAL:" + personaCode, body);
        InitialQuoteSubject subject = transactions.execute(status -> {
            String owner = owners.requireOwner(actor);
            var replay = actorQuote(actor, idempotencyKey);
            if (replay != null) return new InitialQuoteSubject(owner, replay.getAgentId(), initialQuoteReplay(actor, personaCode, idempotencyKey, hash, replay));
            requireProvisioner(actor, owner);
            return new InitialQuoteSubject(owner, resolveInitialAgent(actor, owner, personaCode, requestedAgent, null), null);
        });
        if (subject.replay() != null) return subject.replay();
        var inventory = requireInventory(actor, subject.owner(), subject.agentId());
        String deploymentMode = deploymentMode(actor, subject.owner(), inventory.hostId());
        return transactions.execute(status -> {
            String owner = owners.requireOwner(actor);
            if (!owner.equals(subject.owner())) throw forbidden();
            var replay = actorQuote(actor, idempotencyKey);
            if (replay != null) return initialQuoteReplay(actor, personaCode, idempotencyKey, hash, replay);
            // Final short transaction keeps plan -> persona/binding -> quote ordering.
            EconomyHostingRentPlanEntity plan = plan(actor, "local".equals(deploymentMode)
                    ? AgentHostingRentProperties.SELF_HOSTED_PLAN_ID : AgentHostingRentProperties.MANAGED_PLAN_ID, personaCode);
            String canonical = resolveInitialAgent(actor, owner, personaCode, requestedAgent, subject.agentId());
            if (!canonical.equals(subject.agentId())) throw conflict("HOSTING_RENT_QUOTE_STALE");
            return quoteView(ledger.quote(quoteCommand(actor, idempotencyKey, hash, personaCode,
                    canonical, plan.getPlanId(), plan.getPlanVersion(), HostingRentQuotePurpose.INITIAL, null, null,
                    new HostingRentInitialSkills(inventory.installedSkillCount(), inventory.inventorySha256(), inventory.hostId(), inventory.snapshotJson()))));
        });
    }

    private QuoteView initialQuoteReplay(Actor actor, String persona, String key, byte[] hash, EconomyHostingRentQuoteEntity quote) {
        return quoteView(ledger.quote(quoteCommand(actor, key, hash, persona, quote.getAgentId(), quote.getPlanId(),
                quote.getPlanVersion(), HostingRentQuotePurpose.INITIAL, null, null, initialSkills(quote))));
    }
    private static HostingRentInitialSkills initialSkills(EconomyHostingRentQuoteEntity quote) {
        return new HostingRentInitialSkills(quote.getInitialSkillCount(), quote.getInventorySha256(), quote.getInventoryHostId(), quote.getInventorySnapshotJson());
    }
    private String resolveInitialAgent(Actor actor, String owner, String personaCode, String requestedAgent, String proposed) {
        persona(personaCode);
        var existing = currentBinding(actor, owner, personaCode);
        if (existing != null) {
            if (requestedAgent == null || !requestedAgent.equals(existing.getAgentId()))
                throw conflict("HOSTING_RENT_AGENT_CONFIRMATION_REQUIRED");
            requireExisting(actor, owner, existing.getAgentId(), personaCode);
            if (rent.selectLiveLeaseByAgentForUpdate(actor.tenantId(), actor.clientId(), existing.getAgentId()) != null)
                throw conflict("HOSTING_RENT_LEASE_CONFLICT");
            return existing.getAgentId();
        }
        var released = releasedInstance(actor, personaCode);
        if (released != null) {
            if (requestedAgent != null && !requestedAgent.equals(released.getAgentId())) throw forbidden();
            return released.getAgentId();
        }
        if (requestedAgent != null) throw forbidden();
        return proposed == null ? "agt_" + UUID.randomUUID().toString().replace("-", "") : proposed;
    }
    private ManagedHostingProvisioner.SkillInventory requireInventory(Actor actor, String owner, String agent) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Hosting inventory I/O inside transaction");
        ManagedHostingProvisioner provider = provisioners.getIfAvailable();
        var inventory = provider == null ? null : provider.inventory(actor.tenantId(), actor.clientId(), owner, agent);
        if (inventory == null) throw new HostingRentApplicationException(503, "HOSTING_RENT_NOT_READY");
        return inventory;
    }

    public BindView bind(Actor actor, String personaCode, String idempotencyKey, Map<String, String> body) {
        requireEnabled(actor);
        key(idempotencyKey); exact(personaCode, 100);
        if (!"server".equals(body.get("mode")) && !"local".equals(body.get("mode"))) throw badRequest();
        if ("REPROVISION".equals(body.get("hostingAction"))) return reprovision(actor, personaCode, idempotencyKey, body);
        if (!"INITIAL".equals(body.get("hostingAction"))) throw badRequest();
        if (!Set.of("mode", "hostingAction", "agentId", "quoteId", "expectedPlanVersion",
                "expectedAmountWen", "expectedPeriodSeconds").containsAll(body.keySet())) throw badRequest();
        requireAgentId(required(body, "agentId"));
        byte[] hash = hash("INITIAL-CONFIRM:" + personaCode, body);
        InitialAdmission admission = initialAdmission(actor, personaCode, idempotencyKey, body, hash);
        if (admission.replay() != null) return admission.replay();
        var target = setups == null ? null : transactions.execute(status -> setups.quoteTarget(actor, personaCode, admission.quote().getQuoteId()));
        ManagedHostingProvisioner.SkillInventory inventory;
        if (target != null) {
            boolean ready = Boolean.TRUE.equals(transactions.execute(status -> setups.requestInventory(actor, personaCode, target.setupId(), idempotencyKey)));
            if (!ready) throw new HostingRentApplicationException(409, "HOSTING_SETUP_INVENTORY_PENDING");
            inventory = transactions.execute(status -> setups.inventory(actor, personaCode, target.setupId(), idempotencyKey).inventory());
        } else {
            try { inventory = requireInventory(actor, admission.owner(), admission.quote().getAgentId()); }
            catch (HostingRentApplicationException unavailable) {
                var replay = initialAdmission(actor, personaCode, idempotencyKey, body, hash).replay();
                if (replay != null) return replay;
                throw unavailable;
            }
        }
        return transactions.execute(status -> {
            String owner = owners.requireOwner(actor);
            if (!admission.owner().equals(owner)) throw forbidden();
            EconomyHostingRentQuoteEntity quote = ownedQuote(actor, required(body, "quoteId"));
            confirmation(quote, body, personaCode, "INITIAL");
            EconomyHostingProvisioningIntentEntity prior = rent.selectIntentByQuoteForUpdate(
                    actor.tenantId(), actor.clientId(), quote.getQuoteId());
            if (prior != null) return mutationView(quote.getAgentId(), ledger.reserve(
                    new HostingRentReserveCommand(actor.scope(), actor.principal(), idempotencyKey, hash, quote.getQuoteId())));
            if (!inventory.inventorySha256().equals(quote.getInventorySha256())
                    || !inventory.hostId().equals(quote.getInventoryHostId())
                    || !AgentHostingRentProperties.modeForPlan(quote.getPlanId()).equals(target != null ? target.mode() : deploymentMode(actor, owner, inventory.hostId())))
                throw conflict("HOSTING_RENT_QUOTE_STALE");
            requireProvisioner(actor, owner);
            AgentPersonaEntity persona = persona(personaCode);
            AgentPersonaBindingEntity binding = currentBinding(actor, owner, personaCode);
            if (binding != null) requireExisting(actor, owner, quote.getAgentId(), personaCode);
            AgentRuntimeEntity released = binding == null && (target == null || "server".equals(target.mode())) ? releasedInstance(actor, personaCode) : null;
            if (released != null && !quote.getAgentId().equals(released.getAgentId()))
                throw conflict("HOSTING_RENT_QUOTE_STALE");
            // Quote/persona/binding roots are locked before REQUIRED W02 funds posting.
            HostingRentMutationReceipt receipt = ledger.reserve(new HostingRentReserveCommand(
                    actor.scope(), actor.principal(), idempotencyKey, hash, quote.getQuoteId()));
            if (binding == null) {
                binding = new AgentPersonaBindingEntity();
                binding.setClientId(actor.clientId()); binding.setTenantId(actor.tenantId()); binding.setJiacn(owner);
                binding.setPersonaCode(personaCode); binding.setAgentId(quote.getAgentId());
                binding.setBoundAt(receipt.occurredAt()); binding.setStatus(AgentConstants.BINDING_STATUS_ACTIVE);
                if (bindings.insert(binding) != 1) throw conflict("HOSTING_RENT_CONFLICT");
                String auditReason = "R01 paid admission: " + receipt.intentId();
                AgentIdentityRegistryEntity identity = released == null
                        ? identities.provisionOpaqueIdentity(binding, auditReason)
                        : identities.provisionRetainedIdentity(binding, auditReason);
                if (!quote.getAgentId().equals(identity.getCanonicalAgentId())
                        || !actor.tenantId().equals(identity.getTenantId()) || !owner.equals(identity.getOwnerJiacn())) {
                    throw forbidden();
                }
                if (released != null) {
                    if (runtimes.attachReleasedBinding(released.getId(), binding.getId(), owner, receipt.occurredAt()) != 1)
                        throw conflict("HOSTING_RENT_CONFLICT");
                } else {
                    AgentRuntimeEntity runtime = new AgentRuntimeEntity();
                    runtime.setAgentId(identity.getCanonicalAgentId()); runtime.setBindingId(binding.getId());
                    runtime.setTenantId(actor.tenantId()); runtime.setClientId(actor.clientId()); runtime.setOwnerJiacn(owner);
                    runtime.setPersonaCode(personaCode); runtime.setPersonaName(persona.getName()); runtime.setName(persona.getName());
                    runtime.setAvatar(persona.getAvatar()); runtime.setAbilities(persona.getAbilities());
                    runtime.setStatus(AgentConstants.STATUS_OFFLINE); runtime.setLastSeenAt(receipt.occurredAt());
                    if (runtimes.insert(runtime) != 1) throw conflict("HOSTING_RENT_CONFLICT");
                }
            }
            if (binding.getId() == null || rent.attachBinding(actor.tenantId(), actor.clientId(), receipt.leaseId(),
                    binding.getId().toString()) != 1) throw conflict("HOSTING_RENT_CONFLICT");
            if (target != null) {
                var lockedTarget = setups.quoteTarget(actor, personaCode, quote.getQuoteId());
                if (lockedTarget == null || !lockedTarget.setupId().equals(target.setupId())) throw forbidden();
                setups.activate(actor, lockedTarget, binding.getId().toString(), receipt.leaseId(), receipt.occurredAt());
            }
            reconciler.wakeInitial(actor.clientId(), receipt.intentId());
            return mutationView(quote.getAgentId(), receipt);
        });
    }

    private record InitialAdmission(String owner, EconomyHostingRentQuoteEntity quote, MutationView replay) { }
    private InitialAdmission initialAdmission(Actor actor, String personaCode, String idempotencyKey,
            Map<String, String> body, byte[] hash) {
        return transactions.execute(status -> {
            String owner = owners.requireOwner(actor);
            var quote = ownedQuote(actor, required(body, "quoteId"));
            confirmation(quote, body, personaCode, "INITIAL");
            var prior = rent.selectIntentByQuoteForUpdate(actor.tenantId(), actor.clientId(), quote.getQuoteId());
            var replay = prior == null ? null : mutationView(quote.getAgentId(), ledger.reserve(new HostingRentReserveCommand(
                    actor.scope(), actor.principal(), idempotencyKey, hash, quote.getQuoteId())));
            return new InitialAdmission(owner, quote, replay);
        });
    }

    public QuoteView renewalQuote(Actor actor, String leaseId, String idempotencyKey, Map<String, String> body) {
        requireEnabled(actor); key(idempotencyKey); exact(leaseId, 100);
        String agentId = required(body, "agentId"); requireAgentId(agentId);
        long version = positive(required(body, "expectedLeaseVersion"));
        byte[] hash = hash("RENEWAL:" + leaseId, body);
        return transactions.execute(status -> {
            String owner = owners.requireOwner(actor);
            EconomyHostingRentQuoteEntity replay = actorQuote(actor, idempotencyKey);
            if (replay != null) return quoteView(ledger.quote(quoteCommand(actor, idempotencyKey, hash,
                    replay.getPersonaCode(), agentId, replay.getPlanId(), replay.getPlanVersion(),
                    HostingRentQuotePurpose.RENEWAL, leaseId, version, null)));
            // Non-locking scoped discovery chooses the immutable plan while preserving plan -> binding -> lease lock order.
            var original = rent.selectLease(actor.tenantId(), actor.clientId(), leaseId);
            if (original == null || !agentId.equals(original.getAgentId()) || !actor.actorId().equals(original.getPrincipalId())
                    || !"USER".equals(original.getPrincipalType())) throw forbidden();
            EconomyHostingRentPlanEntity plan = rent.selectPlanForUpdate(actor.tenantId(), actor.clientId(),
                    original.getPlanId(), original.getPlanVersion());
            if (plan == null || !Objects.equals(plan.getAmountWen(), original.getAmountWen())
                    || !Objects.equals(plan.getPeriodSeconds(), original.getPeriodSeconds())
                    || !"ACTIVE".equals(plan.getStatus())) throw conflict("HOSTING_RENT_PLAN_VERSION_CONFLICT");
            var binding = requireExisting(actor, owner, agentId, null);
            EconomyHostingLeaseEntity lease = ownedLease(actor, leaseId, agentId);
            if (!binding.getPersonaCode().equals(lease.getPersonaCode())) throw forbidden();
            return quoteView(ledger.quote(quoteCommand(actor, idempotencyKey, hash, lease.getPersonaCode(), agentId,
                    plan.getPlanId(), plan.getPlanVersion(), HostingRentQuotePurpose.RENEWAL, leaseId, version, null)));
        });
    }

    public MutationView renew(Actor actor, String leaseId, String idempotencyKey, Map<String, String> body) {
        requireEnabled(actor); key(idempotencyKey); exact(leaseId, 100);
        byte[] hash = hash("RENEWAL-CONFIRM:" + leaseId, body);
        return transactions.execute(status -> {
            String owner = owners.requireOwner(actor);
            EconomyHostingRentQuoteEntity quote = ownedQuote(actor, required(body, "quoteId"));
            confirmation(quote, body, quote.getPersonaCode(), "RENEWAL");
            if (!leaseId.equals(quote.getLeaseId())
                    || !Objects.equals(quote.getExpectedLeaseVersion(), positive(required(body, "expectedLeaseVersion")))) throw badRequest();
            EconomyHostingProvisioningIntentEntity prior = rent.selectIntentByQuoteForUpdate(
                    actor.tenantId(), actor.clientId(), quote.getQuoteId());
            if (prior == null) {
                requireExisting(actor, owner, quote.getAgentId(), quote.getPersonaCode());
                ownedLease(actor, leaseId, quote.getAgentId());
            }
            return mutationView(quote.getAgentId(), ledger.renew(new HostingRentReserveCommand(
                    actor.scope(), actor.principal(), idempotencyKey, hash, quote.getQuoteId())));
        });
    }

    /** Stop the exact invitation's financial lifecycle, not its execution identity.
     * Binding/identity -> lease -> schedule matches skill admission; no task or provider I/O.
     * The retained ACTIVE identity permits already accepted work until private handover finishes. */
    public RemovalView remove(Actor actor, String personaCode, String agentId, String leaseId) {
        if (!schemaEnabled) throw new HostingRentApplicationException(503, "HOSTING_RENT_NOT_READY");
        preview.requireMutationAllowed(actor.tenantId(), actor.clientId());
        exact(personaCode, 100); requireAgentId(agentId); exact(leaseId, 100);
        return transactions.execute(status -> {
            String owner = owners.requireOwner(actor);
            var identity = identities.lockRegistrationIdentityInScope(
                    actor.tenantId(), actor.clientId(), owner, agentId);
            var binding = identities.requireActiveBinding(identity, null);
            if (!agentId.equals(identity.getCanonicalAgentId()) || !personaCode.equals(binding.getPersonaCode()))
                throw forbidden();
            var lease = ownedLease(actor, leaseId, agentId);
            if (!personaCode.equals(lease.getPersonaCode())
                    || !Objects.equals(Long.toString(binding.getId()), lease.getBindingId())) throw forbidden();
            var result = renewal.stopRenewal(actor.scope(), actor.principal(), leaseId);
            return new RemovalView(agentId, leaseId, result.status());
        });
    }

    public record RemovalView(String agentId, String leaseId, String status) { }

    public LeaseView lookup(Actor actor, String agentId) {
        requireEnabled(actor); requireAgentId(agentId);
        return reads.execute(status -> {
            String owner = owners.requireOwner(actor);
            var identity = identities.requireRegistrationIdentityInScope(actor.tenantId(), actor.clientId(), owner, agentId);
            if (!agentId.equals(identity.getCanonicalAgentId())) throw forbidden();
            EconomyHostingLeaseEntity latest = rent.selectLatestLease(actor.tenantId(), actor.clientId(), agentId);
            if (latest == null) return new LeaseView(false, null, null, "NOT_MANAGED");
            EconomyHostingLeaseEntity lease = latest;
            if (!actor.actorId().equals(lease.getPrincipalId()) || !"USER".equals(lease.getPrincipalType())) throw forbidden();
            EconomyHostingProvisioningIntentEntity intent = rent.selectIntent(
                    actor.tenantId(), actor.clientId(), lease.getLatestIntentId());
            if (intent == null || !actor.actorId().equals(intent.getPrincipalId())) throw forbidden();
            String admission = switch (lease.getStatus()) {
                case "ACTIVE" -> lease.getPaidThrough() > System.currentTimeMillis() ? "ALLOWED" : "RENEWAL_REQUIRED";
                case "LOCKED" -> "LOCKED";
                case "FINISHING" -> "FINISHING";
                case "REFUNDED" -> "INITIAL_REQUIRED";
                default -> "PENDING";
            };
            return new LeaseView(true, new LeaseSnapshot(lease.getLeaseId(), agentId, lease.getPersonaCode(),
                    lease.getBindingId(), number(lease.getVersion()), lease.getStatus(), number(lease.getPlanVersion()),
                    number(lease.getAmountWen()), number(lease.getPeriodSeconds()), number(lease.getPaidFrom()), number(lease.getPaidThrough()), intent.getSettlementKind(), lease.getPlanId()),
                    new IntentSnapshot(intent.getIntentId(), number(intent.getVersion()), intent.getStatus(),
                            number(intent.getServiceReadyAt()), intent.getReserveTransactionId(), intent.getCaptureTransactionId(),
                            intent.getRefundTransactionId()), admission, reprovisionSnapshot(rent.selectLatestReprovision(
                            actor.tenantId(), actor.clientId(), lease.getLeaseId())));
        });
    }

    private ReprovisionView reprovision(Actor actor, String personaCode, String idempotencyKey, Map<String, String> body) {
        if (!Set.of("mode", "hostingAction", "agentId", "leaseId", "expectedLeaseVersion")
                .containsAll(body.keySet())) throw badRequest();
        String agentId = required(body, "agentId"); requireAgentId(agentId);
        String leaseId = required(body, "leaseId");
        long version = positive(required(body, "expectedLeaseVersion"));
        byte[] requestHash = hash("REPROVISION:" + personaCode, body);
        String owner = owners.requireOwner(actor);
        byte[] keyBytes = idempotencyKey.getBytes(StandardCharsets.US_ASCII);
        // Immutable receipt replay precedes provider availability, expiry and later lease versions.
        var replay = rent.selectReprovisionReplay(actor.tenantId(), actor.clientId(), actor.actorId(), keyBytes);
        if (replay != null) return reprovisionReceipt(replay, requestHash);
        ManagedHostingProvisioner provider = provisioners.getIfAvailable();
        if (provider == null || !provider.probeCapabilities(actor.tenantId(), actor.clientId(), owner, agentId)) throw new HostingRentApplicationException(503, "HOSTING_RENT_REPROVISION_NOT_READY");
        return transactions.execute(status -> {
            if (!owner.equals(owners.requireOwner(actor))) throw forbidden();
            var initial = rent.selectInitialIntent(actor.tenantId(), actor.clientId(), leaseId);
            if (initial == null) throw forbidden();
            // Shared binding fence precedes financial roots, as in removal and new skill admission.
            var binding = requireExisting(actor, owner, agentId, personaCode);
            initial = rent.selectIntentForUpdate(actor.tenantId(), actor.clientId(), initial.getIntentId());
            EconomyHostingLeaseEntity lease = ownedLease(actor, leaseId, agentId);
            var prior = rent.selectReprovisionReplayForUpdate(actor.tenantId(), actor.clientId(), actor.actorId(), keyBytes);
            if (prior != null) return reprovisionReceipt(prior, requestHash);
            long now = System.currentTimeMillis();
            if (initial == null || !"ACTIVE".equals(initial.getStatus()) || !"USER".equals(initial.getPrincipalType())
                    || !actor.actorId().equals(initial.getPrincipalId()) || !agentId.equals(initial.getAgentId())
                    || !"ACTIVE".equals(lease.getStatus()) || lease.getPaidThrough() == null || lease.getPaidThrough() <= now
                    || lease.getVersion() != version || !personaCode.equals(lease.getPersonaCode())) {
                throw conflict("HOSTING_RENT_LEASE_CONFLICT");
            }
            if (!Objects.equals(lease.getBindingId(), binding.getId().toString())) throw forbidden();
            if (!AgentHostingRentProperties.modeForPlan(lease.getPlanId()).equals(body.get("mode"))) throw badRequest();
            if (rent.selectLiveReprovisionForUpdate(actor.tenantId(), actor.clientId(), leaseId) != null) {
                throw conflict("HOSTING_RENT_INTENT_CONFLICT");
            }
            EconomyHostingReprovisionEntity request = new EconomyHostingReprovisionEntity()
                    .setRequestId("hrr-" + UUID.randomUUID()).setLeaseId(leaseId).setIntentId(initial.getIntentId())
                    .setAgentId(agentId).setPersonaCode(personaCode).setPrincipalId(actor.actorId())
                    .setIdempotencyKey(keyBytes).setRequestHash(requestHash).setLeaseVersion(Math.addExact(version, 1))
                    .setPaidThrough(lease.getPaidThrough()).setRequestedAt(now).setStatus("ACCEPTED").setVersion(1L)
                    .setTenantId(actor.tenantId()).setClientId(actor.clientId());
            if (rent.acceptReprovision(actor.tenantId(), actor.clientId(), leaseId, version, now) != 1
                    || rent.insertReprovision(request) != 1) throw conflict("HOSTING_RENT_CONCURRENCY_CONFLICT");
            reconciler.wakeReprovision(actor.clientId(), request.getRequestId());
            // Deliberately NO ledger call, no profile I/O and no change to price/paid periods/latest paid intent.
            return reprovisionReceipt(request, requestHash);
        });
    }

    private static ReprovisionView reprovisionReceipt(EconomyHostingReprovisionEntity row, byte[] hash) {
        if (!java.security.MessageDigest.isEqual(row.getRequestHash(), hash)) throw conflict("IDEMPOTENCY_CONFLICT");
        return new ReprovisionView(row.getRequestId(), row.getAgentId(), row.getLeaseId(), "REPROVISION", "ACCEPTED",
                "WEN", "0", number(row.getLeaseVersion()), number(row.getPaidThrough()), number(row.getRequestedAt()));
    }

    private static ReprovisionSnapshot reprovisionSnapshot(EconomyHostingReprovisionEntity row) {
        return row == null ? null : new ReprovisionSnapshot(row.getRequestId(), number(row.getVersion()), row.getStatus(),
                number(row.getRequestedAt()), number(row.getServiceReadyAt()));
    }

    private void requireEnabled(Actor actor) {
        if (!properties.enabled()) throw new HostingRentApplicationException(503, "HOSTING_RENT_NOT_CONFIGURED");
        if (!schemaEnabled) throw new HostingRentApplicationException(503, "HOSTING_RENT_NOT_READY");
        preview.requireMutationAllowed(actor.tenantId(), actor.clientId());
    }

    private void requireProvisioner(Actor actor, String owner) {
        ManagedHostingProvisioner provider = provisioners.getIfAvailable();
        if (provider == null || !provider.availableFor(actor.tenantId(), actor.clientId(), owner)) throw new HostingRentApplicationException(503, "HOSTING_RENT_NOT_READY");
    }

    private AgentPersonaEntity persona(String code) {
        AgentPersonaEntity persona = roots.lockPersona(code);
        if (persona == null || !code.equals(persona.getPersonaCode()) || !Boolean.TRUE.equals(persona.getActive())
                || Boolean.TRUE.equals(persona.getSystemAgent())) throw forbidden();
        return persona;
    }

    private AgentRuntimeEntity releasedInstance(Actor actor, String personaCode) {
        var candidates = roots.lockPublicInstances(actor.tenantId(), actor.clientId(), personaCode);
        if (candidates.isEmpty()) return null;
        if (candidates.size() != 1) throw conflict("HOSTING_RENT_INSTANCE_CONFLICT");
        var runtime = candidates.getFirst();
        if (runtime.getId() == null || runtime.getBindingId() != null || runtime.getOwnerJiacn() != null
                || !renewal.completedHandover(actor.scope(), runtime.getAgentId(), runtime.getRuntimeInstallationId()))
            throw conflict("HOSTING_RENT_HANDOVER_PENDING");
        requireAgentId(runtime.getAgentId());
        return runtime;
    }

    private AgentPersonaBindingEntity safeRetryBinding(Actor actor, String owner, String persona) {
        var binding=currentBinding(actor,owner,persona);
        if (binding==null) return null;
        requireExisting(actor,owner,binding.getAgentId(),persona);
        var lease=rent.selectLatestLeaseForUpdate(actor.tenantId(),actor.clientId(),binding.getAgentId());
        var intent=lease==null ? null : rent.selectIntent(actor.tenantId(),actor.clientId(),lease.getLatestIntentId());
        if (lease==null || !"REFUNDED".equals(lease.getStatus()) || intent==null || !"INITIAL".equals(intent.getQuotePurpose())
                || !"REFUNDED".equals(intent.getStatus()) || intent.getCaptureTransactionId()!=null || intent.getServiceReadyAt()!=null
                || intent.getRuntimeInstallationId()!=null || intent.getRuntimeManifestSha256()!=null
                || !Long.valueOf(0).equals(intent.getRuntimeProvisionGeneration())) throw conflict("HOSTING_RENT_LEASE_CONFLICT");
        return binding;
    }
    private AgentPersonaBindingEntity currentBinding(Actor actor, String owner, String personaCode) {
        var found = roots.lockBindings(actor.clientId(), personaCode);
        if (found.size() > 1) throw forbidden();
        if (found.isEmpty()) return null;
        AgentPersonaBindingEntity binding = found.getFirst();
        if (!owner.equals(binding.getJiacn()) || !actor.clientId().equals(binding.getClientId())
                || !personaCode.equals(binding.getPersonaCode())) throw forbidden();
        return binding;
    }

    private AgentPersonaBindingEntity requireExisting(Actor actor, String owner, String agentId, String personaCode) {
        AgentIdentityRegistryEntity identity = identities.requireRegistrationIdentityInScope(
                actor.tenantId(), actor.clientId(), owner, agentId);
        if (!agentId.equals(identity.getCanonicalAgentId())) throw forbidden();
        AgentPersonaBindingEntity locked = bindings.findByIdForUpdate(identity.getBindingId());
        if (locked == null || !Integer.valueOf(AgentConstants.BINDING_STATUS_ACTIVE).equals(locked.getStatus())
                || !agentId.equals(locked.getAgentId()) || !actor.clientId().equals(locked.getClientId())
                || !owner.equals(locked.getJiacn())) throw forbidden();
        if (personaCode != null && !personaCode.equals(locked.getPersonaCode())) throw forbidden();
        return locked;
    }

    private EconomyHostingRentQuoteEntity actorQuote(Actor actor, String key) {
        return rent.selectQuoteByActorKeyForUpdate(actor.tenantId(), actor.clientId(), "USER", actor.actorId(),
                key.getBytes(StandardCharsets.US_ASCII));
    }

    private EconomyHostingRentQuoteEntity ownedQuote(Actor actor, String quoteId) {
        EconomyHostingRentQuoteEntity quote = rent.selectQuoteForUpdate(actor.tenantId(), actor.clientId(), quoteId);
        if (quote == null || !actor.actorId().equals(quote.getPrincipalId()) || !"USER".equals(quote.getPrincipalType())) throw forbidden();
        return quote;
    }

    private EconomyHostingLeaseEntity ownedLease(Actor actor, String leaseId, String agentId) {
        EconomyHostingLeaseEntity lease = rent.selectLeaseForUpdate(actor.tenantId(), actor.clientId(), leaseId);
        if (lease == null || !actor.actorId().equals(lease.getPrincipalId()) || !agentId.equals(lease.getAgentId())
                || !"USER".equals(lease.getPrincipalType())) throw forbidden();
        return lease;
    }

    private String deploymentMode(Actor actor, String owner, String hostId) {
        var provider = provisioners.getIfAvailable();
        String mode = provider == null ? null : provider.deploymentMode(actor.tenantId(), actor.clientId(), owner, hostId);
        if (!"server".equals(mode) && !"local".equals(mode)) throw new HostingRentApplicationException(503, "HOSTING_RENT_NOT_READY");
        return mode;
    }
    private EconomyHostingRentPlanEntity plan(Actor actor, String planId, String personaCode) {
        String mode;
        try { mode = AgentHostingRentProperties.modeForPlan(planId); }
        catch (IllegalArgumentException unknown) { throw conflict("HOSTING_RENT_PLAN_VERSION_CONFLICT"); }
        if ("server".equals(mode)) {
            var selected = modelPrices == null ? null : modelPrices.find(actor.tenantId(), actor.clientId(), personaCode);
            if (selected == null) throw new HostingRentApplicationException(503, "HOSTING_RENT_NOT_CONFIGURED");
            if (!AgentHostingRentProperties.MANAGED_PLAN_ID.equals(selected.planId()) || selected.planVersion() <= 0
                    || selected.amountWen() <= 0 || selected.periodSeconds() <= 0
                    || !"WEN".equals(selected.currency()) || !"ACTIVE".equals(selected.status()))
                throw conflict("HOSTING_RENT_PLAN_VERSION_CONFLICT");
            // Configuration selects a version once; the existing immutable plan is then locked before binding/quote.
            var stored = rent.selectPlanForUpdate(actor.tenantId(), actor.clientId(), selected.planId(), selected.planVersion());
            if (stored == null || !Objects.equals(stored.getAmountWen(), selected.amountWen())
                    || !Objects.equals(stored.getPeriodSeconds(), selected.periodSeconds())
                    || !"ACTIVE".equals(stored.getStatus())) throw conflict("HOSTING_RENT_PLAN_VERSION_CONFLICT");
            return stored;
        }
        long version = positive(properties.selfHostedPlanVersion());
        long amount = positive(properties.selfHostedAmountWen());
        EconomyHostingRentPlanEntity plan = rent.selectPlanForUpdate(actor.tenantId(), actor.clientId(), planId, version);
        if (plan == null) {
            EconomyHostingRentPlanEntity proposed = new EconomyHostingRentPlanEntity().setPlanId(planId)
                    .setPlanVersion(version).setAmountWen(amount)
                    .setPeriodSeconds(positive(properties.periodSeconds())).setQuoteTtlSeconds(300L)
                    .setCurrency("WEN").setStatus("ACTIVE").setTenantId(actor.tenantId()).setClientId(actor.clientId())
                    .setCreateTime(System.currentTimeMillis());
            try { rent.insertPlanVersion(proposed); }
            catch (DataIntegrityViolationException concurrent) { /* Compare immutable winner below, never rewrite it. */ }
            plan = rent.selectPlanForUpdate(actor.tenantId(), actor.clientId(), planId, version);
        }
        if (plan == null || plan.getAmountWen() != amount
                || plan.getPeriodSeconds() != positive(properties.periodSeconds()) || !"ACTIVE".equals(plan.getStatus())) {
            throw conflict("HOSTING_RENT_PLAN_VERSION_CONFLICT");
        }
        return plan;
    }

    private void confirmation(EconomyHostingRentQuoteEntity quote, Map<String, String> body, String persona, String purpose) {
        if ("INITIAL".equals(purpose) && !AgentHostingRentProperties.modeForPlan(quote.getPlanId()).equals(body.get("mode"))) throw badRequest();
        if (!purpose.equals(quote.getQuotePurpose()) || !persona.equals(quote.getPersonaCode())
                || !quote.getAgentId().equals(required(body, "agentId"))
                || quote.getPlanVersion() != positive(required(body, "expectedPlanVersion"))
                || quote.getAmountWen() != positive(required(body, "expectedAmountWen"))
                || quote.getPeriodSeconds() != positive(required(body, "expectedPeriodSeconds"))) throw badRequest();
    }

    private HostingRentQuoteCommand quoteCommand(Actor actor, String key, byte[] hash, String persona, String agent,
            String plan, long version, HostingRentQuotePurpose purpose, String lease, Long leaseVersion, HostingRentInitialSkills skills) {
        return new HostingRentQuoteCommand(actor.scope(), actor.principal(), key, hash, purpose,
                plan, version, persona, agent, lease, leaseVersion, skills);
    }
    /** Existing identity syntax is owned by the registry, not by the new-ID generator.
     * Never resolve aliases here: the scoped registry result must equal the requested ID. */
    private static void requireAgentId(String agentId) {
        try { exact(agentId, 100); }
        catch (IllegalArgumentException invalid) { throw badRequest(); }
    }
    private static HostingRentApplicationException forbidden() { return new HostingRentApplicationException(404, "HOSTING_RENT_NOT_FOUND_OR_FORBIDDEN"); }
    private static HostingRentApplicationException conflict(String code) { return new HostingRentApplicationException(409, code); }
    private static String number(Long value) { return value == null ? null : value.toString(); }
    private static QuoteView quoteView(HostingRentQuoteReceipt quote) {
        return new QuoteView(quote.quoteId(), quote.purpose().name(), quote.personaCode(), quote.agentId(), quote.leaseId(),
                number(quote.expectedLeaseVersion()), quote.planId(), number(quote.planVersion()), "WEN",
                number(quote.amountWen()), number(quote.periodSeconds()), number(quote.expiresAt()),
                number(quote.recurringAmountWen()), number(quote.initialSkills() == null ? 0L : quote.initialSkills().count()),
                number(quote.amountWen() - quote.recurringAmountWen()));
    }
    private static MutationView mutationView(String agent, HostingRentMutationReceipt receipt) {
        return new MutationView(agent, receipt.intentId(), receipt.leaseId(), receipt.quoteId(), receipt.transactionId(),
                receipt.status(), "WEN", number(receipt.amountWen()), number(receipt.periodSeconds()), number(receipt.occurredAt()));
    }
    public record QuoteView(String quoteId, String purpose, String personaCode, String agentId, String leaseId,
                            String expectedLeaseVersion, String planId, String planVersion, String currency,
                            String amountWen, String periodSeconds, String expiresAt, String recurringAmountWen, String initialSkillCount, String initialSkillAmountWen) { }
    public sealed interface BindView permits MutationView, ReprovisionView {
        String status();
        String amountWen();
    }
    public record ReprovisionView(String requestId, String agentId, String leaseId, String operation, String status,
                                 String currency, String amountWen, String leaseVersion, String paidThrough, String occurredAt) implements BindView { }
    public record ReprovisionSnapshot(String requestId, String version, String status, String requestedAt, String serviceReadyAt) { }
    public record MutationView(String agentId, String intentId, String leaseId, String quoteId, String transactionId,
                               String status, String currency, String amountWen, String periodSeconds, String occurredAt) implements BindView { }
    public record LeaseView(boolean managed, LeaseSnapshot lease, IntentSnapshot intent, String admission, ReprovisionSnapshot reprovision) {
        public LeaseView(boolean managed, LeaseSnapshot lease, IntentSnapshot intent, String admission) {
            this(managed, lease, intent, admission, null);
        }
    }
    public record LeaseSnapshot(String leaseId, String agentId, String personaCode, String bindingId, String version,
                                String status, String planVersion, String amountWen, String periodSeconds,
                                String paidFrom, String paidThrough, String settlementKind, String planId) { }
    public record IntentSnapshot(String intentId, String version, String status, String serviceReadyAt,
                                 String reserveTransactionId, String captureTransactionId, String refundTransactionId) { }
}
