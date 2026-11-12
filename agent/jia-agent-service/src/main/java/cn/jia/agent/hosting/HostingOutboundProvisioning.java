package cn.jia.agent.hosting;

import cn.jia.agent.mapper.hosting.HostingOutboundStore;
import cn.jia.agent.mapper.hosting.HostingSetupStore;
import cn.jia.core.entity.ProtocolMessage;
import cn.jia.core.security.FixedTenantAdmissionPolicy;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Async transport adapter only: existing lifecycle codecs, installation admission and live-session
 * readiness remain authoritative. No socket fallback and no I/O wait under a transaction. */
public final class HostingOutboundProvisioning {
    private final HostingSetupStore setups;
    private final HostingOutboundStore store;
    private final HostingOutboundExchangeService exchanges;
    private final FixedTenantAdmissionPolicy scope;
    private final TransactionTemplate transactions;
    public HostingOutboundProvisioning(HostingSetupStore setups, HostingOutboundStore store,
            HostingOutboundExchangeService exchanges, FixedTenantAdmissionPolicy scope, PlatformTransactionManager manager) {
        this.setups = setups; this.store = store; this.exchanges = exchanges; this.scope = scope;
        this.transactions = new TransactionTemplate(manager);
    }
    public boolean availableFor(String tenant, String client) {
        scope.requireAdmitted(tenant, client); return true;
    }
    public boolean controls(String tenant, String client, String owner, String agent) {
        scope.requireAdmitted(tenant, client);
        return store.activeSubject(tenant, client, owner, agent) != null;
    }
    public Map<String,Object> exchange(Map<String,Object> request, String preferredHost) {
        String tenant = (String) request.get("tenantId"), client = (String) request.get("clientId");
        scope.requireAdmitted(tenant, client);
        return transactions.execute(status -> {
            var association = setups.leaseActivation(tenant, client, (String) request.get("leaseId"));
            if (association == null || !association.bindingId().equals(request.get("bindingId"))) return null;
            var grant = store.lock(tenant, client, association.authorizationId());
            if (grant == null || !"ACTIVE".equals(grant.status()) || !"CONTROL".equals(grant.purpose())
                    || !grant.ownerJiacn().equals(request.get("ownerJiacn")) || !grant.canonicalAgentId().equals(request.get("canonicalAgentId"))
                    || preferredHost != null && !grant.hostId().equals(preferredHost)) return null;
            // Stable semantic request key, independent of random HTTP correlation IDs. There is at
            // most one unfinished observation per exact request; completed observations are refreshed.
            var canonical = new TreeMap<String,Object>(request);
            if (canonical.get("quotedInventory") instanceof Map<?,?> inventory) {
                var sorted = new TreeMap<String,Object>(); inventory.forEach((k,v) -> sorted.put((String) k,v));
                canonical.put("quotedInventory", sorted);
            }
            String key = UUID.nameUUIDFromBytes(tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsBytes(canonical)).toString();
            var row = store.latest(grant, "out:" + key + ":");
            Map<String,Object> result = null;
            if (row != null && row.resultJson() != null) {
                try { result = UnixManagedHostingProvisioner.hostingResponse(ProtocolMessage.decode(HostingOutboundExchangeService.parse(row.commandJson())), HostingOutboundExchangeService.parse(row.resultJson())); }
                catch (java.io.IOException invalid) { throw new IllegalStateException("Invalid persisted lifecycle response", invalid); }
            }
            if (row == null || row.resultJson() != null && !"prepare".equals(request.get("method"))) {
                String id = "out:" + key + ":" + UUID.randomUUID();
                var message = Map.<String,Object>of("protocolVersion", 1, "requestId", key,
                        "messageType", "hosting." + request.get("method"), "messageId", id,
                        "data", UnixManagedHostingProvisioner.hostingData(request));
                exchanges.enqueue(tenant, client, grant.authorizationId(), message, System.currentTimeMillis());
            }
            return result;
        });
    }
}
