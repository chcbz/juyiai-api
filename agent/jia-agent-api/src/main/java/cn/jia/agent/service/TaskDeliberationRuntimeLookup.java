package cn.jia.agent.service;

/** Live acknowledged discussion protocol, not file-generation capability or business authority.
 * Scope belongs to the resolved executor, which may differ from the task owner. */
public interface TaskDeliberationRuntimeLookup {
    record Scope(String tenantId, String clientId, String ownerJiacn, String agentId) { }
    boolean ready(Scope scope);
}
