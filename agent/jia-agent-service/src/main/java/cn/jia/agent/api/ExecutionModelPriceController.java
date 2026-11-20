package cn.jia.agent.api;

import cn.jia.agent.position.PositionSkillService;
import cn.jia.agent.service.PersonalWorkspaceExecutionService.*;
import cn.jia.agent.service.SkillExecutionService;
import cn.jia.core.entity.JsonRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.regex.Pattern;
import static cn.jia.agent.position.PositionSkillException.require;

/** Model domain HTTP resources, shared by native execution contexts. No conversation tool catalog. */
@RestController
@RequestMapping("/internal/agent/executions/{execution}/runs/{run}/business")
public final class ExecutionModelPriceController extends ExecutionDomainController {
    private static final Pattern MODEL=Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{0,99}"), PERSONA=Pattern.compile("[a-z0-9][a-z0-9-]{0,99}");
    static final PositionSkillService.RequiredPositionSkill REQUIRED=new PositionSkillService.RequiredPositionSkill(
        cn.jia.agent.position.PositionSkillPolicy.PLATFORM_MODELS,"model-maintenance","MODEL_POSITION_REQUIRED","MODEL_SKILL_REQUIRED");
    private final ModelMaintenanceOperations operations;
    ExecutionModelPriceController(SkillExecutionService executions,InstalledDomainAuthority authority,ModelMaintenanceOperations operations,PrivateToolCallReceipts receipts) {
        super(REQUIRED,executions,authority,receipts);this.operations=operations;
    }
    public record Input(String model,String personaCode,String expectedModel,String snapshotSha256,Fence fence) implements BoundInput {}
    private static String valid(String value,Pattern pattern){require(value!=null && pattern.matcher(value).matches(),400,"BAD_REQUEST");return value;}
    @PostMapping("/model-prices/lookup") public ResponseEntity<?> lookup(@PathVariable String execution,@PathVariable String run,@RequestBody JsonRequest<Input> request,Authentication auth){
        var c=call(auth,execution,run,request);var model=valid(c.input().model(),MODEL);return ok(authorized(c,true,false,s->operations.readPrice(s,model)));
    }
    @PostMapping("/hero-models/lookup") public ResponseEntity<?> binding(@PathVariable String execution,@PathVariable String run,@RequestBody JsonRequest<Input> request,Authentication auth){
        var c=call(auth,execution,run,request);var persona=valid(c.input().personaCode(),PERSONA);return ok(authorized(c,false,false,s->operations.readBinding(s,persona)));
    }
    private ResponseEntity<?> priceChange(Call<Input> c,boolean receipt){var model=valid(c.input().model(),MODEL);return change(c,"model-prices",true,Map.of("model",model),receipt,()->operations.prepareAddPrice(model));}
    private ResponseEntity<?> bindingChange(Call<Input> c,boolean receipt){var p=valid(c.input().personaCode(),PERSONA);var m=valid(c.input().model(),MODEL);var old=valid(c.input().expectedModel(),MODEL);return change(c,"hero-models",false,Map.of("personaCode",p,"model",m,"expectedModel",old),receipt,()->s->operations.changeBinding(s,p,old,m));}
    @PostMapping("/model-prices/commit") public ResponseEntity<?> add(@PathVariable String execution,@PathVariable String run,@RequestBody JsonRequest<Input> request,Authentication auth){return priceChange(call(auth,execution,run,request),false);}
    @PostMapping("/model-prices/receipt") public ResponseEntity<?> priceReceipt(@PathVariable String execution,@PathVariable String run,@RequestBody JsonRequest<Input> request,Authentication auth){return priceChange(call(auth,execution,run,request),true);}
    @PostMapping("/hero-models/commit") public ResponseEntity<?> bind(@PathVariable String execution,@PathVariable String run,@RequestBody JsonRequest<Input> request,Authentication auth){return bindingChange(call(auth,execution,run,request),false);}
    @PostMapping("/hero-models/receipt") public ResponseEntity<?> bindingReceipt(@PathVariable String execution,@PathVariable String run,@RequestBody JsonRequest<Input> request,Authentication auth){return bindingChange(call(auth,execution,run,request),true);}
}
