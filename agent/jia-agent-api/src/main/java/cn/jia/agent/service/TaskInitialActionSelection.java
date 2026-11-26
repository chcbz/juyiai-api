package cn.jia.agent.service;

/** Owner selection describes a requested deliverable, not tool permissions, a quote or a dispatch command. */
public interface TaskInitialActionSelection {
    enum Choice { LOCAL_ANSWER, LOCAL_TEXT_FILE }
    record View(String taskVersion,String agentId,String selectionVersion,String choice){}
    record SkillChoice(String installationId,String mimeType) {}
    View chooseSkill(AgentTaskExecutionGrantService.Scope scope,String task,String agent,String selectionVersion,SkillChoice choice,String principal,String key);
    record Selected(NativeTaskPreparation.Binding binding){}
    final class KeyConflict extends RuntimeException {}
    final class Changed extends RuntimeException {}
    final class Missing extends RuntimeException {}
    View view(AgentTaskExecutionGrantService.Scope scope,String task,String agent);
    View choose(AgentTaskExecutionGrantService.Scope scope,String task,String agent,String selectionVersion,Choice choice,String principal,String key);
}
