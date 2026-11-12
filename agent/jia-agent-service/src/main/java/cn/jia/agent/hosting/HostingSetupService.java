package cn.jia.agent.hosting;

import cn.jia.agent.config.HostingSetupProperties;
import cn.jia.agent.mapper.hosting.HostingOutboundStore;
import cn.jia.agent.mapper.hosting.HostingSetupStore;
import cn.jia.agent.mapper.hosting.HostingSetupStore.Setup;
import cn.jia.core.entity.ProtocolMessage;
import cn.jia.core.security.FixedTenantAdmissionPolicy;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import static cn.jia.agent.hosting.HostingRentHttp.*;

/** Target authority owned by invitation admission. Call only after the user's persisted owner/persona
 * authorization, in that same business transaction. Transport completion is not service readiness. */
public final class HostingSetupService {
    private static final String CAPABILITIES = "setup-capabilities", INVENTORY = "setup-inventory";
    private final HostingSetupStore setups;
    private final HostingOutboundStore transport;
    private final HostingOutboundExchangeService exchanges;
    private final FixedTenantAdmissionPolicy scope;
    private final HostingSetupProperties properties;
    public HostingSetupService(HostingSetupStore setups, HostingOutboundStore transport, HostingOutboundExchangeService exchanges,
            FixedTenantAdmissionPolicy scope, HostingSetupProperties properties) {
        this.setups = setups; this.transport = transport; this.exchanges = exchanges; this.scope = scope; this.properties = properties;
    }
    public record Authorization(String authorizationId, String hostId, String purpose, String tenantId,
            String clientId, String ownerJiacn, String canonicalAgentId) { }
    public record View(String setupId, String personaCode, String agentId, String mode, String status, Authorization authorization) { }
    public record Target(Setup setup, ManagedHostingProvisioner.SkillInventory inventory) { }
    private void transaction(Actor actor) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Hosting setup requires admission transaction");
        scope.requireAdmitted(actor.tenantId(), actor.clientId());
    }
    private static HostingRentApplicationException rejected() { return new HostingRentApplicationException(403, "HOSTING_SETUP_REJECTED"); }
    private static HostingRentApplicationException pending() { return new HostingRentApplicationException(409, "HOSTING_SETUP_INVENTORY_PENDING"); }
    private Setup owned(Actor actor, String persona, Setup setup) {
        if (setup == null || !actor.actorId().equals(setup.actorId()) || !actor.ownerJiacn().equals(setup.ownerJiacn())
                || !persona.equals(setup.personaCode())) throw rejected();
        return setup;
    }
    private HostingOutboundStore.Grant active(Setup setup) {
        var grant = transport.lock(setup.tenantId(), setup.clientId(), setup.setupId());
        if (grant == null || !"ACTIVE".equals(grant.status()) || !"SETUP".equals(grant.purpose())
                || !setup.hostId().equals(grant.hostId()) || !setup.ownerJiacn().equals(grant.ownerJiacn())
                || !setup.canonicalAgentId().equals(grant.canonicalAgentId())) throw rejected();
        return grant;
    }
    public String mode(Actor actor, String mode, String hostId) {
        transaction(actor); exact(hostId, 100);
        var host = properties.managedHosts().get(hostId);
        if ("server".equals(mode)) {
            if (host == null || !host.allows(actor.tenantId(), actor.clientId(), actor.ownerJiacn())) throw rejected();
        } else if (!"local".equals(mode) || host != null) throw rejected();
        return mode; // A user cannot relabel an operator-managed host to buy the cheaper plan.
    }
    public View replay(Actor actor, String persona, String key, byte[] hash) {
        transaction(actor);
        var existing = setups.replay(actor.tenantId(), actor.clientId(), actor.actorId(), key);
        if (existing == null) return null;
        owned(actor, persona, existing);
        if (!MessageDigest.isEqual(hash, existing.requestHash())) throw new HostingRentApplicationException(409, "HOSTING_SETUP_CONFLICT");
        return view(existing, active(existing));
    }
    public View create(Actor actor, String persona, String key, byte[] hash, String agent, String mode, String host, String tokenHash, long now) {
        transaction(actor); mode(actor, mode, host); exact(agent, 100);
        if (tokenHash == null || !tokenHash.matches("[0-9a-f]{64}")) throw badRequest();
        String id = "hca_" + UUID.randomUUID().toString().replace("-", "");
        // Runtime creates a random bearer locally; the authenticated user registers only its SHA-256.
        // Lost HTTP replies replay public metadata, never rotate a key or store recoverable credentials.
        var grant = new HostingOutboundStore.Grant(actor.tenantId(), actor.clientId(), id, host, actor.ownerJiacn(), agent,
                "SETUP", HexFormat.of().parseHex(tokenHash), "ACTIVE");
        var setup = new Setup(actor.tenantId(), actor.clientId(), id, actor.actorId(), actor.ownerJiacn(), persona, agent, host, mode, key, hash);
        try {
            exchanges.register(grant, now); setups.insert(setup, now);
            exchanges.enqueue(actor.tenantId(), actor.clientId(), id, message(setup, "capabilities", CAPABILITIES), now);
            exchanges.enqueue(actor.tenantId(), actor.clientId(), id, message(setup, "inventory", INVENTORY), now);
        } catch (org.springframework.dao.DuplicateKeyException conflict) {
            throw new HostingRentApplicationException(409, "HOSTING_SETUP_CONFLICT");
        }
        return view(setup, grant);
    }
    public Setup find(Actor actor, String persona, String id) {
        transaction(actor); exact(id, 100);
        return owned(actor, persona, setups.find(actor.tenantId(), actor.clientId(), id));
    }
    public View lookup(Actor actor, String persona, String id) {
        var setup = find(actor, persona, id); return view(setup, active(setup));
    }
    public View revoke(Actor actor, String persona, String id, long now) {
        var setup = find(actor, persona, id);
        exchanges.revoke(actor.tenantId(), actor.clientId(), id, now);
        return new View(id, persona, setup.canonicalAgentId(), setup.mode(), "REVOKED", null);
    }
    private View view(Setup setup, HostingOutboundStore.Grant grant) {
        String status = inventory(setup, grant) == null ? "WAITING_INVENTORY" : "INVENTORY_READY";
        return new View(setup.setupId(), setup.personaCode(), setup.canonicalAgentId(), setup.mode(), status,
                new Authorization(grant.authorizationId(), grant.hostId(), grant.purpose(), grant.tenantId(), grant.clientId(), grant.ownerJiacn(), grant.canonicalAgentId()));
    }
    public Target inventory(Actor actor, String persona, String id) {
        var setup = find(actor, persona, id); var inventory = inventory(setup, active(setup));
        if (inventory == null) throw pending();
        return new Target(setup, inventory);
    }
    private ManagedHostingProvisioner.SkillInventory inventory(Setup setup, HostingOutboundStore.Grant grant) {
        var capabilities = result(grant, CAPABILITIES); var inventory = result(grant, INVENTORY);
        if (capabilities == null || !Boolean.TRUE.equals(capabilities.get("available")) || inventory == null) return null;
        // Old template-generated empty inventories are not evidence about the user's target environment.
        if (!"TARGET".equals(inventory.get("inventorySource"))) return null;
        var request = new java.util.LinkedHashMap<String,Object>(subject(setup));
        request.put("protocol", UnixManagedHostingProvisioner.PROTOCOL); request.put("method", "inventory");
        return UnixManagedHostingProvisioner.decodeInventory(request, inventory);
    }
    private Map<String,Object> result(HostingOutboundStore.Grant grant, String messageId) {
        var row = transport.command(grant, messageId);
        if (row == null || row.resultJson() == null) return null;
        try { return UnixManagedHostingProvisioner.hostingResponse(ProtocolMessage.decode(HostingOutboundExchangeService.parse(row.commandJson())),
                HostingOutboundExchangeService.parse(row.resultJson())); }
        catch (java.io.IOException invalid) { throw new IllegalStateException("Invalid persisted hosting result", invalid); }
    }
    public void attachQuote(Target target, String quoteId) { setups.attachQuote(target.setup(), quoteId); }
    public boolean isSetupQuote(Actor actor, String quoteId) {
        transaction(actor); return setups.isSetupQuote(actor.tenantId(), actor.clientId(), quoteId);
    }
    private static Map<String,Object> subject(Setup setup) {
        return Map.of("tenantId", setup.tenantId(), "clientId", setup.clientId(), "ownerJiacn", setup.ownerJiacn(), "canonicalAgentId", setup.canonicalAgentId());
    }
    private static Map<String,Object> message(Setup setup, String method, String messageId) {
        return Map.of("protocolVersion", 1, "requestId", setup.setupId(), "messageType", "hosting." + method, "messageId", messageId, "data", subject(setup));
    }
}
