package cn.jia.economy.usage;

/** Internal source journal, not a wallet, prediction or execution endpoint. Binding is supplied by Agent. */
public interface UsageCaptureJournal {
    record Binding(String tenantId,String clientId,String ownerJiacn,String agentId,String taskId,String executionId,String runId,String captureId) {}
    record Submission(String requestId,String requestSha256,String receiptId,String kind,String recordId,String sourceRevision,
                      String previousEvidenceSha256,String evidenceSha256,String expectedRevision,String canonicalEvidence) {}
    enum State { DURABLE, PENDING, CONFLICT, NOT_FOUND }
    record Result(State state,String ackJson) {}
    Result register(Binding trusted,Submission source);
    Result readReceipt(Binding trusted,String receiptId);
}
