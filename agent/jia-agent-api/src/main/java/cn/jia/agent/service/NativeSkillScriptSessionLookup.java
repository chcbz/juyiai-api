package cn.jia.agent.service;

/** Current authenticated execution capability, not an installation or task grant. */
public interface NativeSkillScriptSessionLookup {
    /** The caller supplies the current authenticated transport fence, never request-provided authority. */
    boolean supports(PersonalWorkspaceExecutionService.RuntimeScope scope, AgentManagedSessionLookup.SessionFence fence);
}
