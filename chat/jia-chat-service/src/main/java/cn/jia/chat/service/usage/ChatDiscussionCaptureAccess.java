package cn.jia.chat.service.usage;

import cn.jia.agent.service.AuthenticatedRuntimeOperation;
import cn.jia.agent.service.TaskDiscussionCaptureLookup;
import cn.jia.economy.usage.UsageCaptureJournal.Binding;
import java.security.Principal;
import java.util.function.Function;
import org.springframework.stereotype.Service;

/** A binding never enters through the wire. POST and read-back use this same fence. */
@Service
public final class ChatDiscussionCaptureAccess {
    private final AuthenticatedRuntimeOperation auth;
    private final ChatUsageDispatchAuthority dispatches;
    private final TaskDiscussionCaptureLookup members;
    public ChatDiscussionCaptureAccess(AuthenticatedRuntimeOperation auth,ChatUsageDispatchAuthority dispatches,TaskDiscussionCaptureLookup members){
        this.auth=auth;this.dispatches=dispatches;this.members=members;
    }
    public <T> T withCapture(Principal principal,String turn,String dispatch,String contextHash,String capture,Function<Binding,T> operation){
        return auth.withAuthenticatedDevice(principal,device->operation.apply(dispatches.capture(device,turn,dispatch,contextHash,capture,members)));
    }
}
