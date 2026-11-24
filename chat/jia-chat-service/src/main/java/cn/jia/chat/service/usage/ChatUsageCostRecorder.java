package cn.jia.chat.service.usage;

import cn.jia.agent.service.AuthenticatedRuntimeOperation;
import cn.jia.chat.deliberation.ChatDeliberationDao;
import cn.jia.chat.deliberation.ChatDispatchAuthorityMapper;
import cn.jia.chat.service.ChatDeliberationException;
import cn.jia.economy.service.EconomyScope;
import cn.jia.economy.usage.UsageCostService;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Source attribution only: original CHAT dispatch, not current task admission or final-message authority. */
@Service
public final class ChatUsageCostRecorder {
    public enum Phase { STARTED, TERMINAL }
    public record Evidence(Phase phase, String requestedModel, String usageEvidenceJson) { }
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ChatUsageDispatchAuthority authority;private final UsageCostService costs;
    public ChatUsageCostRecorder(ChatUsageDispatchAuthority authority, UsageCostService costs){this.authority=authority;this.costs=costs;}
    public UsageCostService.Receipt record(AuthenticatedRuntimeOperation.Scope scope,String dispatchId,Evidence evidence){
        var original=authority.original(scope,dispatchId);var dispatch=original.dispatch();var turn=original.turn();var taskId=original.taskId();
        var binding = new UsageCostService.Binding(new EconomyScope(scope.tenantId(), scope.clientId()),
                dispatch.getOwnerJiacn(), taskId, "CHAT_EXECUTION_EVIDENCE", dispatchId);
        var raw = new LinkedHashMap<String, Object>();
        raw.put("dispatchId", dispatchId); raw.put("turnId", dispatch.getTurnId());
        raw.put("conversationId", turn.getConversationId()); raw.put("phase", evidence.phase().name());
        raw.put("requestedModel", evidence.requestedModel()); raw.put("usageEvidence", JSON.readTree(evidence.usageEvidenceJson()));
        boolean started = evidence.phase() == Phase.STARTED;
        // Native thread/turn counters are evidence, not verified individual provider billing.
        return costs.record(new UsageCostService.Observation(binding, started ? "started" : "terminal", started ? 0 : 1,
                JSON.writeValueAsString(raw), null, null, 0));
    }
}
