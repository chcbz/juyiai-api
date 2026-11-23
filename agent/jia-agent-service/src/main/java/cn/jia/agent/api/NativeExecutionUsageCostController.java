package cn.jia.agent.api;

import cn.jia.agent.security.AgentRuntimeAuthenticationService;
import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import cn.jia.agent.service.usage.NativeExecutionUsageCostRecorder;
import cn.jia.agent.service.usage.NativeCaptureAuthority;
import cn.jia.agent.service.usage.NativeCaptureEvidence;
import cn.jia.economy.usage.UsageCaptureJournal;
import tools.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.List;
import org.springframework.dao.DataAccessException;
import cn.jia.core.entity.JsonRequest;
import cn.jia.core.entity.JsonResult;
import cn.jia.economy.usage.UsageCostConflict;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

/** Cost evidence delivery does not claim, resume or finish an execution. */
@RestController
@RequestMapping("/internal/agent/executions")
public final class NativeExecutionUsageCostController {
    public record Request(String executionId, NativeExecutionUsageCostRecorder.Phase phase, String requestedModel, JsonNode usageEvidence) { }
    public record Receipt(String executionId, String attemptId, String revision, String status, String costYuan) { }
    private final NativeExecutionUsageCostRecorder recorder;
    private final AgentRuntimeAuthenticationService authentication;
    private static final ObjectMapper JSON=new ObjectMapper();
    private final NativeCaptureAuthority authority;
    private final UsageCaptureJournal journal;
    private final UsageCaptureJournal.Settlement settlement;
    public NativeExecutionUsageCostController(NativeExecutionUsageCostRecorder recorder,
            AgentRuntimeAuthenticationService authentication,NativeCaptureAuthority authority,UsageCaptureJournal journal,UsageCaptureJournal.Settlement settlement) {
        this.settlement=settlement;
        this.authority=authority;this.journal=journal;
        this.recorder = recorder; this.authentication = authentication;
    }

