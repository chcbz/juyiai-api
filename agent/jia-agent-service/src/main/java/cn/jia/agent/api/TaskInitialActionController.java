package cn.jia.agent.api;

import cn.jia.agent.service.*;
import cn.jia.agent.hosting.HostingRentHttp;
import cn.jia.core.context.EsContextHolder;
import cn.jia.core.entity.*;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/agent/tasks/{taskId}/initial-actions/{agentId}")
@ConditionalOnProperty(name="jia.agent.task-quotes.prepared-observations-enabled",havingValue="true")
public final class TaskInitialActionController {
    private final TaskInitialActionSelection choices;
    public TaskInitialActionController(TaskInitialActionSelection choices){this.choices=choices;}
    private HostingRentHttp.Actor actor(Authentication authentication){var a=HostingRentHttp.actor(authentication);var c=EsContextHolder.getContext();
        if(c==null||!Objects.equals(a.ownerJiacn(),c.getJiacn())||!Objects.equals(a.tenantId(),c.getTenantId())||!Objects.equals(a.clientId(),c.getClientId()))throw new TaskInitialActionSelection.Missing();
        return a;}
    private AgentTaskExecutionGrantService.Scope scope(HostingRentHttp.Actor a){return new AgentTaskExecutionGrantService.Scope(a.tenantId(),a.clientId(),a.ownerJiacn());}
    @GetMapping public ResponseEntity<?> read(@PathVariable String taskId,@PathVariable String agentId,Authentication auth){return ok(choices.view(scope(actor(auth)),taskId,agentId));}
    @GetMapping("/skill-options") public ResponseEntity<?> options(@PathVariable String taskId,@PathVariable String agentId,Authentication auth){return ok(choices.skillOptions(scope(actor(auth)),taskId,agentId));}
    @PutMapping public ResponseEntity<?> select(@PathVariable String taskId,@PathVariable String agentId,@RequestBody JsonRequest<JsonNode> req,@RequestHeader(value="Idempotency-Key",required=false) java.util.List<String> keys,Authentication auth){
        var a=actor(auth);var owner=scope(a);if(keys==null||keys.size()!=1||keys.getFirst().isBlank())return error(400,"BAD_REQUEST");var d=req==null?null:req.data();if(d==null||!d.isObject()||!d.path("selectionVersion").isString()||!d.path("selectionVersion").asText().matches("[a-f0-9]{64}")||!d.path("choice").isString())return error(400,"BAD_REQUEST");
        if("INSTALLED_SKILL".equals(d.get("choice").asText())){
            var id=d.path("optionRef");var mime=d.path("mimeType");
            if(d.size()!=4||!id.isString()||!id.asText().matches("[a-f0-9]{64}")||!mime.isString()||mime.asText().length()>127
                    ||!mime.asText().matches("[a-z0-9][a-z0-9.+-]*/[a-z0-9][a-z0-9.+-]*"))return error(400,"BAD_REQUEST");
            return ok(choices.chooseSkill(owner,taskId,agentId,d.get("selectionVersion").asText(),new TaskInitialActionSelection.SkillChoice(id.asText(),mime.asText()),a.actorId(),keys.getFirst()));
        }
        if(d.size()!=2)return error(400,"BAD_REQUEST");
        final TaskInitialActionSelection.Choice choice;try{choice=TaskInitialActionSelection.Choice.valueOf(d.path("choice").asText());}catch(IllegalArgumentException unknown){return error(400,"BAD_REQUEST");}
        return ok(choices.choose(owner,taskId,agentId,d.path("selectionVersion").asText(),choice,a.actorId(),keys.getFirst()));}
    private ResponseEntity<?> ok(Object v){return ResponseEntity.ok().header("Cache-Control","private, no-store").body(JsonResult.success(v));}
    private ResponseEntity<?> error(int status,String code){return ResponseEntity.status(status).header("Cache-Control","private, no-store").body(JsonResult.failure(code,code));}
    @ExceptionHandler(TaskInitialActionSelection.KeyConflict.class) public ResponseEntity<?> conflict(){return error(409,"INITIAL_ACTION_IDEMPOTENCY_CONFLICT");}
    @ExceptionHandler(TaskInitialActionSelection.Changed.class) public ResponseEntity<?> changed(){return error(409,"INITIAL_ACTION_STALE");}
    @ExceptionHandler(TaskInitialActionSelection.Missing.class) public ResponseEntity<?> missing(){return error(404,"INITIAL_ACTION_NOT_FOUND");}
    @ExceptionHandler(cn.jia.agent.hosting.HostingRentApplicationException.class) public ResponseEntity<?> auth(cn.jia.agent.hosting.HostingRentApplicationException e){return e.status()==401?error(401,"UNAUTHENTICATED"):missing();}
    @ExceptionHandler(cn.jia.agent.exception.AgentTaskCollaborationException.class) public ResponseEntity<?> root(cn.jia.agent.exception.AgentTaskCollaborationException e){return switch(e.getReason()){case NOT_FOUND,FORBIDDEN->missing();case VERSION_CONFLICT,INVALID_TRANSITION->changed();default->error(503,"INITIAL_ACTION_UNAVAILABLE");};}
    @ExceptionHandler(AgentTaskRequirementReadException.class) public ResponseEntity<?> req(AgentTaskRequirementReadException e){return switch(e.reason()){case NOT_FOUND->missing();case RECONFIRM_REQUIRED->changed();default->error(503,"INITIAL_ACTION_UNAVAILABLE");};}
    @ExceptionHandler(AgentTaskExecutionGrantException.class) public ResponseEntity<?> grant(AgentTaskExecutionGrantException e){return switch(e.status()){case 403,404->missing();case 409->changed();default->error(503,"INITIAL_ACTION_UNAVAILABLE");};}
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class) public ResponseEntity<?> bad(){return error(400,"BAD_REQUEST");}
}
