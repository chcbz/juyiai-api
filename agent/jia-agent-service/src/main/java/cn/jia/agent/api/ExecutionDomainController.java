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

/** Execution admission and durable intent receipts, independent of the business resource. */
abstract class ExecutionDomainController {
    interface BoundInput { String snapshotSha256(); Fence fence(); }
    private final PositionSkillService.RequiredPositionSkill requirement;
    private final SkillExecutionService executions;
    private final InstalledDomainAuthority authority;
    private final PrivateToolCallReceipts receipts;
    ExecutionDomainController(PositionSkillService.RequiredPositionSkill requirement,SkillExecutionService executions,InstalledDomainAuthority authority,PrivateToolCallReceipts receipts){
        this.requirement=requirement;this.executions=executions;this.authority=authority;this.receipts=receipts;
    }
    public record Fence(String version,String token) { @Override public String toString(){return "Fence[redacted]";} }
    record Call<I extends BoundInput>(RuntimeScope actor,String execution,String run,ConversationFence fence,String snapshot,I input) {}
    <I extends BoundInput> Call<I> call(Authentication auth,String e,String r,JsonRequest<I> envelope) {
        var p=AgentRuntimeAuthenticationService.requireProof(auth).scope(); var i=envelope==null?null:envelope.data();
        require(i!=null && i.snapshotSha256()!=null && i.snapshotSha256().matches("[0-9a-f]{64}") && i.fence()!=null && i.fence().version()!=null
                && i.fence().version().matches("[1-9][0-9]{0,15}") && i.fence().token()!=null && !i.fence().token().isBlank(),400,"BAD_REQUEST");
        return new Call<>(new RuntimeScope(p.tenantId(),p.clientId(),p.ownerJiacn(),p.agentId(),p.runtimeInstanceId()),e,r,new ConversationFence(Long.parseLong(i.fence().version()),i.fence().token()),i.snapshotSha256(),i);
    }
    <T> T authorized(Call<?> c,boolean publish,boolean mutation,Function<Scope,T> action) {
        return executions.withRunningSkills(c.actor(),c.execution(),c.run(),c.fence(),c.snapshot(),grant->{
            var a=grant.subject(); var scope=new Scope(a.tenantId(),a.clientId(),a.executorOwnerJiacn());
            // A task from a different principal cannot commission administrative writes on the executor's account.
            require(!mutation || a.executorOwnerJiacn().equals(a.ownerJiacn()),403,"DOMAIN_OWNER_REQUIRED");
            authority.requireInstalled(scope,a.agentId(),grant.installation(),grant.host(),publish,grant.selections(),requirement);return action.apply(scope);
        });
    }
    static ResponseEntity<?> ok(Object value){return ResponseEntity.ok().header("Cache-Control","private, no-store").body(JsonResult.success(value));}
    // One business intention per canonical operation/input in this run. The receipt is independent of
    // socket lifetime, model tool IDs, lease renewal and retried HTTP request IDs. A receipt-only request
    // never prepares or invokes a mutation. The second authorization covers out-of-transaction quote I/O.
    ResponseEntity<?> change(Call<?> c,String resource,boolean publish,Map<String,String> input,boolean receiptOnly,Supplier<Function<Scope,Map<String,Object>>> prepare){
        var digest=PositionSkillService.hash(SkillCanonicalJson.write(Map.of("resource",resource,"input",input,"snapshot",c.snapshot())));
        var a=c.actor();var key=new Key(a.tenantId(),a.clientId(),a.ownerJiacn(),c.execution(),c.run(),"domain-"+digest);
        var prior=authorized(c,publish,true,s->receipts.find(key));
        if(prior!=null)return ok(PrivateToolCallReceipts.replay(prior,resource,digest));
        require(!receiptOnly,404,"DOMAIN_RECEIPT_NOT_FOUND");var op=prepare.get();
        return ok(authorized(c,publish,true,s->receipts.apply(key,resource,digest,()->op.apply(s))));
    }
    @ExceptionHandler(PositionSkillException.class) public ResponseEntity<?> denied(PositionSkillException failure){return error(failure.status(),failure.getMessage());}
    @ExceptionHandler(Failure.class) public ResponseEntity<?> unavailable(Failure failure){return error(switch(failure.getReason()){
        case BAD_REQUEST -> 400; case NOT_FOUND -> 404; case CAPABILITY_UNAVAILABLE,STORAGE_UNAVAILABLE -> 503; default -> 409;
    },failure.getReason().name());}
    private static ResponseEntity<?> error(int status,String code){return ResponseEntity.status(status).header("Cache-Control","private, no-store").body(JsonResult.failure(code,"Execution domain operation unavailable"));}
}
