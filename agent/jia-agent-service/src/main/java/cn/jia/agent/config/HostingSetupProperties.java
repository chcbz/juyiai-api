package cn.jia.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.Map;

/** Operator-owned managed host catalog. No inbound address, listener or TLS client bundle. */
@ConfigurationProperties(prefix = "agent.hosting-outbound.setup")
public record HostingSetupProperties(Map<String, ManagedHost> managedHosts) {
    public HostingSetupProperties { managedHosts = managedHosts == null ? Map.of() : Map.copyOf(managedHosts); }
    public record ManagedHost(String tenantId, String clientId, String ownerJiacn) {
        public boolean allows(String tenant, String client, String owner) {
            return tenant.equals(tenantId) && client.equals(clientId) && (owner.equals(ownerJiacn) || "*".equals(ownerJiacn));
        }
    }
}
