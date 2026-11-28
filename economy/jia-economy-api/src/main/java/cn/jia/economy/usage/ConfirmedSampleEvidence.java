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
                    String evidenceSha,String evidenceJson,String execution,String run,String kind,boolean expectsChild) { @Override public String toString(){return "Response[private]";} }
    interface Decoder { boolean coversAllOperations(String groupVersion,String capturePolicy); cn.jia.economy.pricing.MeasuredTaskRepricing.Fact decode(Response response);
        default cn.jia.economy.pricing.MeasuredTaskRepricing.Fact decodeChild(Task task,Response close){return null;}
        /** Null means this adapter cannot independently prove a scoped capture, not zero usage. */
        default ScopedFold openScoped(Task task,ScopedExecution execution){return null;}
    }
    record ScopedExecution(String agent,String execution,String run,String captureId,long captureRevision,long memberRevision) {}
    interface ScopedFold {
        void accept(UsageCaptureProjection.Row row);
        /** Null means incomplete/unproven; never returns a partial list. */
        ScopedCapture finish();
    }
    record ScopedOperation(cn.jia.economy.pricing.CostRouteReference resource,Operation operation) {
        public ScopedOperation { java.util.Objects.requireNonNull(resource);java.util.Objects.requireNonNull(operation);
            if(!resource.kind().equals(operation.fact().kind())||!resource.component().equals(operation.fact().component()))
                throw new IllegalArgumentException("Measured resource/component mismatch"); }
    }
    record ScopedCapture(String measurementSha,java.util.List<ScopedOperation> operations) {
        public ScopedCapture {if(measurementSha==null||!measurementSha.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Missing actual configuration");operations=java.util.List.copyOf(operations);}
    }
    record ScopedMeasured(Assessment assessment,java.util.List<ScopedOperation> operations) {
        public ScopedMeasured {operations=java.util.List.copyOf(operations);}
    }
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
