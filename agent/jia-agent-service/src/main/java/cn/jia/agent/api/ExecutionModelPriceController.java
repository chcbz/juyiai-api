package cn.jia.agent.api;

import cn.jia.agent.position.PositionSkillException;
import cn.jia.agent.security.AgentRuntimeAuthenticationService;
import cn.jia.agent.service.PersonalWorkspaceExecutionService.*;
import cn.jia.agent.service.PositionAuthority;
import cn.jia.agent.service.SkillExecutionService;
import cn.jia.core.entity.JsonRequest;
import cn.jia.core.entity.JsonResult;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import static cn.jia.agent.position.PositionSkillException.require;

/** Read-only domain resource. Native transport supplies the lease; the package never receives it. */
@RestController
@RequestMapping("/internal/agent/executions/{execution}/runs/{run}/business/model-prices")
public final class ExecutionModelPriceController {
    private final SkillExecutionService executions;
    private final ModelMaintenanceAuthority authority;
    private final ModelMaintenanceOperations prices;
    ExecutionModelPriceController(SkillExecutionService executions,ModelMaintenanceAuthority authority,ModelMaintenanceOperations prices) {
        this.executions=executions;this.authority=authority;this.prices=prices;
    }
    public record Fence(String version,String token) { @Override public String toString(){return "Fence[redacted]";} }
    public record Lookup(String model,String snapshotSha256,Fence fence) {}

    @PostMapping("/lookup")
    public ResponseEntity<?> lookup(@PathVariable String execution,@PathVariable String run,
            @RequestBody JsonRequest<Lookup> envelope,Authentication authentication) {
        var p=AgentRuntimeAuthenticationService.requireProof(authentication).scope();
        var data=envelope==null?null:envelope.data();
        require(data!=null && data.model()!=null && data.model().matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,99}")
                && data.snapshotSha256()!=null && data.snapshotSha256().matches("[0-9a-f]{64}") && data.fence()!=null
                && data.fence().version()!=null && data.fence().version().matches("[1-9][0-9]{0,15}")
                && data.fence().token()!=null && !data.fence().token().isBlank(),400,"BAD_REQUEST");
        var subject=new RuntimeScope(p.tenantId(),p.clientId(),p.ownerJiacn(),p.agentId(),p.runtimeInstanceId());
        var fence=new ConversationFence(Long.parseLong(data.fence().version()),data.fence().token());
        var value=executions.withRunningSkills(subject,execution,run,fence,data.snapshotSha256(),grant->{
            var actor=grant.subject(); // A task owner cannot lend their model-maintenance role to the executor.
            var scope=new PositionAuthority.Scope(actor.tenantId(),actor.clientId(),actor.executorOwnerJiacn());
            authority.requireInstalled(scope,actor.agentId(),grant.installation(),grant.host(),true,grant.selections());
            return prices.readPrice(scope,data.model());
        });
        return ResponseEntity.ok().header("Cache-Control","private, no-store").body(JsonResult.success(value));
    }
    @ExceptionHandler(PositionSkillException.class)
    public ResponseEntity<?> denied(PositionSkillException failure){return error(failure.status(),failure.getMessage());}
    @ExceptionHandler(Failure.class)
    public ResponseEntity<?> unavailable(Failure failure){return error(switch(failure.getReason()){
        case BAD_REQUEST -> 400; case NOT_FOUND -> 404; case CAPABILITY_UNAVAILABLE,STORAGE_UNAVAILABLE -> 503; default -> 409;
    },failure.getReason().name());}
    private static ResponseEntity<?> error(int status,String code){return ResponseEntity.status(status).header("Cache-Control","private, no-store").body(JsonResult.failure(code,"Model price lookup unavailable"));}
}
