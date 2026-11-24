package cn.jia.economy.service.impl;

import cn.jia.economy.pricing.TaskEstimateFeatures;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/** Economy-private read representation. Never grants sample eligibility or replaces the confirmed quote. */
public final class EconomyHistoryFeatureBytes {
    private EconomyHistoryFeatureBytes() {}
    public record Metadata(String featureVersion, String templateVersion, String requirementSha256,
            String inputSnapshotSha256, String textSha256, TaskEstimateFeatures.Identity embeddingIdentity,
            List<TaskEstimateFeatures.File> files, Integer requestedDeliverables) {
        public Metadata {
            if(featureVersion==null || templateVersion==null || requirementSha256==null || inputSnapshotSha256==null
                    || textSha256==null || embeddingIdentity==null || embeddingIdentity.providerConfigVersion()==null
                    || embeddingIdentity.model()==null || files==null || files.stream().anyMatch(f->f==null||f.purpose()==null||f.contentMimeType()==null))
                throw new IllegalArgumentException("Incomplete historical feature metadata");
            files=List.copyOf(files);
        }
        @Override public String toString() { return "HistoryFeatureMetadata[private]"; }
    }

    public static Metadata metadata(TaskEstimateFeatures source) {
        return new Metadata(source.featureVersion(), source.templateVersion(), source.requirementSha256(),
                source.inputSnapshotSha256(), source.textSha256(), source.embeddingIdentity(),
                source.files(), source.requestedDeliverables());
    }

    /** Network byte order and IEEE754 float32, matching the original feature commitment. */
    public static byte[] vector(TaskEstimateFeatures source) {
        return source.vectorBytes();
    }

    /** Caller must separately verify the source binding and the compatibility group. */
    public static TaskEstimateFeatures restore(Metadata meta, byte[] bytes, String expectedDigest) {
        if (meta == null || meta.embeddingIdentity() == null || meta.embeddingIdentity().dimensions() != 1024
                || bytes == null || bytes.length != 1024 * Float.BYTES) {
            throw new IllegalArgumentException("Invalid historical feature projection dimensions");
        }
        ByteBuffer in = ByteBuffer.wrap(bytes);
        var values = new ArrayList<Float>(meta.embeddingIdentity().dimensions());
        while (in.hasRemaining()) values.add(in.getFloat());
        var result = new TaskEstimateFeatures(meta.featureVersion(), meta.templateVersion(), meta.requirementSha256(),
                meta.inputSnapshotSha256(), meta.textSha256(), meta.embeddingIdentity(), values, meta.files(), meta.requestedDeliverables());
        if (!result.digest().equals(expectedDigest)) throw new IllegalArgumentException("Historical feature commitment mismatch");
        return result;
    }
}
