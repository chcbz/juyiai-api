package cn.jia.chat.api;

import cn.jia.chat.service.ChatDeliberationException;
import cn.jia.chat.service.usage.ChatDiscussionCaptureAccess;
import cn.jia.agent.service.RuntimeCaptureIntake;
import cn.jia.core.entity.JsonRequest;
import cn.jia.core.entity.JsonResult;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/agent/chat/dispatches/{dispatch}/turns/{turn}/contexts/{context}/captures/{capture}/usage")
public final class ChatDiscussionCaptureController {
    private static final tools.jackson.databind.ObjectMapper JSON=new tools.jackson.databind.ObjectMapper();
    private final ChatDiscussionCaptureAccess access;
    private final RuntimeCaptureIntake http;
    public ChatDiscussionCaptureController(ChatDiscussionCaptureAccess access,RuntimeCaptureIntake http){this.access=access;this.http=http;}
    private RuntimeCaptureIntake.Fence fence(Authentication principal,String turn,String dispatch,String context,String capture){
        return op->access.withCapture(principal,turn,dispatch,"sha256:"+context,capture,op);
    }
    @PostMapping(consumes="application/json",produces="application/json")
    public ResponseEntity<?> submit(@PathVariable String dispatch,@PathVariable String turn,@PathVariable String context,@PathVariable String capture,
            @RequestBody JsonRequest<Map<String,Object>> request,@RequestHeader(value="Idempotency-Key",required=false) String key,Authentication principal){
        return wire(http.submit(turn,dispatch,request,key,fence(principal,turn,dispatch,context,capture),
                (binding,snapshot,obs)->context.equals(snapshot) && "CHAT_SOURCE_UNVERIFIED_V1".equals(obs.get("coveragePolicyVersion")) && !obs.containsKey("dispatchGroup")));
    }
    @GetMapping(value="/receipts/{id}",produces="application/json")
    public ResponseEntity<?> receipt(@PathVariable String dispatch,@PathVariable String turn,@PathVariable String context,@PathVariable String capture,@PathVariable String id,Authentication principal){
        return wire(http.receipt(fence(principal,turn,dispatch,context,capture),id));
    }
    @GetMapping(value="/records/{id}",produces="application/json")
    public ResponseEntity<?> current(@PathVariable String dispatch,@PathVariable String turn,@PathVariable String context,@PathVariable String capture,@PathVariable String id,Authentication principal){
        return wire(http.current(fence(principal,turn,dispatch,context,capture),id));
    }
    private static ResponseEntity<?> wire(RuntimeCaptureIntake.Reply r){
        return ResponseEntity.status(r.status()).header("Cache-Control","private, no-store").body(r.code()==null
                ? JsonResult.success(JSON.readTree(r.ackJson())) : JsonResult.failure(r.code(),"Usage evidence could not be recorded"));
    }
    @ExceptionHandler(ChatDeliberationException.class)
    public ResponseEntity<JsonResult<Void>> unavailable(ChatDeliberationException ignored){
        return ResponseEntity.status(404).header("Cache-Control","private, no-store").body(JsonResult.failure("CHAT_USAGE_DISPATCH_NOT_FOUND","Discussion evidence unavailable"));
    }
}
