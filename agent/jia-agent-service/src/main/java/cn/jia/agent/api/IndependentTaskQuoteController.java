package cn.jia.agent.api;

import cn.jia.agent.exception.AgentTaskCollaborationException;
import cn.jia.agent.exception.PersonalWorkspaceException;
import cn.jia.agent.hosting.HostingRentApplicationException;
import cn.jia.agent.hosting.HostingRentHttp;
import cn.jia.agent.service.AgentTaskExecutionGrantException;
import cn.jia.agent.service.TaskFeatureEncoder;
import cn.jia.agent.service.recommendation.IndependentTaskQuotes;
import cn.jia.core.context.EsContextHolder;
import cn.jia.core.entity.JsonRequest;
import cn.jia.core.entity.JsonResult;
import cn.jia.economy.task.TaskQuoteBatches;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/agent/tasks")
public final class IndependentTaskQuoteController {
    private final IndependentTaskQuotes quotes;
    public IndependentTaskQuoteController(IndependentTaskQuotes quotes){this.quotes=quotes;}
    @PostMapping("/{taskId}/quotes")
    public ResponseEntity<JsonResult<TaskQuoteBatches.Result>> quote(@PathVariable String taskId,@RequestBody JsonRequest<JsonNode> request,
            @RequestHeader(value="Idempotency-Key",required=false) List<String> keys,Authentication authentication){
        var a=HostingRentHttp.actor(authentication);var ctx=EsContextHolder.getContext();
        if(ctx==null||!Objects.equals(ctx.getTenantId(),a.tenantId())||!Objects.equals(ctx.getClientId(),a.clientId())||!Objects.equals(ctx.getJiacn(),a.ownerJiacn()))throw new IndependentTaskQuotes.NotFound();
        if(keys==null||keys.size()!=1||keys.getFirst().isBlank())throw new BadRequest();var data=request==null?null:request.data();
        if(data==null||!data.isObject()||data.size()!=2||!data.path("expectedTaskVersion").isString()||!data.path("agentIds").isArray())throw new BadRequest();
        var ids=new ArrayList<String>();for(var id:data.get("agentIds")){if(!id.isString())throw new BadRequest();ids.add(id.asText());}
        TaskQuoteBatches.Intent intent;try{intent=new TaskQuoteBatches.Intent(data.get("expectedTaskVersion").asText(),ids);}catch(IllegalArgumentException invalid){throw new BadRequest();}
        return ResponseEntity.ok().header("Cache-Control","private, no-store").body(JsonResult.success(quotes.quote(a,taskId,keys.getFirst(),intent)));
    }
    private static class BadRequest extends RuntimeException{}
    private ResponseEntity<JsonResult<Void>> error(int status,String code){return ResponseEntity.status(status).header("Cache-Control","private, no-store").body(JsonResult.failure(code,code));}
    @ExceptionHandler({BadRequest.class,org.springframework.http.converter.HttpMessageNotReadableException.class}) public ResponseEntity<JsonResult<Void>> bad(){return error(400,"BAD_REQUEST");}
    @ExceptionHandler(HostingRentApplicationException.class) public ResponseEntity<JsonResult<Void>> auth(HostingRentApplicationException e){return e.status()==401?error(401,"UNAUTHENTICATED"):error(404,"QUOTE_NOT_FOUND");}
    @ExceptionHandler(IndependentTaskQuotes.NotFound.class) public ResponseEntity<JsonResult<Void>> missing(){return error(404,"QUOTE_NOT_FOUND");}
    @ExceptionHandler(IndependentTaskQuotes.Changed.class) public ResponseEntity<JsonResult<Void>> changed(){return error(409,"QUOTE_STALE");}
    @ExceptionHandler(TaskQuoteBatches.IntentConflict.class) public ResponseEntity<JsonResult<Void>> conflict(){return error(409,"QUOTE_IDEMPOTENCY_CONFLICT");}
    @ExceptionHandler(TaskFeatureEncoder.InvalidResult.class) public ResponseEntity<JsonResult<Void>> invalid(){return error(502,"QUOTE_FEATURE_INVALID");}
    @ExceptionHandler(TaskFeatureEncoder.Unavailable.class) public ResponseEntity<JsonResult<Void>> upstream(){return error(503,"QUOTE_FEATURE_UNAVAILABLE");}
    @ExceptionHandler(AgentTaskCollaborationException.class) public ResponseEntity<JsonResult<Void>> task(AgentTaskCollaborationException e){return switch(e.getReason()){
        case NOT_FOUND,FORBIDDEN -> missing();case VERSION_CONFLICT,INVALID_TRANSITION -> changed();default -> error(503,"QUOTE_UNAVAILABLE");};}
    @ExceptionHandler(AgentTaskExecutionGrantException.class) public ResponseEntity<JsonResult<Void>> grant(AgentTaskExecutionGrantException e){return switch(e.status()){
        case 400 -> bad();case 401 -> error(401,"UNAUTHENTICATED");case 403,404 -> missing();case 409 -> changed();default -> error(503,"QUOTE_UNAVAILABLE");};}
    @ExceptionHandler(PersonalWorkspaceException.class) public ResponseEntity<JsonResult<Void>> workspace(PersonalWorkspaceException e){return switch(e.getReason()){
        case NOT_FOUND -> missing();case VERSION_CONFLICT,METADATA_CHANGED -> changed();default -> error(503,"QUOTE_UNAVAILABLE");};}
    @ExceptionHandler(cn.jia.agent.entity.AssigneeRecommendation.Failure.class) public ResponseEntity<JsonResult<Void>> material(cn.jia.agent.entity.AssigneeRecommendation.Failure e){return e.status()==409?changed():error(503,"QUOTE_UNAVAILABLE");}
    @ExceptionHandler(RuntimeException.class) public ResponseEntity<JsonResult<Void>> unexpected(RuntimeException e){org.slf4j.LoggerFactory.getLogger(getClass()).warn("QUOTE_UNAVAILABLE requestId={} category={}",org.slf4j.MDC.get("request_id"),e.getClass().getSimpleName());return error(503,"QUOTE_UNAVAILABLE");}
}
