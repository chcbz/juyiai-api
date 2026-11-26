package cn.jia.economy.pricing;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/** Shared cost identity. A caller must independently authorize each source and each quote. */
public final class GroupMeanPricing {
    private GroupMeanPricing() { }

    public record Route(String kind, String component, String channel, String model, String tier,
                        String normalizationBasis) {
        private List<String> fields() { return List.of(kind, component, channel, model, tier, normalizationBasis); }
    }

    public record Group(String featureVersion, String operation, String policySha, String bucketId,
                        String contextPolicyId, String contextPolicyVersion, String toolPolicyVersion,
                        String capturePolicyVersion, List<Route> routes) {
        public Group { routes = List.copyOf(routes); }
    }

    public static long amountWen(BigDecimal totalYuan, int completeTasks) {
        return CnyAmounts.quoteWen(totalYuan, BigDecimal.valueOf(completeTasks));
    }

    public static String key(Group group) {
        MessageDigest md;
        try { md = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        for (String field : List.of("group-key-1", group.featureVersion(), group.operation(), group.policySha(),
                group.bucketId(), group.contextPolicyId(), group.contextPolicyVersion(), group.toolPolicyVersion(),
                group.capturePolicyVersion())) put(md, bytes(field));
        if (group.routes().isEmpty()) throw new IllegalArgumentException("Missing pricing routes");
        var rows = group.routes().stream().map(r -> r.fields().stream().map(GroupMeanPricing::bytes).toList())
                .sorted((a,b) -> {
                    for (int i=0; i<a.size(); i++) {
                        int cmp = Arrays.compareUnsigned(a.get(i), b.get(i));
                        if (cmp != 0) return cmp;
                    }
                    return 0;
                }).toList();
        count(md, rows.size());
        List<byte[]> previous = null;
        for (var row : rows) {
            if (previous != null && Arrays.equals(previous.get(0),row.get(0)) && Arrays.equals(previous.get(1),row.get(1)))
                throw new IllegalArgumentException("Duplicate route component");
            row.forEach(value -> put(md,value));
            previous = row;
        }
        return HexFormat.of().formatHex(md.digest());
    }

    private static byte[] bytes(String s) {
        if (s.isBlank() || !StandardCharsets.UTF_8.newEncoder().canEncode(s)) throw new IllegalArgumentException("Invalid group field");
        return s.getBytes(StandardCharsets.UTF_8);
    }
    private static void put(MessageDigest md, byte[] value) { count(md,value.length); md.update(value); }
    private static void count(MessageDigest md, int n) {
        for (int shift=24; shift>=0; shift-=8) md.update((byte)(n >>> shift));
    }
}
