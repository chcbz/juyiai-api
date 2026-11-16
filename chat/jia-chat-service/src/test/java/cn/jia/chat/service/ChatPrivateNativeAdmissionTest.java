package cn.jia.chat.service;

import cn.jia.agent.service.AgentService;
import cn.jia.agent.service.SkillExecutionService;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.deliberation.*;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.entity.ChatMessageEntity;
import cn.jia.chat.handler.TypedDeliberationSessionRegistry;
import cn.jia.chat.handler.dto.ChatMessageDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real transaction boundary with mock repositories. Not MySQL/production concurrency evidence. */
class ChatPrivateNativeAdmissionTest {
    final ChatDeliberationDao dao=mock(ChatDeliberationDao.class);
    final ChatConversationDao conversations=mock(ChatConversationDao.class);
    final ChatMessageDao messages=mock(ChatMessageDao.class);
    final AgentService agents=mock(AgentService.class);
    final SkillExecutionService executions=mock(SkillExecutionService.class);
    final ChatSkillSourceAccessService sources=mock(ChatSkillSourceAccessService.class);
    final TypedDeliberationSessionRegistry sessions=mock(TypedDeliberationSessionRegistry.class);
    final ChatInteractionStepStore steps=mock(ChatInteractionStepStore.class);
    final ChatDeliberationService service=new ChatDeliberationService(dao,conversations,messages,agents);
    final ServerResolvedSender sender=new ServerResolvedSender("user","name","owner","client",DisplayNameSource.FALLBACK);
    JdbcTemplate jdbc;
    ChatConversationEntity conversation;
    ChatRequestEntity request;
    ChatContextSnapshotEntity snapshot;
    ChatTurnEntity turn;
    ChatInteractionStepStore.Step step;
    ChatInteractionStepStore.ExecutionLink link;
    SkillExecutionService.Create command;

