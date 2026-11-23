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
    private static final Origin WAIT=new Origin(null,false), CONFLICT=new Origin(null,true);
    public static Origin resolve(String persisted,Function<String,String> first){
        var evidence=JSON.readTree(persisted);String cap=evidence.get("captureId").asText();var r=evidence.get("observation");
        String sourceId=r.get("sourceId").asText(),epoch=r.get("epoch").asText();
        if(!"0".equals(epoch))return WAIT;
        var plan=observation(first,cap,"PLAN",List.of());
        if(plan==null||!sourceId.equals(plan.get("rootSourceId").asText()))return WAIT;
        var source=observation(first,cap,"SOURCE",List.of(sourceId,epoch));if(source==null)return WAIT;
        if(!source.get("parentSourceRecordId").isNull()||!source.get("previousEpochSourceRecordId").isNull())return CONFLICT;
        if(!"METERED_HTTP_RESPONSES_V1".equals(source.get("sourceKind").asText()))return WAIT;
        String attempt=NativeCaptureEvidence.digest(List.of("NATIVE_RESPONSE_USAGE",source.get("channelNamespace").asText(),r.get("providerResponseId").asText()));
        return attempt.equals(r.get("sourceAttemptId").asText())?new Origin(attempt,false):CONFLICT;
    }
    private static JsonNode observation(Function<String,String> first,String cap,String kind,List<String> id){
        String stored=first.apply(NativeCaptureEvidence.digest(List.of("USAGE_RECORD_V2",cap,kind,id)));
        return stored==null?null:JSON.readTree(stored).get("observation");
    }
}
