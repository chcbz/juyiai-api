package cn.jia.chat.service.usage;

import cn.jia.agent.service.AuthenticatedRuntimeOperation;
import cn.jia.chat.deliberation.*;
import cn.jia.chat.service.ChatDeliberationException;
import cn.jia.economy.usage.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.List;

class ChatUsageCostRecorderTest {
    private final ChatDispatchAuthorityMapper dispatches = mock(ChatDispatchAuthorityMapper.class);
    private final ChatDeliberationDao turns = mock(ChatDeliberationDao.class);
    private final UsageCostService costs = mock(UsageCostService.class);
    private final ChatUsageCostRecorder recorder = new ChatUsageCostRecorder(new ChatUsageDispatchAuthority(dispatches, turns, mock(cn.jia.chat.deliberation.ChatTaskDispatchOriginStore.class)), costs);
    private final AuthenticatedRuntimeOperation.Scope scope =
            new AuthenticatedRuntimeOperation.Scope("0", "client", "executor", "agent");
    private ChatDispatchOutboxEntity dispatch;
    private ChatTurnEntity turn;
    private String payload(String task, String type) {
        return "{\"route\":\"CHAT\",\"dispatchId\":\"dispatch\",\"turnId\":\"turn\","
                + "\"targetAgentId\":\"agent\",\"conversationId\":\"conversation\","
                + "\"taskId\":" + task + ",\"conversationScopeType\":\"" + type + "\"}";
    }
    private ChatUsageCostRecorder.Evidence terminal() {
        return new ChatUsageCostRecorder.Evidence(ChatUsageCostRecorder.Phase.TERMINAL,
                "requested-only", "{\"last\":{\"inputTokens\":10},\"status\":\"UNVERIFIED\"}");
    }
    @BeforeEach void setup() {
        dispatch = new ChatDispatchOutboxEntity().setOwnerJiacn("task-owner").setTurnId("turn")
                .setDispatchId("dispatch").setTaskGrantId("original-grant").setTaskGrantVersion(1L)
                .setStatus("SENT").setPayloadJson(payload("\"task\"", "bounty"));
        turn = new ChatTurnEntity().setTurnId("turn").setTargetAgentId("agent")
                .setConversationId("conversation").setState("CANCELLED");
        when(dispatches.findDispatch("0", "client", "dispatch")).thenReturn(List.of(dispatch));
        when(turns.findTurn("0", "task-owner", "client", "turn")).thenReturn(turn);
        when(dispatches.hasOriginalUsageExecutor("0", "client", "task-owner", "task",
                "original-grant", "agent", "executor")).thenReturn(true);
    }
    @Test void lateCancelledTurnUsesOriginalExecutorAndRecordsEvidenceNotGuessedCost() {
        var receipt = new UsageCostService.Receipt("attempt", 2, TokenUsageCost.Status.PENDING_USAGE, null, false);
        when(costs.record(any())).thenReturn(receipt);
        assertSame(receipt, recorder.record(scope, "dispatch", terminal()));
        var argument = ArgumentCaptor.forClass(UsageCostService.Observation.class);
        verify(costs).record(argument.capture());
        var observation = argument.getValue();
        assertEquals("task-owner", observation.binding().ownerJiacn());
        assertEquals("task", observation.binding().taskId());
        assertEquals("CHAT_EXECUTION_EVIDENCE", observation.binding().source());
        assertEquals("dispatch", observation.binding().sourceAttemptId());
        assertEquals("terminal", observation.receiptId()); assertEquals(1, observation.expectedRevision());
        assertNull(observation.normalizedUsage()); assertNull(observation.price());
        var raw = new ObjectMapper().readTree(observation.rawUsageJson());
        assertEquals(10, raw.get("usageEvidence").get("last").get("inputTokens").asInt());
        assertEquals("requested-only", raw.get("requestedModel").asText());
        verify(dispatches).hasOriginalUsageExecutor("0", "client", "task-owner", "task",
                "original-grant", "agent", "executor");
        verify(dispatches).findDispatch("0", "client", "dispatch");
        verifyNoMoreInteractions(dispatches);
    }
    @Test void nonTaskOwnerTransportKeepsNullTaskAndStableStartedReceipt() {
        dispatch.setOwnerJiacn("executor").setTaskGrantId(null).setTaskGrantVersion(null)
                .setPayloadJson(payload("null", "private"));
        when(turns.findTurn("0", "executor", "client", "turn")).thenReturn(turn);
        recorder.record(scope, "dispatch", new ChatUsageCostRecorder.Evidence(
                ChatUsageCostRecorder.Phase.STARTED, "requested-only", "null"));
        var argument = ArgumentCaptor.forClass(UsageCostService.Observation.class);
        verify(costs).record(argument.capture());
        assertNull(argument.getValue().binding().taskId());
        assertEquals("executor", argument.getValue().binding().ownerJiacn());
        assertEquals("started", argument.getValue().receiptId());
        assertEquals(0, argument.getValue().expectedRevision());
        verify(dispatches, never()).hasOriginalUsageExecutor(any(), any(), any(), any(), any(), any(), any());
    }
    @Test void crossOwnerCannotUseOwnerOnlyDispatchOrMissingOriginalGrant() {
        dispatch.setTaskGrantId(null).setTaskGrantVersion(null).setPayloadJson(payload("null", "public"));
        assertThrows(ChatDeliberationException.class, () -> recorder.record(scope, "dispatch", terminal()));
        dispatch.setPayloadJson(payload("\"task\"", "bounty"));
        assertThrows(ChatDeliberationException.class, () -> recorder.record(scope, "dispatch", terminal()));
        verifyNoInteractions(costs);
    }
    @Test void changedExecutorBindingCannotClaimHistoricalCosts() {
        when(dispatches.hasOriginalUsageExecutor(any(), any(), any(), any(), any(), any(), any())).thenReturn(false);
        assertThrows(ChatDeliberationException.class, () -> recorder.record(scope, "dispatch", terminal()));
        verifyNoInteractions(costs);
    }
    @Test void sourceRelationshipAndMalformedPersistedPayloadCannotAttributeCosts() {
        String valid = dispatch.getPayloadJson();
        for (String invalid : new String[]{"null", "[]", "broken", valid.replace("CHAT", "INSPECT"),
                valid.replace("\"dispatch\"", "\"other\""), valid.replace("\"turn\"", "\"other\""),
                valid.replace("\"agent\"", "\"other\""), valid.replace("\"conversation\"", "\"other\""),
                payload("7", "bounty"), payload("\" \"", "bounty")}) {
            dispatch.setPayloadJson(invalid);
            assertThrows(ChatDeliberationException.class, () -> recorder.record(scope, "dispatch", terminal()), invalid);
        }
        verifyNoInteractions(costs);
    }
    @Test void taskSourceRequiresPositiveSingletonMembershipBeforeCheckingDeviceOrigin() {
        var origins = mock(ChatTaskDispatchOriginStore.class);
        var authority = new ChatUsageDispatchAuthority(dispatches, turns, origins);
        var fence = new cn.jia.agent.service.AgentManagedSessionLookup.SessionFence("install", "host", "boot", 1);
        var device = new AuthenticatedRuntimeOperation.Device(scope, fence);
        dispatch.setEventId("event");
        turn.setDispatchId("dispatch").setContextDigest("context");
        var json = new ObjectMapper();
        for (Object ids : List.of("missing", "agent", List.of(), List.of("other"), List.of("agent", "other"), List.of("x", "y"))) {
            var data = json.readTree(payload("\"task\"", "bounty")).deepCopy();
            if (!"missing".equals(ids)) ((tools.jackson.databind.node.ObjectNode)data).putObject("factsManifest").set("participantAgentIds", json.valueToTree(ids));
            dispatch.setPayloadJson(data.toString());
            assertEquals(ChatUsageDispatchAuthority.TaskSource.UNMATCHED, authority.taskSource(device, "turn", "dispatch", "context"));
        }
        verifyNoInteractions(origins);
        when(origins.matches("event", "context", "install", "host", "boot", 1)).thenReturn(true);
        var valid = (tools.jackson.databind.node.ObjectNode)json.readTree(payload("\"task\"", "bounty"));
        valid.putObject("factsManifest").putArray("participantAgentIds").add("agent");
        dispatch.setPayloadJson(valid.toString());
        var query = (java.util.function.Supplier<ChatUsageDispatchAuthority.TaskSource>) () -> authority.taskSource(device, "turn", "dispatch", "context");
        assertEquals(ChatUsageDispatchAuthority.TaskSource.OBSERVED, query.get());
        turn.setState("PUBLISHED").setFinalMessageId(10L);
        assertEquals(ChatUsageDispatchAuthority.TaskSource.FINAL_BOUND, query.get());
        turn.setDispatchId("replacement");
        assertEquals(ChatUsageDispatchAuthority.TaskSource.UNMATCHED, query.get());
        turn.setDispatchId("dispatch").setContextDigest("replacement-context");
        assertEquals(ChatUsageDispatchAuthority.TaskSource.UNMATCHED, query.get());
        turn.setContextDigest("context");
        valid.put("conversationScopeType", "private");dispatch.setPayloadJson(valid.toString());
        assertEquals(ChatUsageDispatchAuthority.TaskSource.UNMATCHED, authority.taskSource(device, "turn", "dispatch", "context"));
        verifyNoInteractions(costs);
    }
    @Test void absentOrAmbiguousDispatchCannotCreateAnAttempt() {
        when(dispatches.findDispatch("0", "client", "dispatch")).thenReturn(List.of());
        assertThrows(ChatDeliberationException.class, () -> recorder.record(scope, "dispatch", terminal()));
        when(dispatches.findDispatch("0", "client", "dispatch")).thenReturn(List.of(dispatch, dispatch));
        assertThrows(ChatDeliberationException.class, () -> recorder.record(scope, "dispatch", terminal()));
        verifyNoInteractions(costs, turns);
    }
}
