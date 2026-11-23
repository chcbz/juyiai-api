package cn.jia.agent.service.usage;

import cn.jia.agent.service.SkillCanonicalJson;
import cn.jia.economy.usage.UsageCaptureProjection.Coverage;
import cn.jia.economy.usage.UsageCaptureProjection.Row;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Folds strictly ordered, capture-scoped associated heads. Association already proves an acyclic rooted
 * source lineage and request -> response ownership. This verifies the exact CLOSED set, not just ancestry. */
final class NativeCaptureCoverage {
    record Policy(String configSha,String engine,String policy,String adapter,String basis,String channelNamespace) {}
    record Result(Coverage state,BigInteger observedTokens) {}
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final class Tuples {
        private final MessageDigest sha;
        private boolean first=true;
        Tuples(){try{sha=MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}sha.update((byte)'[');}
        void add(Row row){if(!first)sha.update((byte)',');first=false;sha.update(SkillCanonicalJson.write(List.of(row.recordId(),row.sourceRevision(),row.evidenceSha256())).getBytes(StandardCharsets.UTF_8));}
        String finish(){sha.update((byte)']');return HexFormat.of().formatHex(sha.digest());}
    }
    private record Source(String id,String epoch) {}
    private static final class Group {
        JsonNode source,closed;
        long requests,responses;BigInteger max=BigInteger.ZERO,quantity=BigInteger.ZERO;
        final Tuples members=new Tuples();final Set<String> refs=new HashSet<>();
    }
    private final Map<Source,Group> groups=new HashMap<>();private final Tuples finals=new Tuples();
    private final String capture;private final Policy trusted;
    private JsonNode plan,close;private String planSha,last="";private boolean waiting,incomplete;
    NativeCaptureCoverage(String capture,Policy trusted){this.capture=capture;this.trusted=trusted;}
    void accept(Row r){
        if(last.compareTo(r.recordId())>=0)throw new IllegalArgumentException("Unordered capture projection");last=r.recordId();
        if(r.sourceRevision()==null){waiting=true;return;}
        if(!Long.toString(r.revisions()).equals(r.sourceRevision()))waiting=true;
        var e=JSON.readTree(r.evidenceJson());if(!capture.equals(e.get("captureId").asText())||!r.recordId().equals(e.get("recordId").asText()))throw new IllegalStateException("Wrong capture head");
        var o=e.get("observation");String kind=e.get("kind").asText();
        if(kind.equals("PLAN")){plan=o;planSha=r.evidenceSha256();return;}
        if(kind.equals("CAPTURE_FINAL")){close=o;return;}
        var g=groups.computeIfAbsent(new Source(o.get("sourceId").asText(),o.get("epoch").asText()),key->new Group());
        if(kind.equals("SOURCE_FINAL")){g.closed=o;finals.add(r);return;}
        g.members.add(r);
        switch(kind){
            case "SOURCE" -> g.source=o;
            case "REQUEST" -> {g.requests++;g.max=g.max.max(number(o,"sequence"));
                if(!o.get("resolution").asText().equals("RESPONSE")||!g.refs.add(o.get("sourceAttemptId").asText()))incomplete=true;}
            case "RESPONSE" -> {g.responses++;var q=o.get("measuredQuantities");
                if(trusted==null||q.isNull()||!trusted.basis().equals(q.get("normalizationBasis").asText()))incomplete=true;
                else for(var count:q.get("tokens").properties())g.quantity=g.quantity.add(new BigInteger(count.getValue().asText()));}
            default -> throw new IllegalStateException("Unknown capture head kind");
        }
    }
    private static BigInteger number(JsonNode o,String name){return new BigInteger(o.get(name).asText());}
    private void count(JsonNode o,String field,long actual){int cmp=number(o,field).compareTo(BigInteger.valueOf(actual));if(cmp>0)waiting=true;if(cmp<0)incomplete=true;}
    cn.jia.economy.usage.UsageCaptureProjection.DispatchGroup dispatchGroup(){
        if(plan==null || !plan.has("dispatchGroup"))return null;
        var g=plan.get("dispatchGroup"); // Already validated by the wire adapter, linked to this exact associated PLAN head.
        return new cn.jia.economy.usage.UsageCaptureProjection.DispatchGroup(g.get("version").asText(),g.get("executionConfigSha256").asText(),
            g.get("routeSha256").asText(),g.get("toolConfigSha256").asText(),plan.get("coveragePolicyVersion").asText(),planSha);
    }
    Result finish(){
        BigInteger observed=BigInteger.ZERO;
        if(plan==null||close==null)waiting=true;
        if(plan!=null){var root=groups.get(new Source(plan.get("rootSourceId").asText(),"0"));if(root==null||root.source==null)waiting=true;
            if(trusted==null||!trusted.configSha().equals(plan.get("executionConfigSha256").asText())||!trusted.engine().equals(plan.get("engineVersion").asText())||!trusted.policy().equals(plan.get("coveragePolicyVersion").asText()))incomplete=true;}
        if(close!=null){count(close,"sourceCount",groups.size());if(!Objects.equals(planSha,close.get("planEvidenceSha256").asText())||!finals.finish().equals(close.get("sourcesSha256").asText())||!drained(close))incomplete=true;}
        for(var g:groups.values()){
            if(g.source==null||g.closed==null)waiting=true;
            if(g.source!=null&&trusted!=null&&trusted.adapter().equals(g.source.get("adapterVersion").asText())&&trusted.channelNamespace().equals(g.source.get("channelNamespace").asText())&&"METERED_HTTP_RESPONSES_V1".equals(g.source.get("sourceKind").asText()))observed=observed.add(g.quantity);else incomplete=true;
            if(g.closed==null)continue;
            count(g.closed,"requestCount",g.requests);count(g.closed,"responseCount",g.responses);
            // Positive sequence is part of record identity; n distinct sequences with maximum n are exactly [1,n].
            if(!g.max.equals(BigInteger.valueOf(g.requests))||!number(g.closed,"reservedSequenceHighWatermark").equals(BigInteger.valueOf(g.requests))
                    ||g.refs.size()!=g.responses||!drained(g.closed)||!g.members.finish().equals(g.closed.get("recordsSha256").asText()))incomplete=true;
        }
        return new Result(waiting?Coverage.WAITING_EVIDENCE:incomplete?Coverage.INCOMPLETE:Coverage.COMPLETE,observed);
    }
    private static boolean drained(JsonNode close){return "DRAINED".equals(close.get("closeState").asText())&&close.get("reasonCodes").isEmpty();}
}
