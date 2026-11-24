package cn.jia.chat.service;

import cn.jia.agent.service.AgentManagedSessionLookup.SessionFence;
import cn.jia.chat.deliberation.ChatTaskDispatchOriginStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Persist the selected socket identity under the outbox lease, before the network write. */
@Service
public class ChatTaskDispatchOriginService {
    private final ChatDeliberationOutboxService outbox;
    private final ChatTaskDispatchOriginStore origins;
    public ChatTaskDispatchOriginService(ChatDeliberationOutboxService outbox,ChatTaskDispatchOriginStore origins){this.outbox=outbox;this.origins=origins;}
    @Transactional(rollbackFor=Exception.class)
    public void beforeSend(ChatDeliberationOutboxService.Claim claim,String frozenContext, SessionFence device){
        var row=outbox.requireActiveClaim(claim,System.currentTimeMillis());
        if (!"DISPATCH".equals(row.getEventType()) || row.getTaskGrantId()==null) throw new IllegalStateException("Missing original task dispatch");
        origins.observe(row.getEventId(),frozenContext,device.runtimeInstallationId(),device.hostId(),device.runtimeInstanceId(),device.sessionGeneration());
    }
}
