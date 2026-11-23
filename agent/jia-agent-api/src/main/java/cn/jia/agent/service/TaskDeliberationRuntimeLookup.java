package cn.jia.agent.service;

import java.util.Set;
import java.util.stream.Collectors;

/** Live acknowledged discussion protocol, not file-generation capability or business authority.
 * Scope belongs to the resolved executor, which may differ from the task owner. */
public interface TaskDeliberationRuntimeLookup {
    record Scope(String tenantId, String clientId, String ownerJiacn, String agentId) { }
    boolean ready(Scope scope);

    /** Request-local observation only, never an admission token. */
    default Set<Scope> readyScopes(Set<Scope> requested) {
        return requested.stream().filter(this::ready).collect(Collectors.toUnmodifiableSet());
    }
}
