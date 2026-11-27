package cn.jia.agent.api;

import cn.jia.agent.service.SkillExecutionService;
import cn.jia.core.entity.JsonRequest;
import cn.jia.economy.pricing.CostRouteReference;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Administrative resource, not an online quote or untrusted Runtime registration endpoint. */
@RestController
@RequestMapping("/internal/agent/executions/{execution}/runs/{run}/business/cost-route-bindings")
public final class ExecutionCostRouteController extends ExecutionDomainController {
    private final CostRouteMaintenanceOperations operations;
    ExecutionCostRouteController(SkillExecutionService executions,InstalledDomainAuthority authority,
            PrivateToolCallReceipts receipts,CostRouteMaintenanceOperations operations){
        super(ExecutionModelPriceController.REQUIRED,executions,authority,receipts);this.operations=operations;
    }
    public record Input(CostRouteReference reference,String expectedRevision,String action,Map<String,Object> route,
                        String snapshotSha256,Fence fence) implements BoundInput{}
    @PostMapping("/lookup") public ResponseEntity<?> lookup(@PathVariable String execution,@PathVariable String run,
            @RequestBody JsonRequest<Input> request,Authentication auth){
        var c=call(auth,execution,run,request);
        return ok(authorized(c,true,false,scope->operations.read(scope,c.input().reference())));
    }
    @PostMapping("/commit") public ResponseEntity<?> commit(@PathVariable String execution,@PathVariable String run,
            @RequestBody JsonRequest<Input> request,Authentication auth){return mutate(call(auth,execution,run,request),false);}
    @PostMapping("/receipt") public ResponseEntity<?> receipt(@PathVariable String execution,@PathVariable String run,
            @RequestBody JsonRequest<Input> request,Authentication auth){return mutate(call(auth,execution,run,request),true);}
    private ResponseEntity<?> mutate(Call<Input> c,boolean receipt){
        var i=c.input();var prepared=CostRouteMaintenanceOperations.prepare(i.reference(),i.expectedRevision(),i.action(),i.route());
        return change(c,"cost-route-bindings",true,prepared.intent(),receipt,()->scope->operations.apply(scope,prepared));
    }
}
