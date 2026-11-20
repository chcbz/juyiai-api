package cn.jia.agent.api;

import cn.jia.agent.mapper.PrivateToolCallReceiptMapper.Key;
import cn.jia.agent.position.PositionSkillException;
import cn.jia.agent.position.PositionSkillService;
import cn.jia.agent.security.AgentRuntimeAuthenticationService;
import cn.jia.agent.service.PersonalWorkspaceExecutionService.*;
import cn.jia.agent.service.PositionAuthority.Scope;
import cn.jia.agent.service.SkillCanonicalJson;
import cn.jia.agent.service.SkillExecutionService;
import cn.jia.core.entity.JsonRequest;
import cn.jia.core.entity.JsonResult;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import static cn.jia.agent.position.PositionSkillException.require;

/** Authenticate the runtime and run an operation under the existing lease/snapshot transaction. */
abstract class RunningExecutionEndpoint {
    interface BoundInput { String snapshotSha256(); Fence fence(); }
    final SkillExecutionService executions;
    RunningExecutionEndpoint(SkillExecutionService executions){this.executions=executions;}
    public record Fence(String version,String token) { @Override public String toString(){return "Fence[redacted]";} }
    record Call<I extends BoundInput>(RuntimeScope actor,String execution,String run,ConversationFence fence,String snapshot,String selectedInstallation,I input) {}
    <I extends BoundInput> Call<I> call(Authentication auth,String e,String r,JsonRequest<I> envelope) {
        var p=AgentRuntimeAuthenticationService.requireProof(auth).scope(); var i=envelope==null?null:envelope.data();
        require(i!=null && i.snapshotSha256()!=null && i.snapshotSha256().matches("[0-9a-f]{64}") && i.fence()!=null && i.fence().version()!=null
                && i.fence().version().matches("[1-9][0-9]{0,15}") && i.fence().token()!=null && !i.fence().token().isBlank(),400,"BAD_REQUEST");
        var request=org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        String selected=request instanceof org.springframework.web.context.request.ServletRequestAttributes servlet?servlet.getRequest().getHeader("X-Cyf-Selected-Installation"):null;
        return new Call<>(new RuntimeScope(p.tenantId(),p.clientId(),p.ownerJiacn(),p.agentId(),p.runtimeInstanceId()),e,r,new ConversationFence(Long.parseLong(i.fence().version()),i.fence().token()),i.snapshotSha256(),selected,i);
    }
    static Key receiptKey(Call<?> c,String key){
        var actor=c.actor();return new Key(actor.tenantId(),actor.clientId(),actor.ownerJiacn(),c.execution(),c.run(),key);
    }
    static ResponseEntity<?> ok(Object value){return ResponseEntity.ok().header("Cache-Control","private, no-store").body(JsonResult.success(value));}
    @ExceptionHandler(PositionSkillException.class) public ResponseEntity<?> denied(PositionSkillException failure){return error(failure.status(),failure.getMessage());}
    @ExceptionHandler(Failure.class) public ResponseEntity<?> unavailable(Failure failure){return error(switch(failure.getReason()){
        case BAD_REQUEST -> 400; case NOT_FOUND -> 404; case CAPABILITY_UNAVAILABLE,STORAGE_UNAVAILABLE -> 503; default -> 409;
    },failure.getReason().name());}
    private static ResponseEntity<?> error(int status,String code){return ResponseEntity.status(status).header("Cache-Control","private, no-store").body(JsonResult.failure(code,"Execution domain operation unavailable"));}
}