    @BeforeEach void setup() {
        var source=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");
        jdbc=new JdbcTemplate(source);jdbc.execute("CREATE TABLE writes (kind VARCHAR(32))");
        service.setInteractionSteps(steps);
        service.setPrivateNativeAdmission(new DataSourceTransactionManager(source),executions,sources,sessions);
        when(sessions.requireSingleReady(any(),eq("agent"))).thenReturn(new TypedDeliberationSessionRegistry.Ready(
                "session","agent",List.of(),Map.of("nativeExecution",Map.of("executionProtocolVersion",6,
                "toolPolicyRef",SkillExecutionService.NATIVE_LOCAL_FILES_POLICY,"outputMimeTypes",List.of("text/plain")))));
        when(dao.findRequest("tenant","owner","client","request")).thenAnswer(call->request);
        when(conversations.insert(any(ChatConversationEntity.class))).thenAnswer(call->{
            conversation=call.getArgument(0);conversation.setId(42L);write("conversation");return 1;});
        when(conversations.lockScopedById("tenant","owner","client","42")).thenAnswer(call->conversation);
        when(messages.insertScoped(eq("tenant"),eq("client"),any())).thenAnswer(call->{
            ChatMessageEntity message=call.getArgument(2);message.setId(90L);write("message");return 1;});
        when(messages.findOwnedByConversationIdWithLimit("tenant","owner","client","42",200)).thenReturn(List.of());
        when(sources.resolvePrivateMaterials(any(),eq("42"),eq(1L),anyList())).thenReturn(List.of());
        when(dao.insertRequest(any())).thenAnswer(call->{request=call.getArgument(0);request.setId(1L);write("request");return 1;});
        when(dao.insertSnapshot(any())).thenAnswer(call->{snapshot=call.getArgument(0);write("snapshot");return 1;});
        when(dao.insertTurn(any())).thenAnswer(call->{turn=call.getArgument(0);write("turn");return 1;});
        when(executions.create(any(),any())).thenAnswer(call->{command=call.getArgument(1);write("execution");
            return new SkillExecutionService.Creation("execution",null,"run","42","agent","QUEUED",ChatSkillSnapshotJson.sha(command.snapshot()));});
        when(steps.insertStep(any())).thenAnswer(call->{step=call.getArgument(0);write("step");return 1;});
        when(steps.insertLink(any())).thenAnswer(call->{link=call.getArgument(0);write("link");return 1;});
        when(steps.findStep("tenant","owner","client","request",1,1)).thenAnswer(call->step);
        when(steps.findLink(eq("tenant"),eq("owner"),eq("client"),anyString())).thenAnswer(call->link);
        when(dao.findTurnsByRequest("tenant","owner","client","request")).thenAnswer(call->List.of(turn));
        when(dao.findSnapshot(eq("tenant"),eq("owner"),eq("client"),anyString())).thenAnswer(call->snapshot);
    }
    void write(String kind) {
        assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
        jdbc.update("INSERT INTO writes VALUES (?)",kind);
    }
    @AfterEach void cleanup() { jdbc.execute("SHUTDOWN"); }
    ChatMessageDTO input() {
        var input=new ChatMessageDTO();input.setConversationType("juyiting");input.setConversationScopeType("private");
        input.setTargetAgentId("agent");input.setRequestId("request");input.setContent("处理这个文件");input.setMaterialRefs(List.of());return input;
    }
    @Test void firstSendCreatesOnlyOneNativeExecutionAndNoTaskOrChatOutbox() {
        var input=input();var accepted=service.admitPrivate("tenant",sender,input);
        assertNull(input.getConversationId());assertEquals("42",accepted.admission().conversationId());
        assertEquals(InteractionRoute.EXECUTE,accepted.admission().route());assertFalse(accepted.admission().replay());
        assertInstanceOf(SkillExecutionService.PrivateScope.class,command.scope());
        assertNull(conversation.getTaskId());assertNull(step.taskId());assertNull(step.grantId());
        assertNull(step.assignmentRevision());assertNull(step.grantVersion());assertEquals("EXECUTE",turn.getRoute());
        assertEquals(8,jdbc.queryForObject("SELECT COUNT(*) FROM writes",Integer.class));
        assertTrue(snapshot.getFactsManifestJson().contains(SkillExecutionService.NATIVE_LOCAL_FILES_POLICY));
        assertFalse(snapshot.getFactsManifestJson().contains("read-only-constrained"));
        verify(dao,never()).insertOutbox(any());verify(executions,times(1)).create(any(),any());
    }
    @Test void nullConversationReplayReturnsOriginalWithoutReadinessOrMaterialRediscovery() {
        var input=input();service.admitPrivate("tenant",sender,input);
        conversation.setTargetAgentIds("[ \"agent\" ]"); // JSON representation is not identity.
        clearInvocations(sessions,sources,executions);
        var replay=service.admitPrivate("tenant",sender,input);
        assertTrue(replay.admission().replay());assertEquals("42",replay.admission().conversationId());
        verifyNoInteractions(sessions,sources,executions);
        verify(conversations,times(1)).insert(any(ChatConversationEntity.class));
    }
    @Test void changedOriginalIntentConflictsRatherThanCreatingAnotherExecution() {
        var original=input();service.admitPrivate("tenant",sender,original);
        for (String field:List.of("content","target","conversation","material")) {
            var changed=input();
            switch(field) {
                case "content" -> changed.setContent("different");
                case "target" -> changed.setTargetAgentId("another-agent");
                case "conversation" -> changed.setConversationId("42");
                case "material" -> changed.setMaterialRefs(List.of(Map.of("kind","WORKSPACE_VERSION","fileId","file","version","1")));
            }
            var error=assertThrows(ChatDeliberationException.class,()->service.admitPrivate("tenant",sender,changed));
            assertEquals(ChatDeliberationException.Reason.CONFLICT,error.reason());
        }
        verify(executions,times(1)).create(any(),any());
    }
    @Test void linkFailureRollsBackAllEarlierWritesIncludingFirstConversation() {
        when(steps.insertLink(any())).thenThrow(new IllegalStateException("write failed"));
        assertThrows(IllegalStateException.class,()->service.admitPrivate("tenant",sender,input()));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM writes",Integer.class));
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }
    @Test void duplicateKeyLoserRollsBackBeforeReadingWinnerInFreshTransaction() {
        var input=input();service.admitPrivate("tenant",sender,input);
        var winner=request;
        // Model a committed competitor that is first visible only after the unique-key conflict.
        when(dao.findRequest("tenant","owner","client","request"))
                .thenReturn(null,null).thenAnswer(call->{
                    assertEquals(8,jdbc.queryForObject("SELECT COUNT(*) FROM writes",Integer.class));
                    return winner;
                });
        when(dao.insertRequest(any())).thenThrow(new org.springframework.dao.DuplicateKeyException("request key"));
        clearInvocations(executions);
        var replay=service.admitPrivate("tenant",sender,input);
        assertTrue(replay.admission().replay());assertEquals("42",replay.admission().conversationId());
        verifyNoInteractions(executions);
        assertEquals(8,jdbc.queryForObject("SELECT COUNT(*) FROM writes",Integer.class));
    }
    @Test void genericChatAdmissionCannotCreatePrivateDispatchOutbox() {
        var scope=new JuyitingConversationScope("private","agent:agent",null,"agent",List.of("agent"),List.of("agent"));
        var failure=assertThrows(ChatDeliberationException.class,()->service.admit("tenant",sender,"42",1,
                scope,InteractionRoute.CHAT,input(),null));
        assertEquals(ChatDeliberationException.Reason.INVALID_REQUEST,failure.reason());
        verify(dao,never()).insertOutbox(any());verifyNoInteractions(executions);
    }
    @Test void replayStillRequiresLiveConversation() {
        var input=input();service.admitPrivate("tenant",sender,input());
        conversation.setLifecycleGeneration(2L);
        assertThrows(ChatDeliberationException.class,()->service.admitPrivate("tenant",sender,input));
        verify(executions,times(1)).create(any(),any());
    }
    @Test void privateMaterialContractRejectsMissingDuplicatesAndNoUserIntentBeforeReadiness() {
        var ref=Map.<String,Object>of("kind","WORKSPACE_VERSION","fileId","file","version","1");
        var missing=input();missing.setMaterialRefs(null);
        var duplicate=input();duplicate.setMaterialRefs(List.of(ref,ref));
        var tooMany=input();tooMany.setMaterialRefs(java.util.Collections.nCopies(33,ref));
        var noIntent=input();noIntent.setMaterialRefs(List.of(ref));noIntent.setContent("");
        var noConversation=input();noConversation.setMaterialRefs(List.of(Map.of("kind","CURRENT_CONVERSATION_ASSET","assetId","asset","revision","1")));
        for(var value:List.of(missing,duplicate,tooMany,noIntent,noConversation)) {
            var error=assertThrows(ChatDeliberationException.class,()->service.admitPrivate("tenant",sender,value));
            assertEquals(ChatDeliberationException.Reason.INVALID_REQUEST,error.reason());
        }
        verifyNoInteractions(sessions,sources,executions);
    }
    @Test void malformedMaterialKindIsContractFailureNotClassCastException() {
        var input=input();input.setMaterialRefs(List.of(Map.of("kind",123,"fileId","file","version","1")));
        var failure=assertThrows(ChatDeliberationException.class,()->service.admitPrivate("tenant",sender,input));
        assertEquals(ChatDeliberationException.Reason.INVALID_REQUEST,failure.reason());
        verifyNoInteractions(sessions,sources,executions);
    }
    @Test void cancelUsesNativeExecutionBeforeTurnLockAndCreatesNoChatOutbox() {
        service.admitPrivate("tenant",sender,input());
        when(dao.findTurn(eq("tenant"),eq("owner"),eq("client"),anyString())).thenAnswer(call->turn);
        when(dao.lockTurn(eq("tenant"),eq("owner"),eq("client"),anyString())).thenAnswer(call->turn);
        when(conversations.findScopedById("tenant","owner","client","42")).thenReturn(conversation);
        when(executions.cancel(any(),eq("execution"))).thenReturn("CANCELLED");
        clearInvocations(executions,dao,conversations);
        service.cancelTurn("tenant","owner","client",turn.getTurnId(),0L,"USER_CANCELLED");
        var order=inOrder(conversations,executions,dao);
        order.verify(conversations).lockScopedById("tenant","owner","client","42");
        order.verify(executions).cancel(any(),eq("execution"));
        order.verify(dao).lockTurn("tenant","owner","client",turn.getTurnId());
        verify(dao,never()).insertOutbox(any());verify(dao,never()).updateTurnState(any(),anyString(),anyString(),anyLong());
        // Native terminal state is projected separately; no pretend provider rollback or CHAT completion.
        assertEquals("RUNNING",request.getAggregateState());
    }
    @Test void longCurrentMessageIsCarriedInFullWithoutChatDispatchReference() {
        var input=input();String content="文".repeat(10000);input.setContent(content);
        service.admitPrivate("tenant",sender,input);
        var parameters=ChatSkillSourceAccessService.object(command.snapshot().get("parameters"));
        var context=ChatSkillSourceAccessService.object(parameters.get("authorizedContext"));
        var current=ChatSkillSourceAccessService.object(context.get("currentUserMessage"));
        assertEquals(content,current.get("content"));assertFalse(current.containsKey("contentSource"));
        assertTrue(((String)command.snapshot().get("instruction")).length()<8000);
    }
}
