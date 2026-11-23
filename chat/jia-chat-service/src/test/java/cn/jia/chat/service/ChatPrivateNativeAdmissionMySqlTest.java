package cn.jia.chat.service;

import cn.jia.agent.config.PersonalWorkspaceExecutionProperties;
import cn.jia.agent.config.ControlledImageFollowupV3SchemaInitializer;
import cn.jia.agent.config.SkillExecutionSchemaInitializer;
import cn.jia.agent.dao.*;
import cn.jia.agent.dao.impl.*;
import cn.jia.agent.mapper.*;
import cn.jia.agent.service.*;
import cn.jia.agent.service.impl.PersonalWorkspaceExecutionServiceImpl;
import cn.jia.agent.service.impl.PersonalWorkspaceWriteService;
import cn.jia.agent.skill.SkillManagementMySqlFixture;
import cn.jia.chat.dao.*;
import cn.jia.chat.dao.impl.*;
import cn.jia.chat.deliberation.*;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.handler.TypedDeliberationSessionRegistry;
import cn.jia.chat.handler.dto.ChatMessageDTO;
import cn.jia.chat.mapper.*;
import cn.jia.chat.service.impl.WorkspaceConversationAccessServiceImpl;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real production request/conversation/execution mappers and MySQL transactions.
 * Only external readiness, Agent ownership and zero-file discovery are seeded; no model/provider call. */
class ChatPrivateNativeAdmissionMySqlTest extends SkillManagementMySqlFixture {
    String database;
    JdbcTemplate jdbc;
    ChatDeliberationDao dao;
    ChatConversationDaoImpl conversations;
    ChatInteractionStepStore steps;
    ChatDeliberationService service;
    PersonalWorkspaceExecutionServiceImpl executions;
    final AgentService agents=mock(AgentService.class);
    final ChatSkillSourceAccessService sources=mock(ChatSkillSourceAccessService.class);
    final TypedDeliberationSessionRegistry sessions=mock(TypedDeliberationSessionRegistry.class);
    final ServerResolvedSender sender=new ServerResolvedSender("user","name","owner","client",DisplayNameSource.FALLBACK);
    static final List<String> ATOMIC_TABLES=List.of("chat_conversation","chat_message","chat_request",
            "chat_context_snapshot","chat_turn","chat_interaction_step","chat_step_execution_link",
            "agent_personal_workspace_execution","agent_skill_execution");

