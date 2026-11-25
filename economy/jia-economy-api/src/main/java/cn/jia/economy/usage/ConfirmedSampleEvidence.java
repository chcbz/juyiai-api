package cn.jia.economy.usage;

import cn.jia.economy.task.TaskBillingService.Task;

/** Internal, owner-scoped read of one confirmed sample. MATCHED is a measurement prerequisite,
 * not a READY label, an amount, proof of zero operations, or permission to charge. */
public interface ConfirmedSampleEvidence {
    enum Verdict { ACTIVE, CANCELLED_OUTCOME, MEMBERSHIP_PENDING, EXECUTION_SET_CONFLICT, CONFIG_UNKNOWN,
        MIXED_EXECUTION_CONFIG, CLOSED_USAGE_INCOMPLETE, USAGE_INCOMPLETE, MATCHED }
    record Assessment(String sampleId,long evidenceRevision,String terminalFactId,long members,Verdict verdict) {}
    /** Already-associated head only; raw wire JSON is private to the trusted source adapter and not returned. */
    record Response(long memberRevision,String captureId,long captureRevision,String recordId,String sourceRevision,
                    String evidenceSha,String evidenceJson) { @Override public String toString(){return "Response[private]";} }
    interface Decoder { boolean coversAllOperations(String groupVersion,String capturePolicy); cn.jia.economy.pricing.MeasuredTaskRepricing.Fact decode(Response response); }
    record Operation(long memberRevision,String captureId,long captureRevision,String recordId,String sourceRevision,
                     cn.jia.economy.pricing.MeasuredTaskRepricing.Fact fact) {}
    record Measured(Assessment assessment,java.util.List<Operation> operations) {
        public Measured { operations=java.util.List.copyOf(operations); }
    }
    /** One repeatable read covers selection AND operation extraction. No partial costs on incomplete evidence. */
    Measured measure(Task task,String sampleId,Decoder decoder);
    /** Offline evaluation only. Not eligibility for training, charging or catalog admission. */
    Measured measureOutcome(Task task,String quoteId,String agent,String groupSha,String featureSha,Decoder decoder);
    Assessment inspect(Task task,String sampleId);
}
