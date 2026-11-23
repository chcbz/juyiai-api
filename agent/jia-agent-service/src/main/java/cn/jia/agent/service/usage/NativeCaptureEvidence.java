package cn.jia.agent.service.usage;

import cn.jia.agent.service.SkillCanonicalJson;
import cn.jia.economy.pricing.ModelTokenPrice;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.math.BigInteger;
import java.util.*;

/** Agent wire adapter, after original-run authorization, before any durable write.
 * A valid digest proves content identity only: source linkage, pricing and coverage remain unresolved. */
public record NativeCaptureEvidence(String captureId,String recordId,String receiptId,String kind,String sourceRevision,
        String expectedRevision,String previousEvidenceSha256,String evidenceSha256,String canonicalEvidence) {
    private static final String ID="[A-Za-z0-9][A-Za-z0-9._:-]{0,99}", SHA="[a-f0-9]{64}", DEC="0|[1-9][0-9]*", POS="[1-9][0-9]*";
    private static final Set<String> COMMON=Set.of("schemaVersion","executionId","runId","captureId","receiptId","kind","recordId",
            "sourceRevision","previousEvidenceSha256","evidenceSha256","observedAt","expectedRevision","observation");
    private static void check(boolean ok){if(!ok)throw new IllegalArgumentException("USAGE_CAPTURE_INVALID");}
    private static String field(Map<?,?> m,String k,String regex){Object v=m.get(k);check(v instanceof String s && s.matches(regex));return (String)v;}
    private static String text(Map<?,?> m,String k){Object v=m.get(k);check(v instanceof String s&&!s.isBlank());return (String)v;}
    private static Map<?,?> obj(Object o){check(o instanceof Map);return (Map<?,?>)o;}
    private static void keys(Map<?,?> m,String... names){check(m.keySet().equals(Set.of(names)));}
    private static void nullableSha(Map<?,?> m,String k){if(m.get(k)!=null)field(m,k,SHA);}
    private static void reasons(Map<?,?> o){check(o.get("reasonCodes") instanceof List<?> l&&l.stream().allMatch(x->x instanceof String s&&!s.isBlank()));}
    private static long safeCount(Object v){check(v instanceof Integer||v instanceof Long||v instanceof BigInteger);var n=new BigInteger(v.toString());check(n.signum()>=0&&n.compareTo(BigInteger.valueOf(9007199254740991L))<=0);return n.longValueExact();}
    public static String digest(Object value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(SkillCanonicalJson.write(value).getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    public static NativeCaptureEvidence parse(String execution,String run,String idempotencyKey,Map<String,Object> d){
        check(d!=null&&d.keySet().equals(COMMON));if(safeCount(d.get("schemaVersion"))!=2)throw new IllegalArgumentException("USAGE_CAPTURE_VERSION_UNSUPPORTED");
        check(field(d,"executionId",ID).equals(execution)&&field(d,"runId",ID).equals(run));String cap=field(d,"captureId",SHA);
        check(cap.equals(digest(List.of("CAPTURE_V2",execution,run))));String receipt=field(d,"receiptId",ID);check(receipt.equals(idempotencyKey));
        String kind=text(d,"kind"), revision=field(d,"sourceRevision",POS);safeCount(d.get("observedAt"));nullableSha(d,"previousEvidenceSha256");
        check(revision.equals("1")== (d.get("previousEvidenceSha256")==null));if(kind.equals("RESPONSE"))field(d,"expectedRevision",DEC);else check(d.get("expectedRevision")==null);
        var o=obj(d.get("observation"));List<String> identity;
        switch(kind){
          case "PLAN" -> {keys(o,"captureProtocol","executionConfigSha256","engineVersion","coveragePolicyVersion","rootSourceId");check("METERED_CAPTURE_V2".equals(o.get("captureProtocol")));field(o,"executionConfigSha256",SHA);field(o,"rootSourceId",ID);text(o,"engineVersion");text(o,"coveragePolicyVersion");identity=List.of();}
          case "SOURCE" -> {keys(o,"sourceId","epoch","sourceKind","adapterVersion","channelNamespace","parentSourceRecordId","previousEpochSourceRecordId");identity=source(o);text(o,"sourceKind");text(o,"adapterVersion");text(o,"channelNamespace");nullableSha(o,"parentSourceRecordId");nullableSha(o,"previousEpochSourceRecordId");}
          case "REQUEST" -> {keys(o,"sourceId","epoch","sequence","attemptId","resolution","sourceAttemptId","reasonCodes");identity=List.of(field(o,"sourceId",ID),field(o,"epoch",DEC),field(o,"sequence",POS));field(o,"attemptId",ID);String res=text(o,"resolution");check(Set.of("PENDING","RESPONSE","UNRESOLVED").contains(res));check(!revision.equals("1")||res.equals("PENDING"));nullableSha(o,"sourceAttemptId");check(res.equals("RESPONSE")== (o.get("sourceAttemptId")!=null));reasons(o);check(!res.equals("UNRESOLVED")||!((List<?>)o.get("reasonCodes")).isEmpty());}
          case "RESPONSE" -> {keys(o,"sourceId","epoch","sourceAttemptId","providerResponseId","reportedUsage","measuredQuantities","actualBillingIdentity","reasonCodes");source(o);text(o,"providerResponseId");identity=List.of(field(o,"sourceAttemptId",SHA));reasons(o);checkQuantities(o);}
          case "SOURCE_FINAL" -> {keys(o,"sourceId","epoch","reservedSequenceHighWatermark","requestCount","responseCount","recordsSha256","closeState","reasonCodes");identity=source(o);for(String k:List.of("reservedSequenceHighWatermark","requestCount","responseCount"))field(o,k,DEC);field(o,"recordsSha256",SHA);close(o);}
          case "CAPTURE_FINAL" -> {keys(o,"planEvidenceSha256","sourceCount","sourcesSha256","closeState","reasonCodes");field(o,"planEvidenceSha256",SHA);field(o,"sourceCount",DEC);field(o,"sourcesSha256",SHA);close(o);identity=List.of();}
          default -> throw new IllegalArgumentException("USAGE_CAPTURE_INVALID");
        }
        String record=field(d,"recordId",SHA);check(record.equals(digest(List.of("USAGE_RECORD_V2",cap,kind,identity))));var evidence=new HashMap<>(d);
        for(String key:List.of("receiptId","expectedRevision","evidenceSha256"))evidence.remove(key);
        String sha=field(d,"evidenceSha256",SHA);check(sha.equals(digest(evidence)));
        return new NativeCaptureEvidence(cap,record,receipt,kind,revision,(String)d.get("expectedRevision"),(String)d.get("previousEvidenceSha256"),sha,SkillCanonicalJson.write(evidence));
    }
    private static List<String> source(Map<?,?> o){return List.of(field(o,"sourceId",ID),field(o,"epoch",DEC));}
    private static void close(Map<?,?> o){check(Set.of("DRAINED","SEALED_INCOMPLETE").contains(text(o,"closeState")));reasons(o);}
    // A narrow storage allowlist, not proof the declared adapter covers the operation.
    private static void checkQuantities(Map<?,?> o){
        if(o.get("reportedUsage")!=null){var raw=obj(o.get("reportedUsage"));for(var e:raw.entrySet()){
            switch(String.valueOf(e.getKey())){
                case "input_tokens","output_tokens","total_tokens" -> safeCount(e.getValue());
                case "input_tokens_details","output_tokens_details" -> {String key=e.getKey().equals("input_tokens_details")?"cached_tokens":"reasoning_tokens";var detail=obj(e.getValue());keys(detail,key);safeCount(detail.get(key));}
                default -> throw new IllegalArgumentException("USAGE_CAPTURE_INVALID");
            }
        }}
        if(o.get("measuredQuantities")!=null){var q=obj(o.get("measuredQuantities"));keys(q,"normalizationBasis","tokens","coverage");text(q,"normalizationBasis");check("COMPLETE".equals(q.get("coverage")));var tokens=obj(q.get("tokens"));for(Object k:tokens.keySet()){ModelTokenPrice.Category.valueOf((String)k);field(tokens,(String)k,DEC);}}
        if(o.get("actualBillingIdentity")!=null){var b=obj(o.get("actualBillingIdentity"));keys(b,"channel","model","tier","evidenceSha256");for(String k:List.of("channel","model","tier"))text(b,k);field(b,"evidenceSha256",SHA);}
    }
}
