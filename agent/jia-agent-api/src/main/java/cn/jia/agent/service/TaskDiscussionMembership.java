package cn.jia.agent.service;

/** Internal only. The grant-bound task admission owns the root transaction and replay decision.
 * A CHAT/INSPECT turn is real work even before a per-call metering source exists for it. */
public interface TaskDiscussionMembership {
    /** Returns the frozen capture ID, or null when this new member has no admitted source. */
    String admit(AgentTaskExecutionGrantService.Scope owner, String taskId, String turnId,
               String initialDispatchId, String requestDigest, boolean captureTextBootstrap);
}
