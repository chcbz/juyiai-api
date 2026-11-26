package cn.jia.agent.service;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Registration-only, private data. No per-task authority, provider URL, free text or credentials. */
public interface NativePricingProfiles {
    List<String> FIELDS=List.of("operation","contextPolicyId","contextPolicyVersion","toolPolicyVersion","capturePolicyVersion","parentModel","parentRouteNamespace","childChannel","childModel","childTier","childBasis","parentBasis","referenceRouteSha256");
    record Report(long sequence,String sha,String canonical) { @Override public String toString(){return "PricingProfiles[private]";} }
    static Report parse(Object wire){
        if(!(wire instanceof Map<?,?> m)||!m.keySet().equals(Set.of("version","sequence","sha256","profiles"))||!"NATIVE_PRICING_PROFILES_V1".equals(m.get("version"))||!(m.get("sequence") instanceof String seq)||!seq.matches("[1-9][0-9]{0,18}")||!(m.get("profiles") instanceof List<?> rows)||rows.size()>2)throw invalid();
        var out=new StringJoiner(",","[","]");String last="";
        for(var row:rows){if(!(row instanceof Map<?,?> f)||!f.keySet().equals(Set.copyOf(FIELDS)))throw invalid();
            var fields=new StringJoiner(",","{","}");for(var k:FIELDS){var value=f.get(k);
                if(!(value instanceof String v)||!v.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))throw invalid();
                fields.add("\""+k+"\":\""+v+"\"");}
            String op=(String)f.get("operation");if(!Set.of("edit","generate").contains(op)||op.compareTo(last)<=0||!"native-image-context-1".equals(f.get("contextPolicyId"))||!"NATIVE_PINNED_IMAGE_V1".equals(f.get("capturePolicyVersion"))||!"openai-images-modalities-v1".equals(f.get("childBasis"))||!"RESPONSES_TEXT_TOKENS_V1".equals(f.get("parentBasis")))throw invalid();
            for(var k:List.of("contextPolicyVersion","toolPolicyVersion","parentRouteNamespace","referenceRouteSha256"))if(!((String)f.get(k)).matches("[0-9a-f]{64}"))throw invalid();
            last=op;out.add(fields.toString());
        }
        String canonical=out.toString(),sha=digest(canonical);if(!sha.equals(m.get("sha256")))throw invalid();return new Report(Long.parseLong(seq),sha,canonical);
    }
    static String digest(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid runtime pricing report");}
    /** Requires an installation-derived session and current runtime row under the registration transaction. */
    void observe(String tenant,String client,String provider,String agent,AgentManagedSessionLookup.SessionFence fence,Object report);
}
