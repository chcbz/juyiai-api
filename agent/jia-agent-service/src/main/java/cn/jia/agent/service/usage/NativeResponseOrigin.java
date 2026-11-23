package cn.jia.agent.service.usage;

import cn.jia.economy.usage.UsageCaptureJournal.Origin;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.function.Function;

/** Resolve only from capture-scoped, already associated source records. No HTTP-supplied ancestry. */
public final class NativeResponseOrigin {
    private NativeResponseOrigin() {}
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Origin WAIT=new Origin(null,false,0), CONFLICT=new Origin(null,true,0);
    public static Origin resolve(String persisted,String previous,Function<String,String> first){
        var evidence=JSON.readTree(persisted);String cap=evidence.get("captureId").asText();var r=evidence.get("observation");
        if(previous!=null){
            var old=JSON.readTree(previous).get("observation");
            // A new observation may fill missing facts, not silently replace an already reported counter/identity.
            for(String field:List.of("reportedUsage","actualBillingIdentity")){
                if(!containsFacts(r.get(field),old.get(field)))return CONFLICT;
            }
            var measured=old.get("measuredQuantities");
            if(!measured.isNull()&&!sameQuantities(measured,r.get("measuredQuantities")))return CONFLICT;
        }
        String sourceId=r.get("sourceId").asText(),epoch=r.get("epoch").asText();
        var source=observation(first,cap,"SOURCE",List.of(sourceId,epoch));if(source==null)return WAIT;
        if(!"METERED_HTTP_RESPONSES_V1".equals(source.get("sourceKind").asText()))return WAIT;
        String attempt=NativeCaptureEvidence.digest(List.of("NATIVE_RESPONSE_USAGE",source.get("channelNamespace").asText(),r.get("providerResponseId").asText()));
        if(!attempt.equals(r.get("sourceAttemptId").asText()))return CONFLICT;
        var actual=r.get("actualBillingIdentity");var q=r.get("measuredQuantities");
        java.util.Map<String,String> tokens=null;
        if(!q.isNull()){
            String adapter=source.get("adapterVersion").asText(),basis=q.get("normalizationBasis").asText();
            if(TEXT_TOKENS.equals(adapter)||TEXT_TOKENS.equals(basis)){
                if(!adapter.equals(basis)||!matchesResponses(r.get("reportedUsage"),q.get("tokens")))return CONFLICT;
            }
            tokens=new java.util.HashMap<>();for(var entry:q.get("tokens").properties())tokens.put(entry.getKey(),entry.getValue().asText());
        }
        var facts=new cn.jia.economy.usage.UsageCaptureJournal.Facts(actual.isNull()?null:actual.get("channel").asText(),actual.isNull()?null:actual.get("model").asText(),actual.isNull()?null:actual.get("tier").asText(),q.isNull()?null:q.get("normalizationBasis").asText(),tokens==null?null:java.util.Map.copyOf(tokens));
        return new Origin(attempt,false,evidence.get("observedAt").asLong(),facts);
    }
    private static final String TEXT_TOKENS="RESPONSES_TEXT_TOKENS_V1";
    // The ingress codec already bounds raw counters to nonnegative JS safe integers.
    // Reasoning is a subset of output; cached input is removed from ordinary input.
    private static boolean matchesResponses(JsonNode raw,JsonNode tokens){
        if(raw==null||raw.isNull())return false;
        long input=raw.path("input_tokens").asLong(-1),output=raw.path("output_tokens").asLong(-1);
        long cached=raw.path("input_tokens_details").path("cached_tokens").asLong(-1);
        long reasoning=raw.path("output_tokens_details").path("reasoning_tokens").asLong(-1);
        return cached>=0 && cached<=input && reasoning>=0 && reasoning<=output
            && input+output==raw.path("total_tokens").asLong(-1) && tokens.size()==3
            && Long.toString(input-cached).equals(tokens.path("INPUT").asText())
            && Long.toString(cached).equals(tokens.path("CACHED_INPUT").asText())
            && Long.toString(output).equals(tokens.path("OUTPUT").asText());
    }
    private static boolean sameQuantities(JsonNode a,JsonNode b){
        if(b==null||b.isNull()||!a.get("normalizationBasis").equals(b.get("normalizationBasis")))return false;
        for(var category:cn.jia.economy.pricing.ModelTokenPrice.Category.values()){
            var x=a.get("tokens").get(category.name());var y=b.get("tokens").get(category.name());
            if(!(x==null?"0":x.asText()).equals(y==null?"0":y.asText()))return false;
        }
        return true;
    }
    private static boolean containsFacts(JsonNode current,JsonNode previous){
        if(previous==null||previous.isNull())return true;
        if(current==null||current.isNull())return false;
        if(!previous.isObject())return previous.equals(current);
        if(!current.isObject())return false;
        for(var property:previous.properties())if(!containsFacts(current.get(property.getKey()),property.getValue()))return false;
        return true;
    }
    private static JsonNode observation(Function<String,String> first,String cap,String kind,List<String> id){
        String stored=first.apply(NativeCaptureEvidence.digest(List.of("USAGE_RECORD_V2",cap,kind,id)));
        return stored==null?null:JSON.readTree(stored).get("observation");
    }
}
