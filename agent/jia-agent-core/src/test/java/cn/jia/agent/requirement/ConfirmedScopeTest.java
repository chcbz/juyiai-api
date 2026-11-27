package cn.jia.agent.requirement;

import cn.jia.agent.requirement.ConfirmedScope.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ConfirmedScopeTest {
    private static final String INPUTS = "0".repeat(64);
    private static Scalar words(String value) { return new Scalar(ValueType.INTEGER, "unit/words", value); }
    private static WorkItem text(String id, List<String> dependencies) {
        return new WorkItem(id, "text.summary", "1", Map.of(), Map.of("words", words("500")),
                List.of("f-text@1"), dependencies);
    }
    private static Payload payload(List<WorkItem> items) {
        return new Payload(ConfirmedScope.VERSION, items, "交付一份约定长度的中文摘要");
    }

    @Test void frozenInteroperabilityVector() {
        var scope = payload(List.of(text("w1", List.of())));
        assertEquals(477, scope.canonicalBytes(INPUTS).length);
        assertEquals("9ec99a1d7941971d207e3744f9c4f00f2915eb2c035ca69c58d004ddd56ce274", scope.digest(INPUTS));
    }

    @Test void mapOrderDoesNotChangeDigestButTypedValueAndUnitsDo() {
        var first = new LinkedHashMap<String, Scalar>();
        first.put("z", words("500")); first.put("a", words("10"));
        var second = new LinkedHashMap<String, Scalar>();
        second.put("a", words("10")); second.put("z", words("500"));
        var a = new WorkItem("w1", "text.summary", "1", first, Map.of(), List.of(), List.of());
        var b = new WorkItem("w1", "text.summary", "1", second, Map.of(), List.of(), List.of());
        assertEquals(payload(List.of(a)).digest(INPUTS), payload(List.of(b)).digest(INPUTS));
        second.put("z", new Scalar(ValueType.DECIMAL, "unit/words", "500"));
        var c = new WorkItem("w1", "text.summary", "1", second, Map.of(), List.of(), List.of());
        assertNotEquals(payload(List.of(a)).digest(INPUTS), payload(List.of(c)).digest(INPUTS));
        second.put("z", new Scalar(ValueType.INTEGER, "unit/items", "500"));
        var d = new WorkItem("w1", "text.summary", "1", second, Map.of(), List.of(), List.of());
        assertNotEquals(payload(List.of(a)).digest(INPUTS), payload(List.of(d)).digest(INPUTS));
    }

    @Test void referenceSetsCanonicalizeButWorkItemsStayOrdered() {
        var a = new WorkItem("a", "document.extract", "1", Map.of(), Map.of(),
                List.of("f-b@1", "f-a@1", "f-b@1"), List.of());
        assertEquals(List.of("f-a@1", "f-b@1"), a.inputRefs());
        var b = text("b", List.of());
        assertNotEquals(payload(List.of(a, b)).digest(INPUTS), payload(List.of(b, a)).digest(INPUTS));
        var dependent = text("b", List.of("a", "a"));
        assertEquals(List.of("a"), dependent.dependsOn());
        assertNotEquals(payload(List.of(a, b)).digest(INPUTS), payload(List.of(a, dependent)).digest(INPUTS));
    }

    @Test void dependenciesMustPointToEarlierUniqueItems() {
        assertThrows(IllegalArgumentException.class, () -> payload(List.of(text("w1", List.of("w1")))));
        assertThrows(IllegalArgumentException.class, () -> payload(List.of(text("w1", List.of("w2")), text("w2", List.of()))));
        assertThrows(IllegalArgumentException.class, () -> payload(List.of(text("w1", List.of()), text("w1", List.of()))));
        assertThrows(IllegalArgumentException.class, () -> payload(List.of(text("w1", List.of("missing")))));
    }

    @Test void typedNumbersNormalizeWithoutFloatingPoint() {
        assertEquals("0", words("-0").value());
        assertEquals("1.25", new Scalar(ValueType.DECIMAL, "unit/seconds", "1.2500").value());
        assertEquals("0", new Scalar(ValueType.DECIMAL, "unit/seconds", "-0.00").value());
        assertEquals("9007199254740993", words("9007199254740993").value());
        assertDoesNotThrow(() -> new Scalar(ValueType.DECIMAL, "unit/seconds", "9".repeat(35)+"."+"9".repeat(30)));
        for (String invalid : List.of("NaN", "Infinity", "1e2", "9".repeat(36), "0."+"0".repeat(30)+"1")) {
            assertThrows(IllegalArgumentException.class, () -> new Scalar(ValueType.DECIMAL, "unit/seconds", invalid));
        }
        for (String invalid : List.of("01", "+1", "1.0", " 1"))
            assertThrows(IllegalArgumentException.class, () -> words(invalid));
        assertThrows(IllegalArgumentException.class, () -> new Scalar(ValueType.BOOLEAN, "unit/code", "TRUE"));
    }

    @Test void scopeIsImmutableAndLogsDoNotContainBusinessText() {
        var refs = new ArrayList<>(List.of("f-text@1"));
        var params = new HashMap<String, Scalar>(); params.put("private", words("500"));
        var item = new WorkItem("w1", "text.summary", "1", params, Map.of(), refs, List.of());
        var items = new ArrayList<>(List.of(item));
        var scope = payload(items);
        String hash = scope.digest(INPUTS);
        params.clear(); refs.clear(); items.clear();
        assertEquals(hash, scope.digest(INPUTS));
        assertThrows(UnsupportedOperationException.class, () -> scope.workItems().clear());
        assertThrows(UnsupportedOperationException.class, () -> item.parameters().clear());
        assertFalse(scope.toString().contains("中文"));
        assertFalse(item.toString().contains("private="));
    }

    @Test void malformedUnicodeUnknownSchemaAndMissingValuesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Payload("OTHER", List.of(text("w1", List.of())), "ok"));
        assertThrows(IllegalArgumentException.class, () -> new Payload(ConfirmedScope.VERSION, List.of(), "ok"));
        assertThrows(IllegalArgumentException.class, () -> new Payload(ConfirmedScope.VERSION, List.of(text("w1", List.of())), "\ud800"));
        assertThrows(IllegalArgumentException.class, () -> new Scalar(ValueType.TEXT, "unit/text", "\udc00"));
        assertThrows(IllegalArgumentException.class, () -> words(null));
        assertThrows(IllegalArgumentException.class, () -> payload(List.of(text("w1", List.of()))).digest("unknown"));
    }

    @Test void persistedScopeMustMatchItsFixedInputDigest() {
        var payload = payload(List.of(text("w1", List.of())));
        String requirement = payload.requirementDigest("title", "description", INPUTS);
        var confirmed = new ConfirmedScope(1, requirement, payload.digest(INPUTS), INPUTS,
                ConfirmedScope.VERSION, 0, payload.workItems(), payload.acceptanceText());
        assertEquals(payload.digest(INPUTS), confirmed.scopeSha256());
        assertEquals("ConfirmedScope[private]", confirmed.toString());
        assertThrows(IllegalArgumentException.class, () -> new ConfirmedScope(1, requirement,
                payload.digest(INPUTS), "1".repeat(64), ConfirmedScope.VERSION, 0,
                payload.workItems(), payload.acceptanceText()));
        assertNotEquals(requirement, payload.requirementDigest("changed", "description", INPUTS));
        assertNotEquals(payload.requirementDigest("title", null, INPUTS),
                payload.requirementDigest("title", "", INPUTS));
    }
}
