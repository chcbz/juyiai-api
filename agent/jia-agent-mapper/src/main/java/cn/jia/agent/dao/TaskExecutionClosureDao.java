package cn.jia.agent.dao;

/** Original task-root transaction only; does not bill, read usage, or contact another process. */
public interface TaskExecutionClosureDao {
    enum Outcome { ACCEPTED, CANCELLED }
    void closeTaskMembers(String tenant,String client,String owner,String task,Outcome outcome,
                          String outcomeRef,long taskRevision,long closedAt);
}