    @BeforeEach void setup() throws Exception {
        database="cyf_private_native_"+UUID.randomUUID().toString().replace("-","");
        admin.execute("CREATE DATABASE `"+database+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
        var source=source(database);jdbc=new JdbcTemplate(source);
        var schema=new ResourceDatabasePopulator();
        for(String name:List.of("contracts/revision-chat-tables.sql","db/chat-deliberation-schema.sql","db/chat-native-execution-v6.sql",
                "db/agent-personal-workspace-v1_11-executions.sql","db/agent-personal-workspace-v1_13-task-execution.sql",
                "db/agent-personal-workspace-v1_13-task-publication.sql","db/agent-personal-workspace-v1_14-conversation-execution.sql",
                "db/agent-personal-workspace-v1_14-conversation-output.sql","db/agent-personal-workspace-v1_15-conversation-lease.sql",
                "db/agent-personal-workspace-v1_16-conversation-provider-start.sql","db/agent-skill-execution-v1.sql",
                "db/agent-personal-workspace-native-v5.sql","db/agent-personal-workspace-native-v6.sql","db/agent-personal-workspace-metered-capture-v2.sql"))
            schema.addScript(new ClassPathResource(name));
        schema.execute(source);
        // The deployed v6 baseline includes the earlier controlled/skill catalog migrations.
        new ControlledImageFollowupV3SchemaInitializer(jdbc).afterPropertiesSet();
        new ResourceDatabasePopulator(new ClassPathResource("db/economy-v0-skill-application.sql"), new ClassPathResource("db/agent-personal-workspace-skill-v1.sql")).execute(source);
        var config=new MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);
        for(Class<?> mapper:List.of(ChatDeliberationMapper.class,ChatConversationMapper.class,ChatMessageMapper.class,
                PersonalWorkspaceExecutionMapper.class,PersonalWorkspaceExecutionInputMapper.class,
                PersonalWorkspaceExecutionOutputMapper.class,SkillExecutionMapper.class))config.addMapper(mapper);
        var global=new GlobalConfig();global.setIdentifierGenerator(new DefaultIdentifierGenerator());
        var factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(source);factory.setConfiguration(config);factory.setGlobalConfig(global);
        var sql=new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
        dao=spy(new ChatDeliberationDaoImpl(sql.getMapper(ChatDeliberationMapper.class)));
        conversations=new ChatConversationDaoImpl();ReflectionTestUtils.setField(conversations,"baseMapper",sql.getMapper(ChatConversationMapper.class));
        var messages=new ChatMessageDaoImpl();ReflectionTestUtils.setField(messages,"baseMapper",sql.getMapper(ChatMessageMapper.class));
        var rows=new PersonalWorkspaceExecutionDaoImpl(sql.getMapper(PersonalWorkspaceExecutionMapper.class),
                sql.getMapper(PersonalWorkspaceExecutionInputMapper.class),sql.getMapper(PersonalWorkspaceExecutionOutputMapper.class));
        executions=new PersonalWorkspaceExecutionServiceImpl(rows,mock(PersonalWorkspaceDao.class),mock(PersonalWorkspaceTaskLinkDao.class),
                mock(AgentRuntimeDao.class),mock(PersonalWorkspaceStorage.class),mock(PersonalWorkspaceWriteService.class),
                new PersonalWorkspaceExecutionProperties(List.of("text/plain")));
        executions.setNativeAgentAuthority(agents);
        executions.setTaskExecutionDependencies(new WorkspaceConversationAccessServiceImpl(conversations,mock(AgentTaskThreadDao.class)),
                mock(AgentTaskWorkItemDao.class),mock(AgentWorkItemLeaseService.class));
        executions.setSkillExecutionDependencies(new SkillExecutionDaoImpl(sql.getMapper(SkillExecutionMapper.class)),
                mock(InstalledSkillDescriptorLookup.class),mock(SkillCapabilityLookup.class),mock(TaskSkillPolicyService.class));
        ReflectionTestUtils.setField(executions,"conversationExecutionEnabled",true);
        steps=spy(new ChatInteractionStepStore(jdbc));
        service=new ChatDeliberationService(dao,conversations,messages,agents);service.setInteractionSteps(steps);
        service.setPrivateNativeAdmission(new DataSourceTransactionManager(source),executions,sources,sessions);
        when(sessions.requireSingleReady(any(),eq("agent"))).thenReturn(new TypedDeliberationSessionRegistry.Ready("session","agent",List.of(),
                Map.of("nativeExecution",Map.of("executionProtocolVersion",6,"toolPolicyRef",SkillExecutionService.NATIVE_LOCAL_FILES_POLICY,"outputMimeTypes",List.of("text/plain")))));
        when(sources.resolvePrivateMaterials(any(),anyString(),eq(1L),eq(List.of()))).thenReturn(List.of());
    }
    @AfterEach void cleanup() {
        if(database!=null){assertTrue(database.matches("cyf_private_native_[0-9a-f]{32}"));admin.execute("DROP DATABASE `"+database+"`");}
    }
    ChatMessageDTO input() {
        var value=new ChatMessageDTO();value.setConversationType("juyiting");value.setConversationScopeType("private");
        value.setTargetAgentId("agent");value.setRequestId("private-request");value.setContent("请回复本次请求");value.setMaterialRefs(List.of());return value;
    }
    int count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}
    void oneExecution() {
        for(String table:ATOMIC_TABLES)assertEquals(1,count(table),table);
        assertEquals(0,count("chat_dispatch_outbox"));assertEquals(0,count("agent_task_thread"));
        var row=jdbc.queryForMap("SELECT task_id,task_grant_id,task_grant_version,assignment_revision,business_scope,execution_protocol_version FROM agent_personal_workspace_execution");
        for(String key:List.of("task_id","task_grant_id","task_grant_version","assignment_revision"))assertNull(row.get(key),key);
        assertEquals("PRIVATE",row.get("business_scope"));assertEquals(6,((Number)row.get("execution_protocol_version")).intValue());
        assertEquals("EXECUTE",jdbc.queryForObject("SELECT route FROM chat_turn",String.class));
    }
    @Test void firstSendAndLostAcknowledgementPreserveOriginalNullIntentAndOneExecution() {
        var first=service.admitPrivate("0",sender,input());oneExecution();
        clearInvocations(sessions,sources,agents);
        var replay=service.admitPrivate("0",sender,input());assertTrue(replay.admission().replay());
        assertEquals(first.admission().conversationId(),replay.admission().conversationId());oneExecution();verifyNoInteractions(sessions,sources,agents);
        assertNotNull(dao.findRequest("0","owner","client","private-request").getClientIntentSha256());
    }
    @Test void concurrentFirstSendLosingUniqueKeyRollsBackItsConversationAndAllWrites() throws Exception {
        var barrier=new CyclicBarrier(2);
        doAnswer(call->{var result=call.callRealMethod();
            if(!TransactionSynchronizationManager.isActualTransactionActive())barrier.await(20,TimeUnit.SECONDS);
            return result;
        }).when(dao).findRequest("0","owner","client","private-request");
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->service.admitPrivate("0",sender,input()));var b=pool.submit(()->service.admitPrivate("0",sender,input()));
            var left=a.get(30,TimeUnit.SECONDS);var right=b.get(30,TimeUnit.SECONDS);
            assertEquals(left.admission().conversationId(),right.admission().conversationId());
            assertNotEquals(left.admission().replay(),right.admission().replay());oneExecution();
        }
    }
    @Test void lateLinkFailureRollsBackRealRequestUserMessageExecutionAndSkillSnapshot() {
        doThrow(new IllegalStateException("injected-link-failure")).when(steps).insertLink(any());
        assertThrows(IllegalStateException.class,()->service.admitPrivate("0",sender,input()));
        for(String table:ATOMIC_TABLES)assertEquals(0,count(table),table);
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }
    @Test void maintenanceDetectorAcceptsNativeSixPairsAndRejectsMissingAttachments() {
        service.admitPrivate("0",sender,input());
        var detector=new SkillExecutionSchemaInitializer(jdbc);
        assertDoesNotThrow(detector::afterPropertiesSet);
        jdbc.update("DELETE FROM agent_skill_execution");
        assertThrows(IllegalStateException.class,detector::afterPropertiesSet);
    }
    @Test void changedBodyOrGenerationCannotReuseCommittedRequest() {
        var first=service.admitPrivate("0",sender,input());
        var changed=input();changed.setContent("不同请求");
        assertThrows(ChatDeliberationException.class,()->service.admitPrivate("0",sender,changed));
        jdbc.update("UPDATE chat_conversation SET lifecycle_generation=2 WHERE id=?",Long.parseLong(first.admission().conversationId()));
        assertThrows(ChatDeliberationException.class,()->service.admitPrivate("0",sender,input()));oneExecution();
    }
}
