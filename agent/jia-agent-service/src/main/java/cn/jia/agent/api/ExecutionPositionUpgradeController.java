package cn.jia.agent.api;

import cn.jia.agent.position.PositionSkillPolicy;
import cn.jia.agent.position.PositionSkillService;
import cn.jia.agent.position.PositionSkillState.SkillType;
import cn.jia.agent.service.SkillExecutionService;
import cn.jia.core.entity.JsonRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import static cn.jia.agent.position.PositionSkillException.require;

/** The position domain owns version transitions. A saved definition is not an installation receipt. */
@RestController
@RequestMapping("/internal/agent/executions/{execution}/runs/{run}/business/position-skill-versions")
public final class ExecutionPositionUpgradeController extends ExecutionDomainController {
    static final PositionSkillService.RequiredPositionSkill REQUIRED=new PositionSkillService.RequiredPositionSkill(
            PositionSkillPolicy.PLATFORM_SKILLS,"skill-management","SKILL_MANAGEMENT_POSITION_REQUIRED","SKILL_MANAGEMENT_INSTALLATION_REQUIRED");
    private final PositionSkillService positions;
    ExecutionPositionUpgradeController(SkillExecutionService executions, InstalledDomainAuthority authority, PrivateToolCallReceipts receipts, PositionSkillService positions){
        super(REQUIRED,executions,authority,receipts);this.positions=positions;
    }
    public record Input(String skillKey,String expectedProductVersionId,String targetProductVersionId,String snapshotSha256,Fence fence) implements BoundInput {}
    @PostMapping("/lookup") public ResponseEntity<?> lookup(@PathVariable String execution,@PathVariable String run,@RequestBody JsonRequest<Input> request,Authentication auth){
        var c=call(auth,execution,run,request);var key=c.input().skillKey();PositionSkillPolicy.key(key);
        return ok(authorized(c,false,true,s->{var skill=positions.currentState(s).skills().get(key);
            require(skill!=null && skill.type()==SkillType.POSITION,404,"POSITION_SKILL_NOT_FOUND");
            return Map.of("skillKey",key,"productVersionId",skill.productVersionId());}));
    }
    @PostMapping("/commit") public ResponseEntity<?> upgrade(@PathVariable String execution,@PathVariable String run,@RequestBody JsonRequest<Input> request,Authentication auth){return changeVersion(call(auth,execution,run,request),false);}
    @PostMapping("/receipt") public ResponseEntity<?> receipt(@PathVariable String execution,@PathVariable String run,@RequestBody JsonRequest<Input> request,Authentication auth){return changeVersion(call(auth,execution,run,request),true);}
    private ResponseEntity<?> changeVersion(Call<Input> c,boolean receipt){
        var key=c.input().skillKey();var old=c.input().expectedProductVersionId();var target=c.input().targetProductVersionId();
        PositionSkillPolicy.key(key);PositionSkillPolicy.key(old);PositionSkillPolicy.key(target);
        var intent=Map.of("skillKey",key,"expectedProductVersionId",old,"targetProductVersionId",target);
        return change(c,"position-skill-versions",true,intent,receipt,()->s->{
            var id="upgrade-"+PositionSkillService.hash(cn.jia.agent.service.SkillCanonicalJson.write(java.util.List.of(c.execution(),c.run(),c.snapshot(),intent)));
            var view=positions.upgrade(s,id,key,old,target);
            return Map.of("skillKey",key,"productVersionId",target,"operationId",view.operationId(),"revision",Long.toString(view.revision()),"disposition","DEFINITION_UPDATED");
        });
    }
}
