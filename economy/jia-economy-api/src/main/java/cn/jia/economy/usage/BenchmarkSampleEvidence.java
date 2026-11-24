package cn.jia.economy.usage;

import cn.jia.economy.task.TaskBillingService.Task;

/** Internal offline read of an independently reviewed benchmark. No review writer, synthetic quote or automatic activation. */
public interface BenchmarkSampleEvidence {
    record Review(String schema,String caseId,String manifestSha,String terminalFactId,String membershipSha,String decision,
                  String reviewerRef,String deliverableEvidenceRef){@Override public String toString(){return "Review[private]";}}
    /** null: no matching current admitted review. A present but incomplete measurement has no usable operation list. */
    ConfirmedSampleEvidence.Measured measureReviewed(Task task,String caseId,String manifestSha,String fact,String reviewSha,ConfirmedSampleEvidence.Decoder decoder);
}
