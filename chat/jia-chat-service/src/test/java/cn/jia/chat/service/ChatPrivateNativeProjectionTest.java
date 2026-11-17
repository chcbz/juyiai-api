package cn.jia.chat.service;

import cn.jia.agent.service.*;
import cn.jia.chat.dao.*;
import cn.jia.chat.deliberation.*;
import cn.jia.chat.entity.*;
import cn.jia.core.security.FixedTenantAdmissionPolicy;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Projector behavior/lock-call order only. SQL execution and transaction rollback require MySQL. */
class ChatPrivateNativeProjectionTest {
    final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    final ChatDeliberationService requests=mock(ChatDeliberationService.class);
    final ChatDeliberationDao events=mock(ChatDeliberationDao.class);
    final ChatConversationDao conversations=mock(ChatConversationDao.class);
    final ChatMessageDao messages=mock(ChatMessageDao.class);
    final PersonalWorkspaceExecutionService files=mock(PersonalWorkspaceExecutionService.class);
    final SkillExecutionService skills=mock(SkillExecutionService.class);
    final AgentTaskMutationTransaction tasks=mock(AgentTaskMutationTransaction.class);
    final ChatBountyAssetProjector projector=new ChatBountyAssetProjector(jdbc,requests,events,conversations,messages,
            files,mock(ChatConversationEventBroker.class),new FixedTenantAdmissionPolicy("0","client"));
    final ChatBountyAssetProjector.Candidate candidate=new ChatBountyAssetProjector.Candidate("0","owner","client","request","step","exec");
    final PersonalWorkspaceExecutionService.OwnerScope owner=new PersonalWorkspaceExecutionService.OwnerScope("0","client","owner");
    final ChatDeliberationService.RequestView request=mock(ChatDeliberationService.RequestView.class);
    final ChatDeliberationService.StepView step=mock(ChatDeliberationService.StepView.class);
    final PersonalWorkspaceExecutionService.ExecutionView execution=mock(PersonalWorkspaceExecutionService.ExecutionView.class);
    final ChatConversationEntity conversation=new ChatConversationEntity();
    final ChatRequestEntity requestRow=new ChatRequestEntity();
    final ChatTurnEntity turn=new ChatTurnEntity();
    final Map<String,Object> root=new LinkedHashMap<>(),manifest=new LinkedHashMap<>();
    ChatMessageEntity reply;
    @BeforeEach void setup() {
        projector.setSkillProjectionServices(skills,tasks);
        root.put("task_id",null);root.put("business_scope","PRIVATE");root.put("conversation_id","42");
        root.put("target_agent_id","agent");root.put("task_grant_id",null);root.put("task_grant_version",null);root.put("assignment_revision",null);
        when(jdbc.queryForList(anyString(),anyString(),anyString(),anyString(),anyString())).thenAnswer(call->{
            String sql=call.getArgument(0);
            return sql.contains("FROM agent_personal_workspace_execution")?List.of(root):List.of();
        });
        when(jdbc.update(anyString(),any(Object[].class))).thenReturn(1);
        conversation.setId(42L).setJiacn("owner").setConversationType("juyiting").setConversationScopeType("private")
                .setConversationScopeKey("agent:agent").setTargetAgentId("agent").setTargetAgentIds("[ \"agent\" ]")
                .setLifecycleGeneration(1L);
        conversation.setTenantId("0");conversation.setClientId("client");
        when(conversations.lockScopedById("0","owner","client","42")).thenReturn(conversation);
        when(requests.getRequest("0","owner","client","request")).thenReturn(request);
        when(request.requestId()).thenReturn("request");when(request.requestRevision()).thenReturn("1");
        when(request.conversationId()).thenReturn("42");when(request.conversationGeneration()).thenReturn("1");
        when(request.state()).thenReturn("RUNNING");when(request.steps()).thenReturn(List.of(step));
        when(step.stepId()).thenReturn("step");when(step.kind()).thenReturn("EXECUTE");when(step.state()).thenReturn("RUNNING");
        when(step.executionId()).thenReturn("exec");when(step.executionState()).thenReturn("RUNNING");
        when(step.targetAgentId()).thenReturn("agent");when(step.stateVersion()).thenReturn("0");
        when(files.get(owner,"exec")).thenReturn(execution);when(execution.executionId()).thenReturn("exec");
        when(execution.conversationId()).thenReturn("42");when(execution.targetAgentId()).thenReturn("agent");
        when(execution.executionMode()).thenReturn("CONVERSATION");when(execution.state()).thenReturn("RESULT_COMMITTED");
        when(execution.runId()).thenReturn("run");when(files.listConversationOutputs(owner,"exec","run")).thenReturn(List.of());
        manifest.put("schemaVersion",2);manifest.put("data",Map.of("answer","这是处理结果。"));
        manifest.put("artifacts",List.of());manifest.put("inputReceipts",List.of());manifest.put("effectDependencies",List.of());
        manifest.put("resultDigest",ChatSkillSnapshotJson.sha(manifest));manifest.put("manifestId","manifest");
        when(skills.result(owner,"exec")).thenReturn(new SkillExecutionService.ResultView("exec","RESULT_COMMITTED","ARCHIVING",null,"a".repeat(64),0,manifest));
        requestRow.setTenantId("0").setOwnerJiacn("owner").setClientId("client").setRequestId("request")
                .setRequestRevision(1L).setConversationId("42").setConversationGeneration(1L).setAggregateState("RUNNING").setStateVersion(0L);
        when(events.findRequest("0","owner","client","request")).thenReturn(requestRow);
        when(events.updateRequestState(eq(requestRow),anyString(),anyLong())).thenReturn(1);
        turn.setTurnId("turn").setTenantId("0").setOwnerJiacn("owner").setClientId("client").setRequestId("request")
                .setRequestRevision(1L).setConversationId("42").setConversationGeneration(1L).setTargetAgentId("agent")
                .setRoute("EXECUTE").setState("RECEIVED").setStateVersion(0L);
        when(events.findTurnsByRequest("0","owner","client","request")).thenReturn(List.of(turn));
        when(events.lockTurn("0","owner","client","turn")).thenReturn(turn);
        when(events.persistFinal(eq(turn),anyString(),eq(90L),anyLong())).thenReturn(1);
        when(events.updateTurnState(eq(turn),anyString(),anyString(),anyLong())).thenReturn(1);
        when(messages.insertScoped(eq("0"),eq("client"),any())).thenAnswer(call->{reply=call.getArgument(2);reply.setId(90L);return 1;});
        when(events.insertEvent(any())).thenAnswer(call->{ChatConversationEventEntity event=call.getArgument(0);event.setEventSequence(1L);return 1;});
    }
    @Test void privateAnswerBecomesOnePlainMessageAndFinalTurnWithoutTaskOrArchive() {
        assertEquals(1,projector.project(candidate));assertEquals("这是处理结果。",reply.getContent());
        verifyNoInteractions(tasks);verify(files).listConversationOutputs(owner,"exec","run");
        verify(events).persistFinal(eq(turn),eq((String)manifest.get("resultDigest")),eq(90L),anyLong());
        verify(events).updateRequestState(eq(requestRow),eq("RESULT_COMMITTED"),anyLong());
        verify(skills).markProjected(owner,"exec",(String)manifest.get("resultDigest"));
        verify(events,never()).insertOutbox(any());verify(skills,never()).create(any(),any());
        var order=inOrder(jdbc,conversations,files);
        order.verify(jdbc).queryForList(argThat(sql->!sql.contains("FOR UPDATE")),eq("0"),eq("client"),eq("owner"),eq("exec"));
        order.verify(conversations).lockScopedById("0","owner","client","42");
        order.verify(jdbc).queryForList(contains("FOR UPDATE"),eq("0"),eq("client"),eq("owner"),eq("exec"));
        order.verify(files).get(owner,"exec");
    }
    @Test void taskAnswerProjectsBodyWithoutFilesOrTaskCompletion() {
        root.put("business_scope","TASK");root.put("task_id","task");
        conversation.setTaskId("task").setConversationScopeType("bounty").setConversationScopeKey("task:task");
        when(step.taskId()).thenReturn("task");when(execution.taskId()).thenReturn("task");
        when(tasks.executeWithLockedTaskRootInOwnerScope(eq("0"),eq("client"),eq("owner"),eq("task"),any()))
                .thenAnswer(call->((AgentTaskMutationTransaction.LockedTaskMutation<Integer>)call.getArgument(4)).apply(null));
        assertEquals(1,projector.project(candidate));assertEquals("这是处理结果。",reply.getContent());
        verify(messages,times(1)).insertScoped(eq("0"),eq("client"),any());
        verify(events,times(1)).insertEvent(any());
        verify(events,never()).persistFinal(any(),anyString(),anyLong(),anyLong());
        verify(skills).markProjected(owner,"exec",(String)manifest.get("resultDigest"));
        verify(files).listConversationOutputs(owner,"exec","run");
        verify(tasks).executeWithLockedTaskRootInOwnerScope(eq("0"),eq("client"),eq("owner"),eq("task"),any());
        verifyNoMoreInteractions(tasks);
    }
    @Test void cancellationClosesTurnAndRequestWithoutCreatingResponseFileOrProviderCall() {
        when(execution.state()).thenReturn("CANCELLED");
        assertEquals(1,projector.project(candidate));
        verify(events).updateTurnState(eq(turn),eq("CANCELLED"),eq("NATIVE_EXECUTION_CANCELLED"),anyLong());
        verify(events).updateRequestState(eq(requestRow),eq("CANCELLED"),anyLong());
        verifyNoInteractions(messages,skills,tasks);verify(files,never()).listConversationOutputs(any(),anyString(),anyString());
    }
    @Test void revokedInputsProjectCancelledTurnButRetainExecutionReasonOnLink() {
        when(execution.state()).thenReturn("INPUTS_REVOKED");assertEquals(1,projector.project(candidate));
        verify(events).updateTurnState(eq(turn),eq("CANCELLED"),anyString(),anyLong());
        verify(jdbc).update(contains("UPDATE chat_step_execution_link"),eq("INPUTS_REVOKED"),anyLong(),eq("step"),eq("0"),eq("owner"),eq("client"),eq("exec"));
    }
    @Test void foreignPartitionAndChangedGenerationCannotProject() {
        assertEquals(0,projector.project(new ChatBountyAssetProjector.Candidate("foreign","owner","client","request","step","exec")));
        verifyNoInteractions(jdbc);
        conversation.setLifecycleGeneration(2L);assertEquals(0,projector.project(candidate));
        verifyNoInteractions(messages,skills);verify(events,never()).insertEvent(any());
    }
    @Test void privateScopeCannotBorrowSyntheticTaskGrant() {
        root.put("task_grant_id","grant");assertEquals(0,projector.project(candidate));
        verifyNoInteractions(conversations,files,messages,skills,tasks);
    }
    @Test void turnCasFailureDoesNotFinishRequestOrMarkExecutionProjected() {
        when(events.persistFinal(eq(turn),anyString(),eq(90L),anyLong())).thenReturn(0);
        assertThrows(IllegalStateException.class,()->projector.project(candidate));
        verify(events,never()).updateRequestState(any(),anyString(),anyLong());
        verify(skills,never()).markProjected(any(),anyString(),anyString());
        // Full rollback of the already-inserted message is a separate real transaction test.
    }
}
