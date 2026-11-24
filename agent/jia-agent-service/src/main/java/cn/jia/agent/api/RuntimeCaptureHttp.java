package cn.jia.agent.api;

import cn.jia.agent.service.usage.NativeCaptureEvidence;
import cn.jia.agent.service.usage.NativeCaptureRecovery;
import cn.jia.core.entity.JsonRequest;
import cn.jia.core.entity.JsonResult;
import cn.jia.economy.usage.UsageCaptureJournal;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Shared Runtime V2 HTTP protocol adapter. Caller's fence supplies the domain binding, never execution authority. */
@Component
public final class RuntimeCaptureHttp {
    public interface Fence { UsageCaptureJournal.Result apply(Function<UsageCaptureJournal.Binding,UsageCaptureJournal.Result> operation); }
    public interface PlanCheck { boolean matches(UsageCaptureJournal.Binding binding,String snapshot,Map<?,?> observation); }
    private static final ObjectMapper JSON=new ObjectMapper();
    private final UsageCaptureJournal journal;
    private final UsageCaptureJournal.Settlement settlement;
    private final NativeCaptureRecovery recovery;
    public RuntimeCaptureHttp(UsageCaptureJournal journal,UsageCaptureJournal.Settlement settlement,NativeCaptureRecovery recovery){
        this.journal=journal;this.settlement=settlement;this.recovery=recovery;
    }
    public ResponseEntity<?> submit(String executionId,String runId,JsonRequest<Map<String,Object>> request,String key,Fence fence,PlanCheck plan){
        var data=request==null?null:request.data();
        final NativeCaptureEvidence e;
        try {e=NativeCaptureEvidence.parse(executionId,runId,key,data);} catch(IllegalArgumentException ex){
            return error(400,"USAGE_CAPTURE_VERSION_UNSUPPORTED".equals(ex.getMessage())?ex.getMessage():"USAGE_CAPTURE_INVALID");
        }
        var submission=new UsageCaptureJournal.Submission(request.requestId(),NativeCaptureEvidence.digest(List.of(request.requestId(),data)),
                e.receiptId(),e.kind(),e.recordId(),e.sourceRevision(),e.previousEvidenceSha256(),e.evidenceSha256(),e.expectedRevision(),e.canonicalEvidence(),e.bindingSha256());
        try {
            var result=fence.apply(binding->{
                if(!e.captureId().equals(binding.captureId()) || "PLAN".equals(e.kind()) && !plan.matches(binding,e.snapshotSha256(),(Map<?,?>)data.get("observation")))
                    return new UsageCaptureJournal.Result(UsageCaptureJournal.State.CONFLICT,null);
                var registered=journal.register(binding,submission);
                if(registered.state()==UsageCaptureJournal.State.DURABLE&&!"RESPONSE".equals(e.kind()))
                    registered=journal.linkMetadata(binding,e.receiptId(),cn.jia.agent.service.usage.NativeSourceLineage::metadata);
                registered=registered.state()==UsageCaptureJournal.State.PENDING&&"RESPONSE".equals(e.kind())
                        ?journal.bindResponse(binding,e.receiptId(),cn.jia.agent.service.usage.NativeResponseOrigin::resolve):registered;
                if(registered.recoveryPk()!=0)recovery.signal(binding,e.receiptId(),registered.recoveryPk());
                return registered;
            });
            return response(result.work()==null?result:settlement.apply(result.work())); // original fence transaction has committed
        } catch(DataAccessException unavailable){return error(503,"USAGE_CAPTURE_STORE_UNAVAILABLE");}
    }
    public ResponseEntity<?> receipt(Fence fence,String receiptId){
        try{return response(fence.apply(binding->journal.readReceipt(binding,receiptId)));}
        catch(DataAccessException unavailable){return error(503,"USAGE_CAPTURE_STORE_UNAVAILABLE");}
    }
    public ResponseEntity<?> current(Fence fence,String recordId){
        try{return response(fence.apply(binding->journal.readRecord(binding,recordId)));}
        catch(DataAccessException unavailable){return error(503,"USAGE_CAPTURE_STORE_UNAVAILABLE");}
    }
    private static ResponseEntity<?> response(UsageCaptureJournal.Result result){return switch(result.state()){
        case NOT_FOUND -> error(404,"USAGE_CAPTURE_NOT_FOUND");case PENDING -> error(409,"USAGE_ACK_PENDING");case CONFLICT -> error(409,"USAGE_CAPTURE_CONFLICT");
        case DURABLE -> ResponseEntity.ok().header("Cache-Control","private, no-store").body(JsonResult.success(JSON.readTree(result.ackJson())));
    };}
    private static ResponseEntity<JsonResult<Void>> error(int status, String code) {
        return ResponseEntity.status(status).header("Cache-Control", "private, no-store")
                .body(JsonResult.failure(code, "Usage evidence could not be recorded"));
    }
}
