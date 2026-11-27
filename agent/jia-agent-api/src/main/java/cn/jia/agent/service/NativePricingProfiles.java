package cn.jia.agent.service;

/** Private measured cost registration. Parsing/storage belong to Agent service, not this API port.
 * No per-task execution authority, provider prices, user text or credentials. */
public interface NativePricingProfiles {
    /** Requires the caller's current authenticated installation/session transaction. */
    void observe(String tenant,String client,String provider,String agent,
            AgentManagedSessionLookup.SessionFence fence,Object report);
}
