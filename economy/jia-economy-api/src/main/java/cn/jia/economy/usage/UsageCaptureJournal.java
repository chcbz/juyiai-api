package cn.jia.economy.usage;

/** Internal source journal, not a wallet, prediction or execution endpoint. Binding is supplied by Agent. */
public interface UsageCaptureJournal {
    record Binding(String tenantId,String clientId,String ownerJiacn,String agentId,String taskId,String executionId,String runId,String captureId) {}
    record Submission(String requestId,String requestSha256,String receiptId,String kind,String recordId,String sourceRevision,
                      String previousEvidenceSha256,String evidenceSha256,String expectedRevision,String canonicalEvidence,String bindingSha256) {}
    enum State { DURABLE, PENDING, CONFLICT, NOT_FOUND }
    record Work(Binding binding,String requestHash,UsageCostService.Observation observation) {}
    record Result(State state,String ackJson,Work work) { public Result(State state,String ackJson){this(state,ackJson,null);} }
    interface Settlement { Result apply(Work work); }
    Result finish(Work work,UsageCostService.Receipt applied,UsageCostConflict.Reason rejected);
    /** Adapter receives only previously registered bytes for this capture. Null attempt = missing/unsupported source. */
    record Origin(String attemptId,boolean conflict,long observedAt) {}
    interface ResponseOrigin { Origin resolve(String response,String previous,java.util.function.Function<String,String> firstRevision); }
    Result bindResponse(Binding trusted,String receiptId,ResponseOrigin adapter);
    Result register(Binding trusted,Submission source);
    Result readRecord(Binding trusted,String recordId);
    Result readReceipt(Binding trusted,String receiptId);
}
