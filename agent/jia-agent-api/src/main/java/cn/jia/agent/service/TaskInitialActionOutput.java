package cn.jia.agent.service;

/** Internal projection after grant resolution in the original admission transaction. Not an execution capability. */
public interface TaskInitialActionOutput {
    /** Null means no pending first-output commitment. Never infer this from a missing referenced decision. */
    NativeTaskPreparation.Output pending(AgentTaskExecutionGrantService.Scope scope,String task,String agent);
}
