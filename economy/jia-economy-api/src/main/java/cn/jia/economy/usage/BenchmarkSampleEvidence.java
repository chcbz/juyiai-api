package cn.jia.economy.usage;

import cn.jia.economy.task.TaskBillingService.Task;

/** Internal offline read of an independently reviewed benchmark. No review writer, synthetic quote or automatic activation. */
public interface BenchmarkSampleEvidence {
    record Review(String schema,String caseId,String manifestSha,String terminalFactId,String membershipSha,String decision,
                  String reviewerRef,String deliverableEvidenceRef){@Override public String toString(){return "Review[private]";}}
    record Pin(String caseId,String manifestSha,String fact,String reviewSha){}
    cn.jia.economy.task.TaskCalibrationObservations.Frozen freezeReviewed(Task task,cn.jia.economy.task.TaskHistoryCatalog.Group group,
            cn.jia.economy.task.TaskHistoryCatalog.Revision source,Pin pin,String family,cn.jia.economy.pricing.TaskEstimateEvaluation.Split split,ConfirmedSampleEvidence.Decoder decoder);
    /** null: no matching current admitted review. A present but incomplete measurement has no usable operation list. */
    ConfirmedSampleEvidence.Measured measureReviewed(Task task,String caseId,String manifestSha,String fact,String reviewSha,ConfirmedSampleEvidence.Decoder decoder);
    /** Scoped source uses the real sample identity; caller owns one read-only consistent transaction. */
    ConfirmedSampleEvidence.ScopedMeasured measureScopedReviewed(Task task,String sampleId,String caseId,String manifestSha,
            String fact,String reviewSha,ConfirmedSampleEvidence.Decoder decoder);
}
