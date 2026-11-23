package cn.jia.agent.service.recommendation;

import cn.jia.agent.entity.AgentTaskDTO;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;
import java.text.Normalizer;
import java.util.Comparator;
import java.util.List;

/** Private, transient query text. Only its digest and truncation flag may be logged. */
final class TaskRetrievalText {
    static final String VERSION = "task-metadata-v1";
    // UTF-8 bytes upper-bound byte-level BPE tokens; reserve headroom below the model limit.
    private static final Pattern SPACE = Pattern.compile("(?U)\\s+");
    private static final int MAX_BYTES = 7500;
    private final String text;
    private final String sha256;
    private final boolean truncated;

    private TaskRetrievalText(Buffer buffer) {
        text = buffer.out.toString();
        try { sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        truncated = buffer.truncated;
    }
    String text() { return text; }
    String sha256() { return sha256; }
    boolean truncated() { return truncated; }

    static TaskRetrievalText from(AgentTaskDTO task, List<RecommendationMaterials.Observation> materials) {
        var b = new Buffer();
        b.field("事项", task.getTitle());
        if (task.getRequiredAbilities() != null)
            task.getRequiredAbilities().stream().filter(java.util.Objects::nonNull).map(TaskRetrievalText::normalized)
                    .distinct().sorted().forEach(v -> b.field("要求", v));
        b.field("说明", task.getDescription());
        materials.stream().sorted(Comparator.comparing(RecommendationMaterials.Observation::relationId)).forEach(o -> {
            var m = o.material();
            b.field("资料用途", m.role()); b.field("资料名", m.name()); b.field("原始文件名", m.originalFilename());
            b.field("类型", m.contentMimeType()); b.field("摘要", m.summary());
        });
        return new TaskRetrievalText(b);
    }
    static String normalized(String value) {
        return SPACE.matcher(Normalizer.normalize(value, Normalizer.Form.NFC)).replaceAll(" ").strip();
    }
    private static final class Buffer {
        final StringBuilder out = new StringBuilder();
        int remaining = MAX_BYTES;
        boolean truncated;
        void field(String name, String value) {
            if (truncated || value == null || value.isBlank()) return;
            var line = (out.isEmpty() ? "" : "\n") + name + ": " + normalized(value);
            for (int i = 0; i < line.length();) {
                int cp = line.codePointAt(i);
                int bytes = cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
                if (bytes > remaining) { truncated = true; return; }
                remaining -= bytes; out.appendCodePoint(cp); i += Character.charCount(cp);
            }
        }
    }
}
