package cn.jia.economy.usage;

import java.util.List;

/** Capture-local projection. Never registers task membership, posts a price or grants execution. */
public interface UsageCaptureProjection {
    record Key(String tenant,String client,long pk) {}
    record Snapshot(long inputRevision,String captureId) {}
    record Row(String recordId,long revisions,String sourceRevision,String evidenceSha256,String evidenceJson) {}
    enum Coverage { WAITING_EVIDENCE, INCOMPLETE, COMPLETE }
    Snapshot pending(Key key);
    boolean hasAssociated(Key key,String recordId);
    List<Row> page(Key key,String after);
    boolean publish(Key key,long inputRevision,Coverage state);
}