    @PostMapping(value="/{executionId}/runs/{runId}/usage", consumes="application/json", produces="application/json")
    public ResponseEntity<?> submit(@PathVariable String executionId,@PathVariable String runId,@RequestBody JsonRequest<Map<String,Object>> request,
            @RequestHeader(value="Idempotency-Key",required=false) String key,Authentication principal) {
        var data=request==null?null:request.data();
        if(data!=null&&!data.containsKey("schemaVersion")&&(data.containsKey("captureId")||data.containsKey("sourceRevision")||data.containsKey("receiptId")))return error(400,"USAGE_CAPTURE_INVALID");
        if(data==null||!data.containsKey("schemaVersion")) return record(executionId,runId,request==null?null:new JsonRequest<>(request.protocolVersion(),request.requestId(),JSON.convertValue(data,Request.class)),principal);
        final NativeCaptureEvidence e;
        try {e=NativeCaptureEvidence.parse(executionId,runId,key,data);} catch(IllegalArgumentException ex){
            return error(400,"USAGE_CAPTURE_VERSION_UNSUPPORTED".equals(ex.getMessage())?ex.getMessage():"USAGE_CAPTURE_INVALID");
        }
        var submission=new UsageCaptureJournal.Submission(request.requestId(),NativeCaptureEvidence.digest(List.of(request.requestId(),data)),
                e.receiptId(),e.kind(),e.recordId(),e.sourceRevision(),e.previousEvidenceSha256(),e.evidenceSha256(),e.expectedRevision(),e.canonicalEvidence(),e.bindingSha256());
        try {
            var result=authentication.withNativeFence(principal,()->{
                var binding=authority.resolve(AgentRuntimeAuthenticationService.requireProof(principal).scope(),executionId,runId);
                var registered=journal.register(binding,submission);
                if(registered.state()==UsageCaptureJournal.State.DURABLE&&"SOURCE".equals(e.kind()))
                    return journal.linkSource(binding,e.receiptId(),cn.jia.agent.service.usage.NativeSourceLineage::resolve);
                return registered.state()==UsageCaptureJournal.State.PENDING&&"RESPONSE".equals(e.kind())
                        ?journal.bindResponse(binding,e.receiptId(),cn.jia.agent.service.usage.NativeResponseOrigin::resolve):registered;
            });
            return response(result.work()==null?result:settlement.apply(result.work())); // original fence transaction has committed
        } catch(DataAccessException unavailable){return error(503,"USAGE_CAPTURE_STORE_UNAVAILABLE");}
    }
    @GetMapping(value="/{executionId}/runs/{runId}/usage/receipts/{receiptId}",produces="application/json")
    public ResponseEntity<?> receipt(@PathVariable String executionId,@PathVariable String runId,@PathVariable String receiptId,Authentication principal){
        try{return response(authentication.withNativeFence(principal,()->journal.readReceipt(authority.resolve(AgentRuntimeAuthenticationService.requireProof(principal).scope(),executionId,runId),receiptId)));}
        catch(DataAccessException unavailable){return error(503,"USAGE_CAPTURE_STORE_UNAVAILABLE");}
    }
    @GetMapping(value="/{executionId}/runs/{runId}/usage/records/{recordId}",produces="application/json")
    public ResponseEntity<?> current(@PathVariable String executionId,@PathVariable String runId,@PathVariable String recordId,Authentication principal){
        try{return response(authentication.withNativeFence(principal,()->journal.readRecord(authority.resolve(AgentRuntimeAuthenticationService.requireProof(principal).scope(),executionId,runId),recordId)));}
        catch(DataAccessException unavailable){return error(503,"USAGE_CAPTURE_STORE_UNAVAILABLE");}
    }
    private static ResponseEntity<?> response(UsageCaptureJournal.Result result){return switch(result.state()){
        case NOT_FOUND -> error(404,"USAGE_CAPTURE_NOT_FOUND");case PENDING -> error(409,"USAGE_ACK_PENDING");case CONFLICT -> error(409,"USAGE_CAPTURE_CONFLICT");
        case DURABLE -> ResponseEntity.ok().header("Cache-Control","private, no-store").body(JsonResult.success(JSON.readTree(result.ackJson())));
    };}
    public ResponseEntity<JsonResult<Receipt>> record(String executionId, String runId,
            JsonRequest<Request> protocolRequest, Authentication principal) {
        var body = protocolRequest == null ? null : protocolRequest.data();
        if (body == null || !java.util.Objects.equals(executionId,body.executionId()) || body.phase() == null || body.executionId() == null || !body.executionId().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}")
                || body.phase() == NativeExecutionUsageCostRecorder.Phase.STARTED && body.usageEvidence() != null && !body.usageEvidence().isNull()
                || body.usageEvidence() != null && !body.usageEvidence().isNull() && !body.usageEvidence().isObject())
            throw new IllegalArgumentException("Invalid native usage evidence");
        var receipt = authentication.withNativeFence(principal, () -> {
            var trusted = AgentRuntimeAuthenticationService.requireProof(principal).scope();
            var scope = new PersonalWorkspaceExecutionService.RuntimeScope(trusted.tenantId(), trusted.clientId(),
                    trusted.ownerJiacn(), trusted.agentId(), trusted.runtimeInstanceId());
            return recorder.record(scope, executionId, runId, new NativeExecutionUsageCostRecorder.Evidence(
                    body.executionId(), body.phase(), body.requestedModel(), body.usageEvidence() == null ? "null" : body.usageEvidence().toString()));
        });
        return ResponseEntity.ok().header("Cache-Control", "private, no-store").body(JsonResult.success(
                new Receipt(body.executionId(), receipt.attemptId(), Long.toString(receipt.revision()),
                        receipt.status().name(), receipt.costYuan() == null ? null : receipt.costYuan().toPlainString())));
    }

    @ExceptionHandler(PersonalWorkspaceExecutionService.Failure.class)
    public ResponseEntity<JsonResult<Void>> unavailable(PersonalWorkspaceExecutionService.Failure ignored) {
        return error(404, "NATIVE_USAGE_EXECUTION_NOT_FOUND");
    }
    @ExceptionHandler(UsageCostConflict.class)
    public ResponseEntity<JsonResult<Void>> conflict(UsageCostConflict ignored) {
        return error(409, "NATIVE_USAGE_RECEIPT_CONFLICT");
    }
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<JsonResult<Void>> invalid(IllegalArgumentException ignored) {
        return error(400, "NATIVE_USAGE_INVALID");
    }
    private static ResponseEntity<JsonResult<Void>> error(int status, String code) {
        return ResponseEntity.status(status).header("Cache-Control", "private, no-store")
                .body(JsonResult.failure(code, "Native usage evidence could not be recorded"));
    }
}
