package cn.jia.agent.service;

/** Owner selection describes a requested deliverable, not tool permissions, a quote or a dispatch command. */
public interface TaskInitialActionSelection {
    enum Choice { LOCAL_ANSWER, LOCAL_TEXT_FILE }
    record View(String taskVersion,String agentId,String selectionVersion,String choice){}
    record Selected(AgentTaskExecutionGrantService.Scope scope,String task,long taskVersion,String agent){}
    final class Changed extends RuntimeException {}
    final class Missing extends RuntimeException {}
    View view(AgentTaskExecutionGrantService.Scope scope,String task,String agent);
    View choose(AgentTaskExecutionGrantService.Scope scope,String task,String agent,String selectionVersion,Choice choice);
}
