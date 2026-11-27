package cn.jia.agent.requirement;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import cn.jia.core.util.CanonicalValueEncoding;
import static cn.jia.core.util.CanonicalValueEncoding.*;

/** Immutable business scope; construction is not confirmation or spending authority.
 * The Agent confirmation transaction supplies the revision, fixed catalogue and timestamp.
 * Capability-specific bounds/units and file authority belong to their existing owners.
 */
public record ConfirmedScope(long requirementRevision, String requirementSha256,
        String scopeSha256, String inputSnapshotDigest, String schemaVersion,
        long confirmedAt, List<WorkItem> workItems, String acceptanceText) {
    public static final String VERSION = "CONFIRMED_SCOPE_V1";

    public ConfirmedScope {
        if (requirementRevision < 1 || confirmedAt < 0) throw invalid();
        requireSha(requirementSha256);
        requireSha(scopeSha256);
        requireSha(inputSnapshotDigest);
        Payload payload = new Payload(schemaVersion, workItems, acceptanceText);
        workItems = payload.workItems();
        if (!scopeSha256.equals(payload.digest(inputSnapshotDigest))) throw invalid();
    }

    @Override public String toString() { return "ConfirmedScope[private]"; }

    public enum ValueType { INTEGER, DECIMAL, BOOLEAN, ENUM, TEXT }

    public record Scalar(ValueType type, String unit, String value) {
        public Scalar {
            if (type == null || value == null) throw invalid();
            identifier(unit, 64);
            utf8(value);
            value = switch (type) {
                case INTEGER -> {
                    if (!value.matches("-?(0|[1-9][0-9]*)")) throw invalid();
                    yield new BigInteger(value).toString();
                }
                case DECIMAL -> {
                    if (!value.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?")) throw invalid();
                    BigDecimal decimal = new BigDecimal(value).stripTrailingZeros();
                    int scale = Math.max(0, decimal.scale());
                    int whole = Math.max(0, decimal.precision() - decimal.scale());
                    if (scale > 30 || whole > 35) throw invalid();
                    yield decimal.toPlainString();
                }
                case BOOLEAN -> {
                    if (!Set.of("true", "false").contains(value)) throw invalid();
                    yield value;
                }
                case ENUM -> {
                    if (value.isBlank()) throw invalid();
                    yield value;
                }
                case TEXT -> value;
            };
        }
        Map<String, Object> canonicalValue() {
            return Map.of("type", type.name(), "unit", unit, "value", value);
        }
        @Override public String toString() { return "ScopeScalar[private]"; }
    }

    public record WorkItem(String itemId, String capabilityId, String capabilityVersion,
            Map<String, Scalar> parameters, Map<String, Scalar> deliverables,
            List<String> inputRefs, List<String> dependsOn) {
        public WorkItem {
            identifier(itemId, 128);
            identifier(capabilityId, 128);
            identifier(capabilityVersion, 64);
            parameters = scalars(parameters);
            deliverables = scalars(deliverables);
            inputRefs = canonicalSet(inputRefs, false);
            dependsOn = canonicalSet(dependsOn, true);
        }
        Map<String, Object> canonicalValue() {
            return Map.of("itemId", itemId, "capabilityId", capabilityId,
                    "capabilityVersion", capabilityVersion,
                    "parameters", scalarValues(parameters), "deliverables", scalarValues(deliverables),
                    "inputRefs", inputRefs, "dependsOn", dependsOn);
        }
        @Override public String toString() { return "ScopeWorkItem[private]"; }
    }

    /** ConfirmScopeCommand's business payload, not the public HTTP envelope. */
    public record Payload(String schemaVersion, List<WorkItem> workItems, String acceptanceText) {
        public Payload {
            if (!VERSION.equals(schemaVersion) || workItems == null || workItems.isEmpty()
                    || acceptanceText == null || acceptanceText.isBlank()) throw invalid();
            utf8(acceptanceText);
            workItems = List.copyOf(workItems);
            Set<String> previous = new HashSet<>();
            for (WorkItem item : workItems) {
                if (previous.contains(item.itemId()) || !previous.containsAll(item.dependsOn())) throw invalid();
                previous.add(item.itemId());
            }
        }

        /** Catalogue is a verified server-side fixed input digest, never a browser assertion. */
        public byte[] canonicalBytes(String inputSnapshotDigest) {
            requireSha(inputSnapshotDigest);
            return encode(Map.of("schemaVersion", schemaVersion,
                    "workItems", workItems.stream().map(WorkItem::canonicalValue).toList(),
                    "acceptanceText", acceptanceText, "inputSnapshotDigest", inputSnapshotDigest));
        }

        public String digest(String inputSnapshotDigest) {
            return sha256(canonicalBytes(inputSnapshotDigest));
        }

        public String requirementDigest(String title, String description, String inputSnapshotDigest) {
            if (title == null || title.isBlank()) throw invalid();
            Map<String, Object> requirement = new HashMap<>();
            requirement.put("schema", "SCOPED_REQUIREMENT_V1");
            requirement.put("title", title);
            requirement.put("description", description); // Preserve the existing optional-description contract.
            requirement.put("scopeSha256", digest(inputSnapshotDigest));
            return sha256(encode(requirement));
        }
        @Override public String toString() { return "ScopePayload[private]"; }
    }

    private static Map<String, Scalar> scalars(Map<String, Scalar> input) {
        if (input == null) throw invalid();
        input.forEach((key, value) -> {
            identifier(key, 128);
            if (value == null) throw invalid();
        });
        return Map.copyOf(input);
    }

    private static Map<String, Object> scalarValues(Map<String, Scalar> input) {
        Map<String, Object> output = new HashMap<>();
        input.forEach((key, value) -> output.put(key, value.canonicalValue()));
        return output;
    }

    private static List<String> canonicalSet(List<String> input, boolean identifiers) {
        if (input == null) throw invalid();
        TreeSet<String> values = new TreeSet<>(CanonicalValueEncoding::compareUtf8);
        for (String value : input) {
            if (identifiers) identifier(value, 128);
            else {
                if (value == null || value.isBlank()) throw invalid();
                utf8(value); // File/version syntax and limits remain the catalogue owner's contract.
            }
            values.add(value);
        }
        return List.copyOf(values);
    }

    private static void identifier(String value, int maximum) {
        if (value == null || value.isBlank() || value.codePointCount(0, value.length()) > maximum) throw invalid();
        utf8(value);
    }

    private static void requireSha(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) throw invalid();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid confirmed requirement scope");
    }
}
