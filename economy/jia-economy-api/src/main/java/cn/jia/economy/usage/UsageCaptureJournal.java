package cn.jia.economy.usage;

/** Internal source journal, not a wallet, prediction or execution endpoint. Binding is supplied by Agent. */
public interface UsageCaptureJournal {
    record Binding(String tenantId,String clientId,String ownerJiacn,String agentId,String taskId,String executionId,String runId,String captureId) {}
    record Submission(String requestId,String requestSha256,String receiptId,String kind,String recordId,String sourceRevision,
                      String previousEvidenceSha256,String evidenceSha256,String expectedRevision,String canonicalEvidence,String bindingSha256) {}
    enum State { DURABLE, PENDING, CONFLICT, NOT_FOUND }
    record Facts(String channel,String model,String tier,String normalizationBasis,java.util.Map<String,String> quantities) {}
    record Work(Binding binding,String requestHash,UsageCostService.Observation observation,Facts facts) {}
    record Result(State state,String ackJson,Work work, long recoveryPk) { public Result(State state,String ackJson,Work work){this(state,ackJson,work,0);} public Result(State state,String ackJson){this(state,ackJson,null);} }
    interface Settlement { Result apply(Work work); }
    Result finish(Work work,UsageCostService.Receipt applied,UsageCostConflict.Reason rejected);
    /** Adapter receives only previously registered bytes for this capture. Null attempt = missing/unsupported source. */
    record Origin(String attemptId,boolean conflict,long observedAt,Facts facts) {
        public Origin(String attemptId,boolean conflict,long observedAt){this(attemptId,conflict,observedAt,null);}
    }
    interface ResponseOrigin { Origin resolve(String response,String previous,java.util.function.Function<String,String> firstRevision); }
    enum Link { ASSOCIATED, WAITING, CONFLICT }
    record LinkedEvidence(boolean associated,String canonical) {}
    interface SourceLinks { Link resolve(String source,java.util.function.Function<String,LinkedEvidence> first); }
    Result linkSource(Binding trusted,String receiptId,SourceLinks adapter);
    /** Resolve only authenticated, stored bytes referenced by the scheduler. */
    Result recover(String tenant,String client,long capturePk,String receiptId,SourceLinks links,ResponseOrigin origin);
    Result bindResponse(Binding trusted,String receiptId,ResponseOrigin adapter);
    Result register(Binding trusted,Submission source);
    Result readRecord(Binding trusted,String recordId);
    Result readReceipt(Binding trusted,String receiptId);
}
