package cn.jia.agent.service.usage;

import cn.jia.economy.pricing.MeasuredTaskRepricing;
import cn.jia.economy.task.TaskBillingService.Task;
import cn.jia.economy.usage.ConfirmedSampleEvidence.Response;
import cn.jia.economy.usage.UsageCostService;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Unpriced, read-only extraction from one associated final and its existing provider ledger. */
final class NativeImageSampleOperation {
    private static final ObjectMapper JSON=new ObjectMapper();
    static MeasuredTaskRepricing.Fact read(UsageCostService costs,Task task,Response r){
        var envelope=JSON.readTree(r.evidenceJson());var o=envelope.path("observation");var i=o.path("imageInvocation");
        if(!r.expectsChild()||!"CAPTURE_FINAL".equals(r.kind())||!r.kind().equals(envelope.path("kind").asText())||!r.execution().equals(envelope.path("executionId").asText())
            ||!r.run().equals(envelope.path("runId").asText())||!r.captureId().equals(NativeCaptureEvidence.captureIdentity(r.execution(),r.run()))
            ||!r.captureId().equals(envelope.path("captureId").asText())||!r.recordId().equals(envelope.path("recordId").asText())||!r.sourceRevision().equals(envelope.path("sourceRevision").asText())
            ||!r.recordId().equals(NativeCaptureEvidence.digest(List.of("USAGE_RECORD_V2",r.captureId(),r.kind(),List.of())))||!"DRAINED".equals(o.path("closeState").asText())
            ||!o.path("reasonCodes").isArray()||!o.path("reasonCodes").isEmpty()||!i.isObject())return null;
        String id=i.path("invocationId").asText(),op=i.path("operation").asText();if(!Set.of("generate","edit").contains(op))return null;
        var binding=new UsageCostService.Binding(task.scope(),task.ownerJiacn(),task.taskId(),"NATIVE_PROVIDER_CALL",r.execution()+":"+id);
        var start=costs.observation(binding,"started").orElse(null);var end=costs.observation(binding,"terminal").orElse(null);
        if(start==null||end==null||start.expectedRevision()!=0||end.expectedRevision()!=1||end.normalizedUsage()==null)return null;
        var s=raw(start,i,"startedSha256");var t=raw(end,i,"terminalSha256");
        if(s==null||t==null||!"STARTED".equals(s.path("phase").asText())||!s.path("evidence").isNull()||!"TERMINAL".equals(t.path("phase").asText())
            ||!"openai-images-v1".equals(t.path("protocol").asText())||!op.equals(t.path("operation").asText())||!id.equals(t.path("invocationId").asText()))return null;
        for(var k:List.of("schemaVersion","protocol","invocationId","operation","requestedModel","channel","tier","priceVersion"))if(!s.path(k).equals(t.path(k)))return null;
        var e=t.path("evidence");if(!e.path("providerRequestId").isString()||e.path("providerRequestId").asText().isBlank())return null;
        var model=t.path("requestedModel").asText();if(e.hasNonNull("responseModel")&&!model.equals(e.path("responseModel").asText()))return null;
        var qty=ImageUsageNormalizer.normalize(t.path("channel").asText(),model,t.path("tier").asText(),e);
        if(qty==null||!qty.equals(end.normalizedUsage()))return null;
        return new MeasuredTaskRepricing.Fact(new MeasuredTaskRepricing.Operation(binding.source(),binding.sourceAttemptId()),binding.source(),op,r.evidenceSha(),new MeasuredTaskRepricing.Tokens(qty.normalizationBasis(),qty.tokens()));
    }
    private static JsonNode raw(UsageCostService.Observation saved,JsonNode ref,String hash){
        var raw=JSON.readValue(saved.rawUsageJson(),Map.class);return NativeCaptureEvidence.digest(raw).equals(ref.path(hash).asText())? JSON.valueToTree(raw):null;
    }
}
