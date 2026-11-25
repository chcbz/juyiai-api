package cn.jia.economy.task;

/** Trusted offline reconciliation of an enrolled case and one independently identified Agent terminal fact.
 * One transaction / bounded page. Completion here means member-set closure, never approval of benchmark quality or cost. */
public interface TaskBenchmarkClosure {
    enum Step { AWAITING_VERIFIED_TERMINAL, MORE, CLOSED }
    Step reconcile(TaskBillingService.Task task,String caseId,String manifestSha,String terminalFactId);
}
