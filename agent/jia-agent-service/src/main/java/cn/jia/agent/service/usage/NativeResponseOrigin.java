package cn.jia.agent.service.usage;

import cn.jia.economy.usage.UsageCaptureJournal.Origin;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.function.Function;

/** The current Runtime captures one original HTTP Responses root. Other source graphs wait for their adapter. */
public final class NativeResponseOrigin {
    private NativeResponseOrigin() {}
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Origin WAIT=new Origin(null,false,0), CONFLICT=new Origin(null,true,0);
    public static Origin resolve(String persisted,String previous,Function<String,String> first){
        var evidence=JSON.readTree(persisted);String cap=evidence.get("captureId").asText();var r=evidence.get("observation");
        if(previous!=null){
            var old=JSON.readTree(previous).get("observation");
            // A new observation may fill missing facts, not silently replace an already reported counter/identity.
            for(String field:List.of("reportedUsage","measuredQuantities","actualBillingIdentity")){
                if(!containsFacts(r.get(field),old.get(field)))return CONFLICT;
            }
        }
        String sourceId=r.get("sourceId").asText(),epoch=r.get("epoch").asText();
        if(!"0".equals(epoch))return WAIT;
        var plan=observation(first,cap,"PLAN",List.of());
        if(plan==null||!sourceId.equals(plan.get("rootSourceId").asText()))return WAIT;
        var source=observation(first,cap,"SOURCE",List.of(sourceId,epoch));if(source==null)return WAIT;
        if(!source.get("parentSourceRecordId").isNull()||!source.get("previousEpochSourceRecordId").isNull())return CONFLICT;
        if(!"METERED_HTTP_RESPONSES_V1".equals(source.get("sourceKind").asText()))return WAIT;
        String attempt=NativeCaptureEvidence.digest(List.of("NATIVE_RESPONSE_USAGE",source.get("channelNamespace").asText(),r.get("providerResponseId").asText()));
        if(!attempt.equals(r.get("sourceAttemptId").asText()))return CONFLICT;
        var actual=r.get("actualBillingIdentity");var q=r.get("measuredQuantities");
        java.util.Map<String,String> tokens=null;
        if(!q.isNull()){
            tokens=new java.util.HashMap<>();for(var entry:q.get("tokens").properties())tokens.put(entry.getKey(),entry.getValue().asText());
        }
        var facts=new cn.jia.economy.usage.UsageCaptureJournal.Facts(actual.isNull()?null:actual.get("channel").asText(),actual.isNull()?null:actual.get("model").asText(),actual.isNull()?null:actual.get("tier").asText(),q.isNull()?null:q.get("normalizationBasis").asText(),tokens==null?null:java.util.Map.copyOf(tokens));
        return new Origin(attempt,false,evidence.get("observedAt").asLong(),facts);
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
