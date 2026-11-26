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

    public enum Dimension { INPUT_FILE_COUNT, TOTAL_INPUT_BYTES, REQUESTED_DELIVERABLES }
    public record Range(long lo,long hi) {
        public Range { if(lo<0 || hi<=lo)throw new IllegalArgumentException("Invalid cost range"); }
        boolean contains(Long n) {return n!=null && n>=lo && n<hi;}
        boolean overlaps(Range other) {return lo<other.hi && other.lo<hi;}
    }
    public record Bucket(String id,java.util.Map<Dimension,Range> ranges) {
        public Bucket { if(id.isBlank())throw new IllegalArgumentException("Missing bucket identity"); ranges=java.util.Map.copyOf(ranges); }
    }
    /** Loaded once per validated policy version. Unknown measurements are never mapped to zero. */
    public record Buckets(String operation,List<Bucket> buckets) {
        public Buckets {
            if(operation.isBlank() || buckets.isEmpty())throw new IllegalArgumentException("Missing operation policy");
            buckets=List.copyOf(buckets); var ids=new java.util.HashSet<String>(); var dims=buckets.get(0).ranges().keySet();
            for(int i=0;i<buckets.size();i++) {
                var b=buckets.get(i);
                if(!ids.add(b.id()) || !dims.equals(b.ranges().keySet()))throw new IllegalArgumentException("Inconsistent bucket policy");
                for(int j=0;j<i;j++) {
                    var other=buckets.get(j);
                    if(dims.stream().allMatch(d -> other.ranges().get(d).overlaps(b.ranges().get(d))))throw new IllegalArgumentException("Overlapping cost buckets");
                }
            }
        }
        public java.util.Optional<String> match(String knownOperation,java.util.Map<Dimension,Long> observations) {
            if(!operation.equals(knownOperation))return java.util.Optional.empty();
            return buckets.stream().filter(b -> b.ranges().entrySet().stream().allMatch(e -> e.getValue().contains(observations.get(e.getKey()))))
                .map(Bucket::id).findFirst();
        }
    }

    public static long amountWen(BigDecimal totalYuan, int completeTasks) {
        return CnyAmounts.quoteWen(totalYuan, BigDecimal.valueOf(completeTasks));
    }

    public static String featureDigest(String version,String operation,String bucket,String requirement,String inputs) {
        var md = sha256();
        for(var field:List.of(version,operation,bucket,requirement,inputs)) put(md,bytes(field));
        return HexFormat.of().formatHex(md.digest());
    }
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    public static String key(Group group) {
        var md = sha256();
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
