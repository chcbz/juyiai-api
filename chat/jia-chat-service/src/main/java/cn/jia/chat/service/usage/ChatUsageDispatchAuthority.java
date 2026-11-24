package cn.jia.chat.service.usage;

import cn.jia.agent.service.AuthenticatedRuntimeOperation;
import cn.jia.chat.deliberation.*;
import cn.jia.chat.service.ChatDeliberationException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import java.util.*;

/** Original dispatch ownership for cost observations, never authorization to run a task. */
@Service
public final class ChatUsageDispatchAuthority {
    private final ChatTaskDispatchOriginStore origins;
    private final ChatDispatchAuthorityMapper dispatches;private final ChatDeliberationDao turns;
    private static final ObjectMapper JSON=new ObjectMapper();
    public ChatUsageDispatchAuthority(ChatDispatchAuthorityMapper dispatches,ChatDeliberationDao turns,ChatTaskDispatchOriginStore origins){this.origins=origins;this.dispatches=dispatches;this.turns=turns;}
    record Original(ChatDispatchOutboxEntity dispatch,ChatTurnEntity turn,String taskId,boolean singleBountyExecutor) { }
    Original original(AuthenticatedRuntimeOperation.Scope scope,String dispatchId){
        var rows = dispatches.findDispatch(scope.tenantId(), scope.clientId(), dispatchId);
        if (rows == null || rows.size() != 1) throw denied();
        var dispatch = rows.getFirst();
        var turn = turns.findTurn(scope.tenantId(), dispatch.getOwnerJiacn(), scope.clientId(), dispatch.getTurnId());
        Map<?, ?> payload;
        try { payload = JSON.readValue(dispatch.getPayloadJson(), Map.class); }
        catch (RuntimeException invalid) { throw denied(); }
        if (payload == null || turn == null || !"CHAT".equals(payload.get("route"))
                || !Objects.equals(dispatchId, payload.get("dispatchId"))
                || !Objects.equals(dispatch.getTurnId(), payload.get("turnId"))
                || !Objects.equals(scope.agentId(), payload.get("targetAgentId"))
                || !Objects.equals(turn.getTargetAgentId(), payload.get("targetAgentId"))
                || !Objects.equals(turn.getConversationId(), payload.get("conversationId"))) throw denied();
        Object taskValue = payload.get("taskId");
        if (taskValue != null && (!(taskValue instanceof String) || ((String) taskValue).isBlank())) throw denied();
        String taskId = (String) taskValue;
        boolean multiMember = payload.get("factsManifest") instanceof Map<?, ?> facts
                && facts.get("participantAgentIds") instanceof List<?> members
                && members.size() > 1 && members.contains(scope.agentId());
        if (dispatch.getTaskGrantId() == null && dispatch.getTaskGrantVersion() == null
                && (!"bounty".equals(payload.get("conversationScopeType")) || multiMember)) {
            // Preserve original owner-only transport for private/public/team conversations.
            if (!Objects.equals(dispatch.getOwnerJiacn(), scope.ownerJiacn())) throw denied();
        } else if (taskId == null || dispatch.getTaskGrantId() == null || dispatch.getTaskGrantVersion() == null
                || !dispatches.hasOriginalUsageExecutor(scope.tenantId(), scope.clientId(), dispatch.getOwnerJiacn(),
                    taskId, dispatch.getTaskGrantId(), scope.agentId(), scope.ownerJiacn())) throw denied();
        // Absence of multi-member evidence is not evidence of a single executor.
        boolean singleBountyExecutor = "bounty".equals(payload.get("conversationScopeType"))
                && payload.get("factsManifest") instanceof Map<?, ?> facts
                && List.of(scope.agentId()).equals(facts.get("participantAgentIds"));
        return new Original(dispatch, turn, taskId, singleBountyExecutor);
    }
    /** Transport attribution only. Neither an ACK nor a final reply proves measured usage. */
    public enum TaskSource { UNMATCHED, OBSERVED, FINAL_BOUND }

    public TaskSource taskSource(AuthenticatedRuntimeOperation.Device device, String turnId, String dispatchId, String contextHash) {
        var original = original(device.subject(), dispatchId);
        var dispatch = original.dispatch();
        var turn = original.turn();
        var fence = device.fence();
        if (original.taskId() == null || !original.singleBountyExecutor() || dispatch.getTaskGrantId() == null
                || dispatch.getTaskGrantVersion() == null || !Objects.equals(dispatch.getTurnId(), turnId)
                || !Objects.equals(turn.getDispatchId(), dispatchId) || !Objects.equals(turn.getContextDigest(), contextHash)
                || !origins.matches(dispatch.getEventId(), contextHash, fence.runtimeInstallationId(), fence.hostId(),
                        fence.runtimeInstanceId(), fence.sessionGeneration())) return TaskSource.UNMATCHED;
        // SENT alone may still undergo pre-turn bootstrap replacement. A persisted final closes that path.
        boolean finalBound = "SENT".equals(dispatch.getStatus()) && turn.getFinalMessageId() != null
                && ("FINAL_PERSISTED".equals(turn.getState()) || "PUBLISHED".equals(turn.getState()));
        return finalBound ? TaskSource.FINAL_BOUND : TaskSource.OBSERVED;
    }
    private static ChatDeliberationException denied() {
        return new ChatDeliberationException(ChatDeliberationException.Reason.NOT_FOUND_OR_FORBIDDEN,
                "Original CHAT usage authority is unavailable");
    }
}
