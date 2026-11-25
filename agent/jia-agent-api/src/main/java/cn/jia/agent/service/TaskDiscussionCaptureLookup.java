package cn.jia.agent.service;

/** Read the original admission inside the caller's authenticated transaction. Not a device or billing authority. */
public interface TaskDiscussionCaptureLookup {
    boolean enrolled(AgentTaskExecutionGrantService.Scope taskOwner, String taskId, String turnId,
                     String dispatchId, String admissionSha256, String captureId);
}
