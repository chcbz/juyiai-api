package cn.jia.agent.dao;

/** Original task-root transaction only; does not bill, read usage, or contact another process. */
public interface TaskExecutionClosureDao {
    enum Outcome { ACCEPTED, CANCELLED }
    cn.jia.economy.usage.TaskMembership.Terminal pending(String tenant,String client,String fact);
    java.util.List<cn.jia.economy.usage.TaskMembership.Member> members(cn.jia.economy.usage.TaskMembership.Terminal terminal,long after);
    void delivered(cn.jia.economy.usage.TaskMembership.Terminal terminal);
    java.util.List<cn.jia.economy.usage.TaskMembership.Pending> pendingPage(String tenant,String client,String after);
    void closeTaskMembers(String tenant,String client,String owner,String task,Outcome outcome,
                          String outcomeRef,long taskRevision,long closedAt);
}
