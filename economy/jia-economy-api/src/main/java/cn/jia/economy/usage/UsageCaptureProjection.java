package cn.jia.economy.usage;

import java.util.List;

/** Capture-local projection. Never registers task membership, posts a price or grants execution. */
public interface UsageCaptureProjection {
    record Key(String tenant,String client,long pk) {}
    record Snapshot(long inputRevision,String captureId,String owner,String task,String agent,String execution,String run) {}
    record Row(String recordId,long revisions,String sourceRevision,String evidenceSha256,String evidenceJson) {}
    /** Local dispatch measurement, independently versioned from the price book and from coverage. */
    record DispatchGroup(String version,String executionConfigSha256,String routeSha256,String toolConfigSha256,
            String capturePolicyVersion,String planEvidenceSha256) {}
    enum Coverage { WAITING_EVIDENCE, INCOMPLETE, COMPLETE }
    Snapshot pending(Key key);
    boolean hasAssociated(Key key,String recordId);
    List<Row> page(Key key,String after);
    default boolean publish(Key key,long inputRevision,Coverage state){return publish(key,inputRevision,state,null);}
    boolean publish(Key key,long inputRevision,Coverage state,DispatchGroup group);
}
