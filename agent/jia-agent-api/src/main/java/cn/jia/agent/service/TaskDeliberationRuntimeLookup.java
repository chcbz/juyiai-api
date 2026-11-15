package cn.jia.agent.service;

/** Live acknowledged native deliberation capability, not presence or business authority.
 * Scope belongs to the resolved executor, which may differ from the task owner. */
public interface TaskDeliberationRuntimeLookup {
    record Scope(String tenantId, String clientId, String ownerJiacn, String agentId) { }
    boolean ready(Scope scope);
}
