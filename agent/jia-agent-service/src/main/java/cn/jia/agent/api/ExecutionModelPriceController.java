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
import java.util.regex.Pattern;
import static cn.jia.agent.position.PositionSkillException.require;

/** Model domain HTTP resources, shared by native execution contexts. No conversation tool catalog. */
@RestController
@RequestMapping("/internal/agent/executions/{execution}/runs/{run}/business")
public final class ExecutionModelPriceController {
    private static final Pattern MODEL=Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{0,99}"), PERSONA=Pattern.compile("[a-z0-9][a-z0-9-]{0,99}");
    private final SkillExecutionService executions;
    private final ModelMaintenanceAuthority authority;
    private final ModelMaintenanceOperations operations;
    private final PrivateToolCallReceipts receipts;
    ExecutionModelPriceController(SkillExecutionService executions,ModelMaintenanceAuthority authority,ModelMaintenanceOperations operations,PrivateToolCallReceipts receipts) {
        this.executions=executions;this.authority=authority;this.operations=operations;this.receipts=receipts;
    }
    public record Fence(String version,String token) { @Override public String toString(){return "Fence[redacted]";} }
    public record Input(String model,String personaCode,String expectedModel,String snapshotSha256,Fence fence) {}
    private record Call(RuntimeScope actor,String execution,String run,ConversationFence fence,String snapshot,Input input) {}
    private Call call(Authentication auth,String e,String r,JsonRequest<Input> envelope) {
        var p=AgentRuntimeAuthenticationService.requireProof(auth).scope(); var i=envelope==null?null:envelope.data();
        require(i!=null && i.snapshotSha256()!=null && i.snapshotSha256().matches("[0-9a-f]{64}") && i.fence()!=null && i.fence().version()!=null
                && i.fence().version().matches("[1-9][0-9]{0,15}") && i.fence().token()!=null && !i.fence().token().isBlank(),400,"BAD_REQUEST");
        return new Call(new RuntimeScope(p.tenantId(),p.clientId(),p.ownerJiacn(),p.agentId(),p.runtimeInstanceId()),e,r,new ConversationFence(Long.parseLong(i.fence().version()),i.fence().token()),i.snapshotSha256(),i);
    }
    private <T> T authorized(Call c,boolean price,boolean mutation,Function<Scope,T> action) {
        return executions.withRunningSkills(c.actor(),c.execution(),c.run(),c.fence(),c.snapshot(),grant->{
            var a=grant.subject(); var scope=new Scope(a.tenantId(),a.clientId(),a.executorOwnerJiacn());
            // A task from a different principal cannot commission administrative writes on the executor's account.
            require(!mutation || a.executorOwnerJiacn().equals(a.ownerJiacn()),403,"MODEL_OWNER_REQUIRED");
            authority.requireInstalled(scope,a.agentId(),grant.installation(),grant.host(),price,grant.selections());return action.apply(scope);
        });
    }
    private static String valid(String value,Pattern pattern){require(value!=null && pattern.matcher(value).matches(),400,"BAD_REQUEST");return value;}
    private static ResponseEntity<?> ok(Object value){return ResponseEntity.ok().header("Cache-Control","private, no-store").body(JsonResult.success(value));}
    @PostMapping("/model-prices/lookup") public ResponseEntity<?> lookup(@PathVariable String execution,@PathVariable String run,@RequestBody JsonRequest<Input> request,Authentication auth){
        var c=call(auth,execution,run,request);var model=valid(c.input().model(),MODEL);return ok(authorized(c,true,false,s->operations.readPrice(s,model)));
    }
    @PostMapping("/hero-models/lookup") public ResponseEntity<?> binding(@PathVariable String execution,@PathVariable String run,@RequestBody JsonRequest<Input> request,Authentication auth){
        var c=call(auth,execution,run,request);var persona=valid(c.input().personaCode(),PERSONA);return ok(authorized(c,false,false,s->operations.readBinding(s,persona)));
    }
    // One business intention per canonical operation/input in this run. The receipt is independent of
    // socket lifetime, model tool IDs, lease renewal and retried HTTP request IDs. A receipt-only request
    // never prepares or invokes a mutation. The second authorization covers out-of-transaction quote I/O.
    private ResponseEntity<?> change(Call c,String resource,boolean price,Map<String,String> input,boolean receiptOnly,Supplier<Function<Scope,Map<String,Object>>> prepare){
        var digest=PositionSkillService.hash(SkillCanonicalJson.write(Map.of("resource",resource,"input",input,"snapshot",c.snapshot())));
        var a=c.actor();var key=new Key(a.tenantId(),a.clientId(),a.ownerJiacn(),c.execution(),c.run(),"domain-"+digest);
        var prior=authorized(c,price,true,s->receipts.find(key));
        if(prior!=null)return ok(PrivateToolCallReceipts.replay(prior,resource,digest));
        require(!receiptOnly,404,"MODEL_RECEIPT_NOT_FOUND");var op=prepare.get();
        return ok(authorized(c,price,true,s->receipts.apply(key,resource,digest,()->op.apply(s))));
    }
    private ResponseEntity<?> priceChange(Call c,boolean receipt){var model=valid(c.input().model(),MODEL);return change(c,"model-prices",true,Map.of("model",model),receipt,()->operations.prepareAddPrice(model));}
    private ResponseEntity<?> bindingChange(Call c,boolean receipt){var p=valid(c.input().personaCode(),PERSONA);var m=valid(c.input().model(),MODEL);var old=valid(c.input().expectedModel(),MODEL);return change(c,"hero-models",false,Map.of("personaCode",p,"model",m,"expectedModel",old),receipt,()->s->operations.changeBinding(s,p,old,m));}
    @PostMapping("/model-prices/commit") public ResponseEntity<?> add(@PathVariable String execution,@PathVariable String run,@RequestBody JsonRequest<Input> request,Authentication auth){return priceChange(call(auth,execution,run,request),false);}
    @PostMapping("/model-prices/receipt") public ResponseEntity<?> priceReceipt(@PathVariable String execution,@PathVariable String run,@RequestBody JsonRequest<Input> request,Authentication auth){return priceChange(call(auth,execution,run,request),true);}
    @PostMapping("/hero-models/commit") public ResponseEntity<?> bind(@PathVariable String execution,@PathVariable String run,@RequestBody JsonRequest<Input> request,Authentication auth){return bindingChange(call(auth,execution,run,request),false);}
    @PostMapping("/hero-models/receipt") public ResponseEntity<?> bindingReceipt(@PathVariable String execution,@PathVariable String run,@RequestBody JsonRequest<Input> request,Authentication auth){return bindingChange(call(auth,execution,run,request),true);}
    @ExceptionHandler(PositionSkillException.class) public ResponseEntity<?> denied(PositionSkillException failure){return error(failure.status(),failure.getMessage());}
    @ExceptionHandler(Failure.class) public ResponseEntity<?> unavailable(Failure failure){return error(switch(failure.getReason()){
        case BAD_REQUEST -> 400; case NOT_FOUND -> 404; case CAPABILITY_UNAVAILABLE,STORAGE_UNAVAILABLE -> 503; default -> 409;
    },failure.getReason().name());}
    private static ResponseEntity<?> error(int status,String code){return ResponseEntity.status(status).header("Cache-Control","private, no-store").body(JsonResult.failure(code,"Model maintenance unavailable"));}
}
