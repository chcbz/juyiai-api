package cn.jia.economy.usage;

import cn.jia.economy.task.TaskBillingService.Task;

/** Internal, owner-scoped read of one confirmed sample. MATCHED is a measurement prerequisite,
 * not a READY label, an amount, proof of zero operations, or permission to charge. */
public interface ConfirmedSampleEvidence {
    enum Verdict { ACTIVE, CANCELLED_OUTCOME, MEMBERSHIP_PENDING, EXECUTION_SET_CONFLICT, CONFIG_UNKNOWN,
        MIXED_EXECUTION_CONFIG, USAGE_INCOMPLETE, MATCHED }
    record Assessment(String sampleId,long evidenceRevision,String terminalFactId,long members,Verdict verdict) {}
    Assessment inspect(Task task,String sampleId);
}
