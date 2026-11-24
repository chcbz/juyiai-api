package cn.jia.agent.api;

import cn.jia.agent.security.AgentRuntimeAuthenticationService;
import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import cn.jia.agent.service.usage.NativeExecutionUsageCostRecorder;
import cn.jia.agent.service.usage.NativeCaptureAuthority;
import tools.jackson.databind.ObjectMapper;
import java.util.Map;
import cn.jia.agent.service.RuntimeCaptureIntake;
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
    private final RuntimeCaptureIntake capture;
    public NativeExecutionUsageCostController(NativeExecutionUsageCostRecorder recorder,AgentRuntimeAuthenticationService authentication,
            NativeCaptureAuthority authority,RuntimeCaptureIntake capture) {
        this.authority=authority;this.capture=capture;
        this.recorder = recorder; this.authentication = authentication;
    }

    @PostMapping(value="/{executionId}/runs/{runId}/usage", consumes="application/json", produces="application/json")
    public ResponseEntity<?> submit(@PathVariable String executionId,@PathVariable String runId,@RequestBody JsonRequest<Map<String,Object>> request,
            @RequestHeader(value="Idempotency-Key",required=false) String key,Authentication principal) {
        var data=request==null?null:request.data();
        if(data!=null&&!data.containsKey("schemaVersion")&&(data.containsKey("captureId")||data.containsKey("sourceRevision")||data.containsKey("receiptId")))return error(400,"USAGE_CAPTURE_INVALID");
        if(data==null||!data.containsKey("schemaVersion")) return record(executionId,runId,request==null?null:new JsonRequest<>(request.protocolVersion(),request.requestId(),JSON.convertValue(data,Request.class)),principal);
        return wire(capture.submit(executionId,runId,request,key,fence(principal,executionId,runId),(binding,snapshot,obs)->authority.matchesPlan(binding,snapshot)));
    }
    private RuntimeCaptureIntake.Fence fence(Authentication principal,String execution,String run){
        return op->authentication.withNativeFence(principal,()->op.apply(authority.resolve(AgentRuntimeAuthenticationService.requireProof(principal).scope(),execution,run)));
    }
    @GetMapping(value="/{executionId}/runs/{runId}/usage/receipts/{receiptId}",produces="application/json")
    public ResponseEntity<?> receipt(@PathVariable String executionId,@PathVariable String runId,@PathVariable String receiptId,Authentication principal){
        return wire(capture.receipt(fence(principal,executionId,runId),receiptId));
    }
    @GetMapping(value="/{executionId}/runs/{runId}/usage/records/{recordId}",produces="application/json")
    public ResponseEntity<?> current(@PathVariable String executionId,@PathVariable String runId,@PathVariable String recordId,Authentication principal){
        return wire(capture.current(fence(principal,executionId,runId),recordId));
    }
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

    private static ResponseEntity<?> wire(RuntimeCaptureIntake.Reply r){
        return ResponseEntity.status(r.status()).header("Cache-Control","private, no-store").body(r.code()==null
                ? JsonResult.success(JSON.readTree(r.ackJson())) : JsonResult.failure(r.code(),"Usage evidence could not be recorded"));
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
