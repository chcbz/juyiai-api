package cn.jia.agent.service.usage;

import cn.jia.economy.pricing.MeasuredTaskRepricing;
import cn.jia.economy.pricing.ModelTokenPrice;
import cn.jia.economy.usage.ConfirmedSampleEvidence;
import java.util.EnumMap;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Source adapter for already-associated normalized responses. Unknown actual invoice identity does not erase quantities. */
@Component
public final class NativeSampleResponseDecoder implements ConfirmedSampleEvidence.Decoder {
    private static final ObjectMapper JSON=new ObjectMapper();
    // Never infer zero tools merely because every MODEL response had measured tokens.
    @Override public boolean coversAllOperations(String groupVersion,String policy){
        return "NATIVE_DISPATCH_GROUP_V1".equals(groupVersion)&&"TEXT_MODEL_ONLY_V1".equals(policy);
    }
    @Override public MeasuredTaskRepricing.Fact decode(ConfirmedSampleEvidence.Response row){
        var e=JSON.readTree(row.evidenceJson());var o=e.path("observation");String attempt=o.path("sourceAttemptId").asText();
        if(!"RESPONSE".equals(e.path("kind").asText())||!row.captureId().equals(e.path("captureId").asText())
                ||!row.sourceRevision().equals(e.path("sourceRevision").asText())||!row.recordId().equals(e.path("recordId").asText())
                ||!row.recordId().equals(NativeCaptureEvidence.digest(List.of("USAGE_RECORD_V2",row.captureId(),"RESPONSE",List.of(attempt)))))
            throw new IllegalStateException("Wrong measured response head");
        var q=o.path("measuredQuantities");String basis=q.path("normalizationBasis").asText();
        if(!"RESPONSES_TEXT_TOKENS_V1".equals(basis)||!"COMPLETE".equals(q.path("coverage").asText())||!q.path("tokens").isObject())return null;
        var counts=new EnumMap<ModelTokenPrice.Category,Long>(ModelTokenPrice.Category.class);
        for(var entry:q.get("tokens").properties())counts.put(ModelTokenPrice.Category.valueOf(entry.getKey()),Long.parseLong(entry.getValue().asText()));
        return new MeasuredTaskRepricing.Fact(new MeasuredTaskRepricing.Operation("NATIVE_RESPONSE_USAGE",attempt),"MODEL_RESPONSE","INFERENCE",row.evidenceSha(),new MeasuredTaskRepricing.Tokens(basis,counts));
    }
}
