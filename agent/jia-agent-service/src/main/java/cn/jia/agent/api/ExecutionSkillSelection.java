package cn.jia.agent.api;

import cn.jia.agent.service.SkillExecutionService;
import cn.jia.core.entity.JsonRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static cn.jia.agent.position.PositionSkillException.require;

/** Trusted control-plane route; not a business resource and not reachable through a skill socket. */
@RestController
@RequestMapping("/internal/agent/executions/{execution}/runs/{run}/skill-selection")
public final class ExecutionSkillSelection extends RunningExecutionEndpoint {
    private static final String BINDING="native-skill-selection";
    private final PrivateToolCallReceipts receipts;
    ExecutionSkillSelection(SkillExecutionService executions,PrivateToolCallReceipts receipts){super(executions);this.receipts=receipts;}
    public record Input(String installationId,String snapshotSha256,Fence fence) implements BoundInput {}
    private static List<Map<String,Object>> match(List<Map<String,Object>> frozen,Object id){
        require(id instanceof String s && !s.isBlank(),400,"BAD_SKILL_SELECTION");
        var matches=frozen.stream().filter(row->row.get("skill") instanceof Map<?,?> sk && id.equals(sk.get("installationId"))).toList();
        require(matches.size()==1,403,"SKILL_SELECTION_NOT_OFFERED");return matches;
    }
    // null means no selected skill, not permission to obtain another binding on retry.
    @PostMapping public ResponseEntity<?> select(@PathVariable String execution,@PathVariable String run,@RequestBody JsonRequest<Input> request,Authentication auth){
        var c=call(auth,execution,run,request);var id=c.input().installationId();
        return ok(executions.withRunningSkills(c.actor(),execution,run,c.fence(),c.snapshot(),grant->{
            require(grant.selections().size()>1,409,"SKILL_SELECTION_NOT_REQUIRED");
            if(id!=null)match(grant.selections(),id);
            var value=id==null?Map.<String,Object>of():Map.<String,Object>of("installationId",id);
            var saved=receipts.apply(receiptKey(c,BINDING),BINDING,c.snapshot(),()->value);
            require(value.equals(saved),409,"SKILL_SELECTION_ALREADY_BOUND");return saved;
        }));
    }
    static List<Map<String,Object>> bound(Call<?> c,List<Map<String,Object>> frozen,PrivateToolCallReceipts receipts){
        if(frozen.size()<=1)return frozen; // No additional query for an already frozen singleton.
        require(c.selectedInstallation()!=null,403,"SKILL_SELECTION_REQUIRED");
        var binding=receipts.find(receiptKey(c,BINDING));require(binding!=null,403,"SKILL_SELECTION_REQUIRED");
        var id=PrivateToolCallReceipts.replay(binding,BINDING,c.snapshot()).get("installationId");
        require(c.selectedInstallation().equals(id),403,"SKILL_SELECTION_MISMATCH");return match(frozen,id);
    }
}
