package cn.jia.agent.service;

import cn.jia.agent.requirement.ConfirmedScope.Scalar;
import cn.jia.agent.requirement.ConfirmedScope.ValueType;
import cn.jia.agent.skill.quote.CapabilityQuoteSpec.InputMetadata;
import java.util.Map;

/** Projection of already-authorized immutable workspace version facts, not a content parser.
 * Both quote preparation and prospective benchmark admission consume this same projection.
 * Does not grant file access or infer pages, duration, text length or media dimensions. */
public final class PersonalWorkspaceInputMetadata {
    public static final String SCHEMA = "WORKSPACE_FILE_FACTS_V1";
    public static final String PRODUCER = "STORED_FILE_VERSION_V1";
    private PersonalWorkspaceInputMetadata() { }

    public static InputMetadata fixedVersion(String fileId, long version, String mimeType,
            String contentSha256, long byteLength) {
        if (fileId == null || fileId.isBlank() || byteLength < 0) {
            throw new IllegalStateException("Invalid stored file version facts");
        }
        return new InputMetadata(fileId + "@" + version, version, mimeType, contentSha256,
                SCHEMA, PRODUCER, Map.of("byteLength",
                        new Scalar(ValueType.INTEGER, "unit/bytes", Long.toString(byteLength))));
    }
}
