package cn.jia.economy.task;

/** Server-approved reference routes, not proof of actual supplier billing or sample eligibility. */
public interface PreparedReferenceRoutes {
    record Configuration(String version,String executionSha,String localRouteSha,String toolSha,String capturePolicy) {}
    /** Pure immutable lookup. Null: no exact owner-scoped mapping for this measured configuration. */
    TaskBudgetEstimator.Terms resolve(TaskBillingService.Task task,Configuration configuration);
}
