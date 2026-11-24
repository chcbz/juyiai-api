package cn.jia.agent.service.impl;

import cn.jia.economy.pricing.TaskEstimateFeatures;
import cn.jia.economy.pricing.TaskQuoteCalculator;
import cn.jia.economy.pricing.MeasuredTaskRepricing;
import cn.jia.economy.pricing.MeasuredNeighborEstimate;
import cn.jia.economy.task.TaskQuoteBatches;
import cn.jia.agent.common.TaskEventType;
import cn.jia.agent.api.AgentTaskDeliberationOperationController;
import cn.jia.chat.api.AgentTaskDeliveryReviewController;
import cn.jia.chat.service.*;
import cn.jia.agent.api.AgentTaskCancellationController;
import cn.jia.core.config.*;
import cn.jia.core.filter.RequestIdFilter;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.filter.DelegatingFilterProxy;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import jakarta.servlet.DispatcherType;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import cn.jia.agent.dao.*;
import cn.jia.agent.dao.impl.*;
import cn.jia.agent.entity.*;
import cn.jia.agent.mapper.*;
import cn.jia.agent.service.*;
import cn.jia.economy.config.*;
import cn.jia.economy.mapper.EconomyLedgerMapper;
import cn.jia.economy.mapper.EconomyTaskBillingMapper;
import cn.jia.economy.pricing.*;
import cn.jia.economy.service.EconomyScope;
import cn.jia.economy.config.TaskPricingTestFixture;
import cn.jia.economy.service.impl.EconomyPostingServiceImpl;
import cn.jia.economy.service.impl.TaskBillingServiceImpl;
import cn.jia.economy.task.TaskBillingService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Production Agent/ledger SQL and Spring transactions on the same isolated MySQL database.
 * Only identity/material observations, the external wakeup receiver and explicit fault injection
 * are test doubles. No task, event, grant, outbox or money persistence is implemented by this test.
 */
class AgentTaskBillingMySqlTransactionTest extends cn.jia.agent.skill.SkillManagementMySqlFixture {
    private static final String TENANT = "0", CLIENT = "billing-client", OWNER = "billing-owner";
    private static final String PAYER = "billing-user", TASK = "billing-task", AGENT = "agent-1";
    private static final AgentTaskExecutionGrantService.Scope SCOPE =
            new AgentTaskExecutionGrantService.Scope(TENANT, CLIENT, OWNER);
    private static final TaskBillingService.Task BILLING_TASK =
            new TaskBillingService.Task(new EconomyScope(TENANT, CLIENT), OWNER, TASK);
    private static final Path DDL = Path.of("../jia-agent-mapper/src/main/resources/db");

    private JdbcTemplate jdbc, independent;
    private String database;
    private DataSourceTransactionManager manager;
    private AgentTaskMutationTransaction roots;
    private TaskBillingService billing;
    private cn.jia.economy.task.TaskQuoteBatches batches;
    private AgentTaskExecutionGrantServiceImpl assignment;
    private AgentTaskFormalDeliveryDecisionService decisions;
    private AgentTaskCancellationService cancellation;
    private cn.jia.agent.hosting.HostingRentTaskSettlement rentSettlement;
    private AgentTaskBountyBootstrapOutboxDao outbox;
    private AgentTaskBountyBootstrapOutboxService bootstrap;
    private AgentTaskEventAfterCommitPublisher publisher;
    private final AgentTaskEventBroker broker = mock(AgentTaskEventBroker.class);
    private String failEvent;
    private boolean failBootstrap;
    private boolean failOccupation;
    private String quoteId;
    private cn.jia.economy.api.EconomyWalletService walletReads;
    private EconomyPreviewGate walletGate;
    private AgentTaskDeliberationOperationReadService operationReads;
    private AgentTaskFormalDeliveryReadService deliveryReads;
    private ChatTaskDeliveryReviewService reviews;
    private ChatTaskDeliveryReviewReadService reviewSources;
    @org.junit.jupiter.api.io.TempDir Path httpDirectory;
    private Tomcat httpServer;
    private AnnotationConfigWebApplicationContext httpContext;
    private String origin;
    private byte[] signingKey;

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({ApiEnvelopeJsonConfig.class, ApiEnvelopeAdvice.class, ApiRequestEnvelopeAdvice.class, ExceptionHandlerAdvice.class})
    public static class HttpApp {
        @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
            return http.csrf(c -> c.disable()).authorizeHttpRequests(a -> a.anyRequest().authenticated())
                    .oauth2ResourceServer(o -> o.jwt(j -> {})).build();
        }
    }

    private cn.jia.agent.service.usage.TaskMembershipDelivery membershipDelivery;
    private cn.jia.agent.service.usage.TaskMembershipSweep membershipSweep;
    private PersonalWorkspaceExecutionDaoImpl executionRows;
    private cn.jia.economy.usage.ConfirmedSampleEvidence sampleEvidence;
    private cn.jia.economy.task.TaskCalibrationCatalog calibrationCatalog;
    private cn.jia.economy.task.TaskHistoryCatalog historyCatalog;
    private cn.jia.agent.service.usage.ConfirmedSampleRecovery recovery;
    private cn.jia.economy.usage.ConfirmedSampleProjection sampleProjection;

    @BeforeEach
    void setUp() throws Exception {
        // Owned ephemeral MySQL only; never connect to a configured/production database.
        database = "cyf_agentbill_" + Long.toUnsignedString(System.nanoTime(), 36);
        admin.execute("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
        var source = source(database);
        jdbc = new JdbcTemplate(source);
        independent = new JdbcTemplate(source(database));
        new EconomySchemaInitializer(jdbc).afterPropertiesSet();
        new ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("db/agent-task-execution-members-v1.sql")).execute(source);
        // Read unchanged production definitions; no parallel hand-written table schema.
        String schema = Files.readString(DDL.resolve("schema.sql"));
        for (String table : List.of("agent_task_meta", "agent_task_member", "agent_task_work_item", "agent_task_event", "agent_task_artifact", "agent_persona_binding")) {
            int start = schema.indexOf("CREATE TABLE IF NOT EXISTS " + table + " (");
            assertTrue(start >= 0, table);
            jdbc.execute(schema.substring(start, schema.indexOf(";\n", start)));
        }
        for (String file : List.of("agent-task-execution-grant-v1.sql",
                "agent-task-bounty-bootstrap-outbox-v1.sql", "agent-task-formal-delivery-r2.sql")) {
            new ResourceDatabasePopulator(new FileSystemResource(DDL.resolve(file))).execute(source);
        }
        new ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("db/economy-v0-hosting-rent.sql")).execute(source);
        new ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("db/task-scheduled-work-v1.sql"),new org.springframework.core.io.ClassPathResource("db/economy-usage-capture.sql")).execute(source);
        new ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("db/economy-task-quote-batch-v1.sql")).execute(source);
        manager = new DataSourceTransactionManager(source);
        var config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
        for (Class<?> mapper : List.of(AgentTaskMetaMapper.class, AgentTaskMemberMapper.class,
                AgentTaskWorkItemMapper.class, AgentTaskEventMapper.class, AgentTaskExecutionGrantMapper.class,
                AgentTaskBountyBootstrapOutboxMapper.class, AgentTaskFormalDeliveryMapper.class, AgentTaskCancellationMapper.class,
                PersonalWorkspaceExecutionMapper.class, PersonalWorkspaceExecutionInputMapper.class, PersonalWorkspaceExecutionOutputMapper.class, SkillExecutionMapper.class, AgentTaskProviderCostConsentMapper.class,
                EconomyLedgerMapper.class, EconomyTaskBillingMapper.class, ControlledImageBridgeOperationMapper.class,
                cn.jia.economy.mapper.EconomyHostingRentMapper.class, cn.jia.economy.mapper.EconomyHostingRenewalMapper.class,
                HostingRentFinishingMapper.class, AgentPersonaBindingMapper.class,cn.jia.task.mapper.ScheduledWorkMapper.class,cn.jia.economy.mapper.TaskMembershipReceiptMapper.class)) config.addMapper(mapper);
        var global = new GlobalConfig(); global.setIdentifierGenerator(new DefaultIdentifierGenerator());
        var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(source);
        factory.setConfiguration(config); factory.setGlobalConfig(global);
        for(var m:List.of(AgentRuntimeMapper.class,AgentIdentityRegistryMapper.class,AgentIdentityAliasMapper.class,AgentExternalServicePolicyMapper.class))config.addMapper(m);
        config.addMapper(cn.jia.economy.mapper.ConfirmedSampleMapper.class);
        config.addMapper(cn.jia.economy.mapper.TaskCalibrationMapper.class);
        var sql = new SqlSessionTemplate(factory.getObject()); recommendationSql=sql;
        var meta = new AgentTaskMetaDaoImpl();
        ReflectionTestUtils.setField(meta, "baseMapper", sql.getMapper(AgentTaskMetaMapper.class));
        var members = new AgentTaskMemberDaoImpl(sql.getMapper(AgentTaskMemberMapper.class));
        var work = new AgentTaskWorkItemDaoImpl(sql.getMapper(AgentTaskWorkItemMapper.class));
        var eventDao = new AgentTaskEventDaoImpl();
        ReflectionTestUtils.setField(eventDao, "baseMapper", sql.getMapper(AgentTaskEventMapper.class));
        roots = new AgentTaskMutationTransactionImpl(meta, manager);
        publisher = new AgentTaskEventAfterCommitPublisher(broker, manager);
        var actualEvents = new AgentTaskEventWriterImpl(eventDao, manager, publisher);
        AgentTaskEventWriter events = command -> {
            var written = actualEvents.append(command);
            if (command.getEventType().equals(failEvent)) throw new InjectedFailure();
            return written;
        };
        var ledger = sql.getMapper(EconomyLedgerMapper.class);
        walletGate = new EconomyPreviewGate(new EconomyPreviewProperties(true,
                List.of(new EconomyPreviewProperties.AllowedScope(TENANT, CLIENT))));
        // Issuance is outside this fixture. Wallet/ledger reads use the production SQL;
        // disabled campaigns cannot call this deliberately unavailable treasury boundary.
        walletReads = new cn.jia.economy.api.EconomyWalletService(ledger,
                command -> { throw new AssertionError("browser fixture must not issue funds"); },
                new org.springframework.core.env.StandardEnvironment(), walletGate);
        var pricing = TaskPricingTestFixture.install(jdbc, new cn.jia.economy.service.EconomyScope(TENANT, CLIENT),
                List.of(new ModelTokenPrice("fixture", "fixture-model", "standard", "1", "synthetic fixture",
                        Map.of(ModelTokenPrice.Category.INPUT, new BigDecimal("0.50")), null, Map.of())));
        batches=proxy(new cn.jia.economy.service.impl.TaskQuoteBatchStore(sql.getMapper(EconomyTaskBillingMapper.class),new ObjectMapper()),cn.jia.economy.task.TaskQuoteBatches.class);
        billing = proxy(new TaskBillingServiceImpl(sql.getMapper(EconomyTaskBillingMapper.class), ledger,
                new EconomyPostingServiceImpl(ledger, manager, new EconomyPreviewGate(new EconomyPreviewProperties(
                        true, List.of(new EconomyPreviewProperties.AllowedScope(TENANT, CLIENT))))),
                new ObjectMapper(), pricing), TaskBillingService.class);
        var identities = mock(AgentIdentityService.class);
        when(identities.resolveAgentIdInScope(TENANT, CLIENT, OWNER, AGENT)).thenReturn(AGENT);
        when(identities.lockActiveCanonicalAgentIdsInScope(TENANT, CLIENT, OWNER, List.of(AGENT))).thenReturn(List.of(AGENT));
        var legacy = new AgentLegacyTaskCompatibilityService(meta, members, work, identities, roots, events);
        var links = mock(PersonalWorkspaceTaskLinkDao.class);
        when(links.list(TENANT, CLIENT, OWNER, TASK, null, null, 33)).thenReturn(List.of());
        var requirements = mock(AgentTaskRequirementSnapshotService.class);
        var requirement = new AgentTaskRequirementSnapshotService.Snapshot(TENANT, CLIENT, OWNER, TASK,
                1, "Fixture title", "Fixture description", "a".repeat(64), "CREATE");
        when(requirements.requireCurrent(any(), eq(TASK), eq(1L))).thenReturn(requirement);
        when(requirements.freezeCurrent(any(), eq(TASK), eq(1L))).thenReturn(requirement);
        when(requirements.read(SCOPE, TASK, 1L)).thenReturn(requirement);
        outbox = spy(new AgentTaskBountyBootstrapOutboxDaoImpl(sql.getMapper(AgentTaskBountyBootstrapOutboxMapper.class)));
        doAnswer(call -> {
            Object written = call.callRealMethod();
            if (failBootstrap) throw new InjectedFailure();
            return written;
        }).when(outbox).insert(any());
        bootstrap = new AgentTaskBountyBootstrapOutboxServiceImpl(outbox, new ObjectMapper(), manager,
                requirements, new cn.jia.core.security.FixedTenantAdmissionPolicy(TENANT, null));
        assignment = new AgentTaskExecutionGrantServiceImpl(
                new AgentTaskExecutionGrantDaoImpl(sql.getMapper(AgentTaskExecutionGrantMapper.class)),
                outbox, links, mock(PersonalWorkspaceDao.class), legacy, identities, roots,
                new ObjectMapper(), requirements, billing, meta, mock(cn.jia.agent.service.AgentHostingWorkAdmission.class));
        operationReads = new AgentTaskDeliberationOperationReadServiceImpl(
                new AgentTaskExecutionGrantDaoImpl(sql.getMapper(AgentTaskExecutionGrantMapper.class)), outbox,
                new ControlledImageBridgeOperationDaoImpl(sql.getMapper(ControlledImageBridgeOperationMapper.class)),
                requirements, roots, new ObjectMapper());
        var authority = mock(TaskAssigneeAuthorityService.class);
        var target = new TaskAssigneeAuthorityService.Target(TENANT, CLIENT, OWNER, AGENT, 1L, null);
        when(authority.lockForAssignment(TENANT, CLIENT, OWNER, AGENT)).thenReturn(target);
        when(authority.requireGrantTarget(eq(TENANT), eq(CLIENT), eq(OWNER), anyString(), anyString(), anyLong()))
                .thenReturn(target);
        assignment.setAssigneeAuthority(authority);
        // Live Agent transport is an external observation; SQL/money remains real here.
        assignment.setDeliberationRuntime(executor -> {
            assertEquals(new TaskDeliberationRuntimeLookup.Scope(TENANT, CLIENT, OWNER, AGENT), executor);
            return true;
        });
        deliveryReads = proxy(new AgentTaskFormalDeliveryReadServiceImpl(
                new AgentTaskFormalDeliveryDaoImpl(sql.getMapper(AgentTaskFormalDeliveryMapper.class)), meta),
                AgentTaskFormalDeliveryReadService.class);
        executionRows=new PersonalWorkspaceExecutionDaoImpl(sql.getMapper(PersonalWorkspaceExecutionMapper.class),
                sql.getMapper(PersonalWorkspaceExecutionInputMapper.class),sql.getMapper(PersonalWorkspaceExecutionOutputMapper.class));
        var fixed=new cn.jia.core.security.FixedTenantAdmissionPolicy(TENANT,CLIENT);
        calibrationCatalog=proxy(new cn.jia.economy.service.impl.TaskCalibrationCatalogService(sql.getMapper(cn.jia.economy.mapper.TaskCalibrationMapper.class),new ObjectMapper()),cn.jia.economy.task.TaskCalibrationCatalog.class);
        historyCatalog=proxy(new cn.jia.economy.service.impl.TaskHistoryCatalogService(sql.getMapper(cn.jia.economy.mapper.ConfirmedSampleMapper.class),new ObjectMapper()),cn.jia.economy.task.TaskHistoryCatalog.class);
        sampleEvidence=proxy(new cn.jia.economy.service.impl.ConfirmedSampleEvidenceReader(sql.getMapper(cn.jia.economy.mapper.ConfirmedSampleMapper.class)),cn.jia.economy.usage.ConfirmedSampleEvidence.class);
        sampleProjection=proxy(new cn.jia.economy.service.impl.ConfirmedSampleProjectionService(sampleEvidence,sql.getMapper(cn.jia.economy.mapper.ConfirmedSampleMapper.class),new ObjectMapper(),manager),cn.jia.economy.usage.ConfirmedSampleProjection.class);
        var schedules=new cn.jia.task.scheduling.ScheduledWorkStore(sql.getMapper(cn.jia.task.mapper.ScheduledWorkMapper.class),fixed,manager);
        var beans=new org.springframework.beans.factory.support.DefaultListableBeanFactory();beans.registerSingleton("sched",schedules);
        var receipts=proxy(new cn.jia.economy.service.impl.TaskMembershipReceiptStore(sql.getMapper(cn.jia.economy.mapper.TaskMembershipReceiptMapper.class)),cn.jia.economy.usage.TaskMembershipReceipt.class);
        membershipDelivery=new cn.jia.agent.service.usage.TaskMembershipDelivery(executionRows,receipts,beans.getBeanProvider(cn.jia.task.scheduling.ScheduleService.class),fixed);
        recovery=new cn.jia.agent.service.usage.ConfirmedSampleRecovery(sampleProjection,new cn.jia.agent.service.usage.NativeSampleResponseDecoder(),fixed,schedules,mock(cn.jia.task.scheduling.CronScheduleService.class),"0 * * * * *");
        membershipSweep=new cn.jia.agent.service.usage.TaskMembershipSweep(executionRows,fixed,manager,schedules,mock(cn.jia.task.scheduling.CronScheduleService.class),"0 * * * * *");
        decisions = proxy(new AgentTaskFormalDeliveryDecisionServiceImpl(
                new AgentTaskFormalDeliveryDaoImpl(sql.getMapper(AgentTaskFormalDeliveryMapper.class)),
                meta, members, work, roots, events, billing,executionRows), AgentTaskFormalDeliveryDecisionService.class);
        var cancelDao = spy(new AgentTaskCancellationDaoImpl(sql.getMapper(AgentTaskCancellationMapper.class)));
        doAnswer(call -> {
            Object changed = call.callRealMethod();
            if (failOccupation) throw new InjectedFailure();
            return changed;
        }).when(cancelDao).releaseTaskOccupation(anyString(), anyString(), anyString(), anyString(), anyLong());
        var leases = new AgentWorkItemLeaseServiceImpl(members, work, roots, events, 900000);
        leases.setAssigneeAuthority(authority);
        when(authority.requireTaskAgent(TENANT,CLIENT,OWNER,TASK,AGENT,false)).thenReturn(AGENT);

        var executionCancellation=proxy(new TaskExecutionCancellationServiceImpl(executionRows,
                NativeCancellationFixture.create(jdbc,executionRows,new SkillExecutionDaoImpl(sql.getMapper(SkillExecutionMapper.class)),
                        mock(org.springframework.context.ApplicationEventPublisher.class),TENANT,CLIENT,OWNER,TASK,AGENT,roots)),TaskExecutionCancellationService.class);
        var consentCancellation = proxy(new AgentTaskProviderCostConsentServiceImpl(
                new AgentTaskProviderCostConsentDaoImpl(sql.getMapper(AgentTaskProviderCostConsentMapper.class)),
                assignment,roots,mock(ControlledImageProviderOperatorPolicy.class),new ObjectMapper()), AgentTaskProviderCostConsentService.class);
        cancellation = new AgentTaskCancellationServiceImpl(roots,
                new AgentTaskStateServiceImpl(meta, members, work, roots, events), work,
                new AgentTaskExecutionGrantDaoImpl(sql.getMapper(AgentTaskExecutionGrantMapper.class)), cancelDao, billing,
                executionCancellation,
                mock(cn.jia.chat.service.TaskDiscussionCancellation.class),
                proxy(leases,AgentWorkItemLeaseService.class),consentCancellation,executionRows);
        var rentBindings = new cn.jia.agent.dao.impl.AgentPersonaBindingDaoImpl();
        ReflectionTestUtils.setField(rentBindings, "baseMapper", sql.getMapper(AgentPersonaBindingMapper.class));
        rentSettlement = new cn.jia.agent.hosting.HostingRentTaskSettlement(sql.getMapper(HostingRentFinishingMapper.class),
                rentBindings, new cn.jia.economy.service.impl.HostingRentRenewalServiceImpl(
                        sql.getMapper(cn.jia.economy.mapper.EconomyHostingRentMapper.class),
                        sql.getMapper(cn.jia.economy.mapper.EconomyHostingRenewalMapper.class),
                        ledger, mock(cn.jia.economy.service.EconomyPostingService.class), walletGate, manager), roots,
                new AgentTaskFormalDeliveryDaoImpl(sql.getMapper(AgentTaskFormalDeliveryMapper.class)), decisions,
                cancellation, new cn.jia.core.security.FixedTenantAdmissionPolicy(TENANT, CLIENT));
        jdbc.update("INSERT INTO agent_task_meta(task_id,owner_jiacn,tenant_id,client_id) VALUES(?,?,?,?)",
                TASK, OWNER, TENANT, CLIENT);
        jdbc.update("""
                INSERT INTO economy_account(account_id,owner_type,owner_id,purpose,currency,balance_wen,
                  allow_negative,status,version,tenant_id,client_id,create_time,update_time)
                VALUES('wallet','USER',?,'AVAILABLE','WEN',100,0,'ACTIVE',0,?,?,1,1)
                """, PAYER, TENANT, CLIENT);
        quoteId = locked(() -> billing.quote(BILLING_TASK, new TaskBillingService.Binding(1, "a".repeat(64),
                assignment.snapshotTaskInputsWithinLockedTask(SCOPE, TASK).digest(), AGENT, "recommendation-1"),
                PAYER, new TaskWorkload("GENERAL", 1_000_000, 0, 1, Map.of()),
                "fixture-model")).quoteId();
    }

    @AfterEach
    void close() throws Exception {
        try {
            if (httpServer != null) { httpServer.stop(); httpServer.destroy(); }
        } finally { if (httpContext != null) httpContext.close(); }
        if (publisher != null) publisher.close();
        if (admin != null && database != null) admin.execute("DROP DATABASE IF EXISTS `" + database + "`");
    }

    private void expiredRelationship() {
        jdbc.update("INSERT INTO agent_persona_binding(id,jiacn,persona_code,agent_id,bound_at,status,tenant_id,client_id) VALUES(7,?,'hero',?,1,1,?,?)",
                OWNER, AGENT, TENANT, CLIENT);
        jdbc.update("""
                INSERT INTO economy_hosting_lease(lease_id,principal_type,principal_id,persona_code,agent_id,binding_id,
                plan_id,plan_version,amount_wen,period_seconds,status,paid_from,paid_through,locked_at,latest_intent_id,version,tenant_id,client_id,create_time,update_time)
                VALUES('expired-lease','USER',?,'hero',?,'7','hosted',1,500,2592000,'FINISHING',1,2,2,'intent',1,?,?,1,1)
                """, PAYER, AGENT, TENANT, CLIENT);
    }
    @Test void expiredRelationshipSettlementAcceptsOnlyOriginalOwnersDeliveredTask() {
        assign(); prepareSubmitted(); expiredRelationship();
        jdbc.update("INSERT INTO agent_task_meta(task_id,owner_jiacn,tenant_id,client_id,assigned_agent_id,reward_status) VALUES('foreign','other-owner',?,?,?,'running')",
                TENANT, CLIENT, AGENT);
        rentSettlement.settleExpiredTasks(new EconomyScope(TENANT, CLIENT), "expired-lease");
        rentSettlement.settleExpiredTasks(new EconomyScope(TENANT, CLIENT), "expired-lease");
        assertEquals("CAPTURED", charge());
        assertEquals("completed", jdbc.queryForObject("SELECT reward_status FROM agent_task_meta WHERE task_id=?", String.class,TASK));
        assertEquals("running", jdbc.queryForObject("SELECT reward_status FROM agent_task_meta WHERE task_id='foreign'", String.class));
        assertEquals(2,count("economy_transaction"));
        assertEquals(1,jdbc.queryForObject("SELECT live_slot FROM economy_hosting_lease",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT status FROM agent_persona_binding",Integer.class));
    }
    @Test void expiredRelationshipSettlementCancelsFailedTaskAndReleasesHoldOnce() throws Exception {
        prepareCancellation(); assign(); expiredRelationship(); jdbc.update("UPDATE agent_task_meta SET reward_status='failed'");
        rentSettlement.settleExpiredTasks(new EconomyScope(TENANT, CLIENT), "expired-lease");
        rentSettlement.settleExpiredTasks(new EconomyScope(TENANT, CLIENT), "expired-lease");
        assertEquals("cancelled",taskState()); assertEquals("RELEASED",charge()); assertEquals(100,wallet(jdbc));
        assertEquals(2,count("economy_transaction"));
    }
    @Test void expiredRelationshipSettlementDoesNotForceVoluntaryRemovalOrWrongBinding() {
        assign(); expiredRelationship(); jdbc.update("UPDATE economy_hosting_lease SET locked_at=NULL");
        rentSettlement.settleExpiredTasks(new EconomyScope(TENANT, CLIENT), "expired-lease");
        assertEquals("HELD",charge()); assertEquals("assigned",taskState());
        jdbc.update("UPDATE economy_hosting_lease SET locked_at=CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)");
        rentSettlement.settleExpiredTasks(new EconomyScope(TENANT, CLIENT), "expired-lease");
        assertEquals("HELD",charge()); assertEquals("assigned",taskState());
        jdbc.update("UPDATE economy_hosting_lease SET locked_at=2,binding_id='8'");
        rentSettlement.settleExpiredTasks(new EconomyScope(TENANT, CLIENT), "expired-lease");
        assertEquals("HELD",charge()); assertEquals(1,count("economy_transaction"));
    }

    @Test
    void automaticExpiryAcceptanceCapturesOnceAndPersistsSystemNotOwnerAudit() {
        assign(); prepareSubmitted();
        var command = new AgentTaskFormalDeliveryDecisionDTO(); command.setDeliveryId("delivery-1");
        command.setDecision("accepted"); command.setExpectedTaskVersion(4L); command.setExpectedDeliveryVersion(0L);
        assertFalse(decisions.acceptForHostingExpiry(TENANT, CLIENT, TASK, OWNER, command).getReplayed());
        assertTrue(decisions.acceptForHostingExpiry(TENANT, CLIENT, TASK, OWNER, command).getReplayed());
        assertEquals("CAPTURED", charge()); assertEquals("completed", taskState());
        assertNull(jdbc.queryForObject("SELECT reviewed_by_jiacn FROM agent_task_formal_delivery", String.class));
        assertEquals("hosting_rent_expired", jdbc.queryForObject("SELECT review_reason FROM agent_task_formal_delivery", String.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM agent_task_event WHERE event_type='FORMAL_DELIVERY_ACCEPTED' AND actor_type='system' AND actor_id IS NULL", Integer.class));
    }

    @Test
    void automaticExpiryEventFailureRollsBackMoneyAndCanRetry() {
        assign(); prepareSubmitted(); failEvent = TaskEventType.FORMAL_DELIVERY_ACCEPTED;
        var command = new AgentTaskFormalDeliveryDecisionDTO(); command.setDeliveryId("delivery-1");
        command.setDecision("accepted"); command.setExpectedTaskVersion(4L); command.setExpectedDeliveryVersion(0L);
        assertThrows(InjectedFailure.class, () -> decisions.acceptForHostingExpiry(TENANT, CLIENT, TASK, OWNER, command));
        assertEquals("HELD", charge()); assertEquals("reviewing", taskState());
        assertEquals("submitted", jdbc.queryForObject("SELECT state FROM agent_task_formal_delivery", String.class));
        failEvent = null; decisions.acceptForHostingExpiry(TENANT, CLIENT, TASK, OWNER, command);
        assertEquals("CAPTURED", charge());
    }

    @Test
    void unavailableNativeDeliberationRollsBackAssignmentAndReservesNoMoney() {
        assignment.setDeliberationRuntime(executor -> false);
        var failure = assertThrows(AgentTaskExecutionGrantException.class, this::assign);
        assertEquals("TASK_DELIBERATION_NOT_READY", failure.code());
        assertAssignmentAbsent();
    }

    @Test
    void assignmentAndAcceptanceCommitOnceAndReplayOriginalReceipt() {
        var first = assign();
        assertEquals(first.getGrantId(), assign().getGrantId());
        assertEquals(50, wallet(jdbc)); assertEquals("HELD", charge()); assertEquals(1, count("economy_transaction"));
        assertEquals(1, count("agent_task_execution_grant")); assertEquals(1, count("agent_task_bounty_bootstrap_outbox"));
        prepareSubmitted();
        var receipt = decide("accepted");
        assertFalse(receipt.getReplayed()); assertTrue(decide("accepted").getReplayed());
        assertEquals("CAPTURED", charge()); assertEquals(50, wallet(jdbc));
        assertEquals(2, count("economy_transaction"));
        assertEquals(50L, jdbc.queryForObject("SELECT balance_wen FROM economy_account WHERE purpose='TASK_FEE'", Long.class));
        assertEquals(0L, jdbc.queryForObject("SELECT SUM(balance_wen) FROM economy_account WHERE owner_type='TASK'", Long.class));
        assertEquals("completed", taskState());
        assertEquals("done", jdbc.queryForObject("SELECT member_status FROM agent_task_member", String.class));
    }

    @Test
    void assignmentEventFailureRollsBackRealMoneyMembersWorkAndEvents() {
        failEvent = TaskEventType.TASK_ASSIGNED;
        assertThrows(InjectedFailure.class, this::assign);
        assertAssignmentAbsent();
        verifyNoInteractions(broker);
        failEvent = null; assign();
        assertEquals(50, wallet(jdbc)); assertEquals("HELD", charge());
    }

    @Test
    void persistedBootstrapFailureRollsBackGrantAssignmentAndHoldTogether() {
        failBootstrap = true;
        assertThrows(InjectedFailure.class, this::assign);
        assertAssignmentAbsent(); verifyNoInteractions(broker);
        failBootstrap = false; assign();
        assertEquals(1, count("agent_task_bounty_bootstrap_outbox"));
        assertEquals(1, count("economy_transaction"));
    }

    @Test
    void acceptanceEventFailureRestoresHoldAndAllCompletionFacts() {
        assign(); prepareSubmitted(); clearInvocations(broker);
        int eventsBefore = count("agent_task_event");
        failEvent = TaskEventType.FORMAL_DELIVERY_ACCEPTED;
        assertThrows(InjectedFailure.class, () -> decide("accepted"));
        assertEquals("HELD", charge()); assertEquals(1, count("economy_transaction"));
        assertEquals("reviewing", taskState()); assertEquals(eventsBefore, count("agent_task_event"));
        assertEquals("submitted", jdbc.queryForObject("SELECT state FROM agent_task_formal_delivery", String.class));
        assertEquals("submitted", jdbc.queryForObject("SELECT status FROM agent_task_work_item", String.class));
        assertEquals("accepted", jdbc.queryForObject("SELECT member_status FROM agent_task_member", String.class));
        verifyNoInteractions(broker);
        failEvent = null; decide("accepted"); assertEquals("CAPTURED", charge());
    }

    @Test
    void physicalOuterTransactionHidesAssignmentAndCaptureUntilCommit() {
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            assign();
            assertEquals(100, wallet(independent)); assertEquals(50, wallet(jdbc));
            assertEquals(0, independent.queryForObject("SELECT COUNT(*) FROM agent_task_execution_grant", Integer.class));
            verifyNoInteractions(broker);
        });
        assertEquals(50, wallet(independent));
        prepareSubmitted(); clearInvocations(broker);
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            decide("accepted");
            assertEquals("HELD", independent.queryForObject("SELECT state FROM economy_task_charge", String.class));
            assertEquals("reviewing", independent.queryForObject("SELECT reward_status FROM agent_task_meta", String.class));
            verifyNoInteractions(broker);
        });
        assertEquals("CAPTURED", charge()); assertEquals("completed", taskState());
    }

    @Test
    void concurrentAcceptanceReplaysUnderRootLockWithoutDoubleCapture() throws Exception {
        assign(); prepareSubmitted();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { start.await(); return decide("accepted").getReplayed(); });
            var b = pool.submit(() -> { start.await(); return decide("accepted").getReplayed(); });
            start.countDown();
            assertNotEquals(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        }
        assertEquals(2, count("economy_transaction")); assertEquals("CAPTURED", charge());
    }

    private record PreparedFixture(TaskBillingService.Binding binding,TaskEstimateFeatures features,TaskBillingService.Quote offer,TaskBillingService.MeasuredPreparation preparation) {}
    private PreparedFixture measuredOffer(){
        String inputs=locked(()->assignment.snapshotTaskInputsWithinLockedTask(SCOPE,TASK).digest());
        var binding=new TaskBillingService.Binding(1,"a".repeat(64),inputs,AGENT,"measured-batch");
        var vec=new java.util.ArrayList<>(java.util.Collections.nCopies(1024,0f));vec.set(0,1f);
        var features=new TaskEstimateFeatures(TaskEstimateFeatures.VERSION,cn.jia.agent.service.recommendation.QuoteHttpTestWiring.template(),binding.requirementSha256(),inputs,"b".repeat(64),
                new TaskEstimateFeatures.Identity("cfg","fixture",1024),vec,List.of(),null);
        var unit=new TaskQuoteCalculator.ToolKey("test","draw","frame");
        var fact=new MeasuredTaskRepricing.Fact(new MeasuredTaskRepricing.Operation("source","op"),"draw","frame","fixture-proof",new MeasuredTaskRepricing.Units(unit,BigDecimal.ONE));
        var reference=new MeasuredTaskRepricing.Reference("book",Map.of("draw",Map.of("frame",new MeasuredTaskRepricing.UnitRate(unit,new TaskQuoteCalculator.ToolPrice("r1","fixture",new BigDecimal("0.25"))))));
        var estimate=MeasuredNeighborEstimate.aggregate(reference,List.of(new MeasuredNeighborEstimate.Neighbor("training-case","7",BigDecimal.ONE,List.of(fact))));
        var prepared=new TaskBillingService.MeasuredPreparation(features,new TaskBillingService.MeasuredGroup("NATIVE_DISPATCH_GROUP_V1","c".repeat(64),"f".repeat(64),"r1","d".repeat(64),"cp"),"v1","fixture-holdout","fixture",estimate);
        var offer=locked(()->billing.storeMeasured(BILLING_TASK,binding,PAYER,prepared));
        return new PreparedFixture(binding,features,offer,prepared);
    }
    @Test void measuredBatchRollsBackEarlierChunksAndPreservesMixedSnapshots() throws Exception {
        var f=measuredOffer(); var p=f.preparation(); int before=count("economy_task_quote");
        var g=p.group();var group=new TaskBillingService.MeasuredGroup(g.dispatchGroupVersion(),"e".repeat(64),g.localRouteSha256(),g.routeVersion(),g.toolConfigSha256(),g.capturePolicyVersion());
        var price=new MeasuredNeighborEstimate.Estimate(p.estimate().contributions(),new BigDecimal("0.50"),BigDecimal.ONE,50L);
        var second=new TaskBillingService.MeasuredPreparation(p.features(),group,p.estimatorVersion(),p.calibrationVersion(),p.qualityStatus(),price);
        var writes=new java.util.ArrayList<TaskBillingService.MeasuredWrite>();
        for(int i=0;i<40;i++)writes.add(new TaskBillingService.MeasuredWrite(new TaskBillingService.Binding(1,f.binding().requirementSha256(),f.binding().inputSnapshotDigest(),"multi-"+i,"mixed"),i%2==0?p:second));
        writes.add(writes.getFirst()); // database unique binding fails after at least one successful INSERT
        assertThrows(org.springframework.dao.DuplicateKeyException.class,()->locked(()->billing.storeMeasuredBatch(BILLING_TASK,PAYER,writes)));
        assertEquals(before,count("economy_task_quote"));writes.removeLast();
        var receipts=locked(()->billing.storeMeasuredBatch(BILLING_TASK,PAYER,writes));assertEquals(40,receipts.size());
        var json=new ObjectMapper();for(int i=0;i<40;i++){
            var q=receipts.get(i);assertEquals(i%2==0?25L:50L,q.amountWen());var expected=writes.get(i);
            var raw=jdbc.queryForObject("SELECT snapshot_json FROM economy_task_quote WHERE quote_id=?",String.class,q.quoteId());
            var tree=json.readTree(raw);assertEquals(json.readTree(json.writeValueAsString(expected.prepared())),tree.path("preparation"));
            assertEquals(json.readTree(json.writeValueAsString(expected.binding())),tree.path("binding"));
        }
        assertEquals(before+40,count("economy_task_quote"));assertEquals(0,count("economy_transaction"));
    }
    @Test void measuredQuoteSeedsOnlyUponSuccessfulConfirmationAndNeverRepricesOnReplay(){
        var fixture=measuredOffer();var offer=fixture.offer();var features=fixture.features();var binding=fixture.binding();var inputs=binding.inputSnapshotDigest();
        assertEquals(25,offer.amountWen());assertEquals(0,count("economy_task_cost_sample"));
        assertThrows(InjectedFailure.class,()->locked(()->{billing.reserve(BILLING_TASK,offer.quoteId(),binding,PAYER);throw new InjectedFailure();}));
        assertEquals(0,count("economy_task_charge"));assertEquals(0,count("economy_task_cost_sample"));assertEquals(0,count("economy_transaction"));
        var held=locked(()->billing.reserve(BILLING_TASK,offer.quoteId(),binding,PAYER));assertEquals(held,locked(()->billing.reserve(BILLING_TASK,offer.quoteId(),binding,PAYER)));
        assertEquals(held,locked(()->billing.reservation(BILLING_TASK,offer.quoteId(),PAYER,binding.recommendationId())));
        assertEquals(1,count("economy_task_cost_sample"));assertEquals(features.digest(),jdbc.queryForObject("SELECT initial_feature_sha256 FROM economy_task_cost_sample WHERE quote_id=? AND initial_input_snapshot_sha256=? AND lifecycle_state='ACTIVE' AND sample_state='WAITING_EVIDENCE'",String.class,offer.quoteId(),inputs));
        assertEquals(1,count("economy_transaction"));
    }

    private void confirmMeasured(){
        var prepared=measuredOffer();assignment.assignForDeliberation(SCOPE,TASK,"assign-1",AGENT,0,1,
                new AgentTaskExecutionGrantService.Payment(PAYER,prepared.offer().quoteId(),prepared.binding().recommendationId()));
    }
    private void deliverTerminal(){
        var sweepKey=new cn.jia.task.scheduling.ScheduleKey(CLIENT,membershipSweep.getName(),"cron:fixture","");
        membershipSweep.executeScheduled(new cn.jia.task.scheduling.ScheduledInvocation(TENANT,sweepKey,1));
        var fact=jdbc.queryForObject("SELECT business_key FROM task_scheduled_work WHERE handler=?",String.class,membershipDelivery.getName());
        membershipDelivery.executeScheduled(new cn.jia.task.scheduling.ScheduledInvocation(TENANT,new cn.jia.task.scheduling.ScheduleKey(CLIENT,membershipDelivery.getName(),fact,""),2));
    }
    @Test void acceptedMeasuredTaskIncludesUnmeteredRetryAndNeverPromotesOnMembershipAlone(){
        confirmMeasured();prepareSubmitted();
        locked(()->{for(int i=0;i<2;i++){var m=new cn.jia.agent.entity.PersonalWorkspaceExecutionEntity().setTaskId(TASK).setOwnerJiacn(OWNER).setExecutionId("exec").setRunId("run"+i).setRequestHash("a".repeat(64));m.setTenantId(TENANT);m.setClientId(CLIENT);executionRows.registerTaskMember(m,i==0?"a".repeat(64):null);}return null;});
        decide("accepted");deliverTerminal();assertEquals("CAPTURED",charge());assertEquals(2,count("economy_task_cost_sample_member"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM economy_task_cost_sample_member WHERE capture_id IS NULL AND run_id='run1'",Integer.class));
        assertEquals("WAITING_EVIDENCE",jdbc.queryForObject("SELECT sample_state FROM economy_task_cost_sample WHERE lifecycle_state='ACCEPTED'",String.class));
        jdbc.update("UPDATE agent_task_execution_set SET delivery_state='PENDING'");deliverTerminal();assertEquals(2,count("economy_task_cost_sample_member"));
        assertEquals(2L,jdbc.queryForObject("SELECT evidence_revision FROM economy_task_cost_sample",Long.class));
    }
    @Test void cancelledMeasuredTaskRemainsExcludedAfterReleaseEvenWithoutMembers() throws Exception {
        prepareCancellation();confirmMeasured();prepareRunning();cancellation.cancel(TENANT,CLIENT,OWNER,PAYER,TASK,3);deliverTerminal();
        assertEquals("RELEASED",charge());assertEquals(100,wallet(jdbc));assertEquals("EXCLUDED",jdbc.queryForObject("SELECT sample_state FROM economy_task_cost_sample WHERE lifecycle_state='CANCELLED'",String.class));
    }
    private static cn.jia.economy.usage.ConfirmedSampleEvidence.Decoder textFixture(java.util.function.Function<cn.jia.economy.usage.ConfirmedSampleEvidence.Response,MeasuredTaskRepricing.Fact> fn){
        return new cn.jia.economy.usage.ConfirmedSampleEvidence.Decoder(){public boolean coversAllOperations(String group,String policy){return "NATIVE_DISPATCH_GROUP_V1".equals(group)&&"cp".equals(policy);} public MeasuredTaskRepricing.Fact decode(cn.jia.economy.usage.ConfirmedSampleEvidence.Response r){return fn.apply(r);}};
    }
    @Test void confirmedEligibilityComparesEveryCurrentMemberButNeverTreatsReferenceRouteAsActualIdentity() throws Exception {
        confirmMeasured();String sid=jdbc.queryForObject("SELECT sample_id FROM economy_task_cost_sample",String.class);
        assertNull(sampleEvidence.inspect(new TaskBillingService.Task(BILLING_TASK.scope(),"other",TASK),sid));
        assertEquals("ACTIVE",sampleEvidence.inspect(BILLING_TASK,sid).verdict().name());prepareSubmitted();
        locked(()->{for(int i=0;i<65;i++){var m=new cn.jia.agent.entity.PersonalWorkspaceExecutionEntity().setTaskId(TASK).setOwnerJiacn(OWNER).setExecutionId("exec"+i).setRunId("run").setRequestHash("a".repeat(64));m.setTenantId(TENANT);m.setClientId(CLIENT);executionRows.registerTaskMember(m,cn.jia.agent.service.usage.NativeCaptureEvidence.captureIdentity(m.getExecutionId(),m.getRunId()));}return null;});
        decide("accepted");deliverTerminal();deliverTerminal();
        assertEquals("USAGE_INCOMPLETE",sampleEvidence.inspect(BILLING_TASK,sid).verdict().name());
        // Synthetic independently projected facts, not a real provider coverage grant or READY label.
        jdbc.update("INSERT INTO economy_usage_capture(tenant_id,client_id,owner_jiacn,task_id,agent_id,execution_id,run_id,capture_id,input_revision,projection_revision,coverage_state) SELECT ?,?,?,?,?,execution_id,run_id,capture_id,1,1,'COMPLETE' FROM economy_task_cost_sample_member",TENANT,CLIENT,OWNER,TASK,AGENT);
        jdbc.update("INSERT INTO economy_usage_capture_dispatch_group SELECT id,1,'NATIVE_DISPATCH_GROUP_V1',REPEAT('c',64),REPEAT('f',64),REPEAT('d',64),'cp',REPEAT('9',64) FROM economy_usage_capture");
        var matched=sampleEvidence.inspect(BILLING_TASK,sid);assertEquals(65,matched.members());assertEquals("MATCHED",matched.verdict().name());
        var noResponses=sampleEvidence.measure(BILLING_TASK,sid,textFixture(r->{throw new AssertionError("No responses yet");}));assertTrue(noResponses.operations().isEmpty());assertEquals("USAGE_INCOMPLETE",noResponses.assessment().verdict().name());
        jdbc.update("INSERT INTO economy_usage_capture_record(capture_pk,record_id,revision_key,source_revision,kind,evidence_sha256,binding_sha256,evidence_json,is_associated_head,association_state) SELECT id,REPEAT('1',64),REPEAT('2',64),'1','RESPONSE',REPEAT('3',64),REPEAT('4',64),'{}',1,'APPLIED' FROM economy_usage_capture");
        cn.jia.economy.usage.ConfirmedSampleEvidence.Decoder fixtureDecoder=textFixture(r->new MeasuredTaskRepricing.Fact(new MeasuredTaskRepricing.Operation("fixture",r.captureId()),"MODEL_RESPONSE","INFERENCE",r.evidenceSha(),new MeasuredTaskRepricing.Tokens("fixture",Map.of(ModelTokenPrice.Category.INPUT,1L))));
        var measured=sampleEvidence.measure(BILLING_TASK,sid,fixtureDecoder);assertEquals(65,measured.operations().size());assertEquals(65,measured.operations().stream().map(x->x.fact().operation()).distinct().count());
        var partial=sampleEvidence.measure(BILLING_TASK,sid,textFixture(r->r.memberRevision()==measured.operations().getLast().memberRevision()?null:fixtureDecoder.decode(r)));assertEquals("USAGE_INCOMPLETE",partial.assessment().verdict().name());assertTrue(partial.operations().isEmpty());
        var firstLine=new java.util.concurrent.atomic.AtomicBoolean(true);
        assertFalse(sampleProjection.refresh(BILLING_TASK,sid,textFixture(r->{if(firstLine.getAndSet(false)) independent.update("UPDATE economy_task_cost_sample SET evidence_revision=evidence_revision+1 WHERE sample_id=?",sid);return fixtureDecoder.decode(r);})));
        assertEquals(0,count("economy_task_cost_sample_revision"));
        assertTrue(sampleProjection.refresh(BILLING_TASK,sid,fixtureDecoder));assertFalse(sampleProjection.refresh(BILLING_TASK,sid,fixtureDecoder));
        var group=new cn.jia.economy.task.TaskHistoryCatalog.Group(new TaskBillingService.MeasuredGroup("NATIVE_DISPATCH_GROUP_V1","c".repeat(64),"f".repeat(64),"r1","d".repeat(64),"cp"),TaskEstimateFeatures.VERSION,cn.jia.agent.service.recommendation.QuoteHttpTestWiring.template(),new TaskEstimateFeatures.Identity("cfg","fixture",1024));
        var query=new TaskBillingService.Task(BILLING_TASK.scope(),OWNER,"new-task");
        var entries=historyCatalog.page(query,group,"");assertEquals(1,entries.size());var selected=entries.getFirst().source();assertEquals(sid,selected.sampleId());
        assertTrue(historyCatalog.page(BILLING_TASK,group,"").isEmpty());assertTrue(historyCatalog.page(new TaskBillingService.Task(query.scope(),"other","new-task"),group,"").isEmpty());
        assertTrue(historyCatalog.page(query,group,sid).isEmpty());assertTrue(historyCatalog.page(query,new cn.jia.economy.task.TaskHistoryCatalog.Group(group.execution(),group.featureVersion(),"different-template",group.embedding()),"").isEmpty());
        var needle=entries.getFirst().features();var scales=cn.jia.economy.pricing.TaskNeighborSearch.trainingScales(List.of(needle));
        var searchParams=new cn.jia.economy.pricing.TaskNeighborSearch.Parameters(scales,1,1,1,1,1,0.1,0.2,1,1,1,1);
        assertNull(calibrationCatalog.resolve(query,group));
        var gate=new cn.jia.economy.pricing.TaskEstimateCalibration.Gate(1,BigDecimal.ONE);
        var ratio=new cn.jia.economy.pricing.TaskEstimateEvaluation.Ratio(BigDecimal.ZERO,BigDecimal.ONE);
        var scores=new cn.jia.economy.pricing.TaskEstimateEvaluation.Scores(1,1,0,1,0,BigDecimal.ZERO,ratio,ratio);
        var fitted=new cn.jia.economy.pricing.TaskEstimateCalibration.Fit("fixture",searchParams,scores);
        var report=new cn.jia.economy.pricing.TaskEstimateCalibration.Report(group.digest(),group.execution().routeVersion(),fitted,scores,true);
        var receipt=new cn.jia.economy.task.TaskCalibrationCatalog.Receipt(cn.jia.economy.pricing.TaskNeighborSearch.VERSION,"1".repeat(64),"2".repeat(64),"3".repeat(64),"4".repeat(64),gate,report);
        var receiptJson=new ObjectMapper().writeValueAsString(receipt);
        jdbc.update("INSERT INTO economy_task_estimator_evidence VALUES(?,?,?,?,SHA2(?,256),?)",TENANT,CLIENT,OWNER,group.digest(),receiptJson,receiptJson);
        jdbc.update("INSERT INTO economy_task_estimator_current VALUES(?,?,?,?,1,SHA2(?,256))",TENANT,CLIENT,OWNER,group.digest(),receiptJson);
        var active=calibrationCatalog.resolve(query,group);assertEquals(searchParams,active.parameters());assertTrue(locked(()->calibrationCatalog.lockCurrent(query,active.pin())));
        assertNull(calibrationCatalog.resolve(new TaskBillingService.Task(query.scope(),"different-owner","offer"),group));
        jdbc.update("UPDATE economy_task_estimator_current SET generation=2,receipt_sha=NULL WHERE group_sha=?",group.digest());
        assertNull(calibrationCatalog.resolve(query,group));assertFalse(locked(()->calibrationCatalog.lockCurrent(query,active.pin())));
        jdbc.update("UPDATE economy_task_estimator_current SET generation=3,receipt_sha=SHA2(?,256) WHERE group_sha=?",receiptJson,group.digest());
        assertFalse(locked(()->calibrationCatalog.lockCurrent(query,active.pin()))); // reactivation of same bytes cannot resurrect stale preparation
        var engine=new cn.jia.economy.service.impl.MeasuredHistoryEstimateEngine(historyCatalog,(priceScope,v,routes,kinds)->{
            assertEquals(query.scope(),priceScope);assertEquals("r1",v);assertEquals(java.util.Set.of("MODEL_RESPONSE"),kinds);
            var ref=new ModelTokenPrice("fixture","model","tier","test-price","synthetic",Map.of(ModelTokenPrice.Category.INPUT,new BigDecimal("10000")),null,Map.of());
            return new MeasuredTaskRepricing.Reference(v,Map.of("MODEL_RESPONSE",Map.of("INFERENCE",new MeasuredTaskRepricing.TokenRate("fixture",ref))));});
        var prediction=engine.estimate(query,group.execution(),needle,searchParams,Map.of());assertEquals(65L,prediction.wen());assertEquals(sid,prediction.contributions().getFirst().sampleId());
        var estimator=new cn.jia.economy.service.impl.TaskBudgetEstimatorService(calibrationCatalog,historyCatalog,engine);
        var terms=new cn.jia.economy.task.TaskBudgetEstimator.Terms(group.execution(),Map.of("MODEL_RESPONSE",Map.of("INFERENCE",new cn.jia.economy.pricing.TaskReferencePricing.TokenRoute("fixture","model","tier","fixture"))));
        assertEquals(new cn.jia.economy.task.TaskBudgetEstimator.Unavailable(TaskQuoteBatches.Reason.BILLING_ROUTE_MISSING),estimator.prepare(query,needle,null));
        assertEquals(new cn.jia.economy.task.TaskBudgetEstimator.Unavailable(TaskQuoteBatches.Reason.NO_BASELINE),estimator.prepare(new TaskBillingService.Task(query.scope(),"other","offer"),needle,terms));
        var available=assertInstanceOf(cn.jia.economy.task.TaskBudgetEstimator.Available.class,estimator.prepare(query,needle,terms));assertEquals(65,available.preparation().estimate().wen());
        locked(()->{estimator.lockSelected(query,List.of(available,available));return null;});
        verifyHistoryQuoteHttp(query,proxy(estimator,cn.jia.economy.task.TaskBudgetEstimator.class),terms);
        var recovered=historyCatalog.quantities(query,group,selected);assertEquals(measured.operations().stream().map(o->o.fact()).toList(),recovered);
        assertNull(historyCatalog.quantities(new TaskBillingService.Task(query.scope(),"other","new-task"),group,selected));
        assertTrue(locked(()->historyCatalog.lockCurrent(query,group,selected)));assertEquals(1024,entries.getFirst().features().vector().size());
        assertEquals(65,jdbc.queryForObject("SELECT JSON_LENGTH(operation_refs) FROM economy_task_cost_sample_revision WHERE sample_state='READY'",Integer.class));
        jdbc.update("UPDATE economy_task_cost_sample SET evidence_revision=evidence_revision+1,sample_state='WAITING_EVIDENCE'"); // synthetic source invalidation, journal/CAS tested independently
        assertThrows(cn.jia.economy.task.TaskBudgetEstimator.Stale.class,()->locked(()->{estimator.lockSelected(query,List.of(available));return null;}));
        assertTrue(historyCatalog.page(query,group,"").isEmpty());assertFalse(locked(()->historyCatalog.lockCurrent(query,group,selected)));assertNull(historyCatalog.quantities(query,group,selected));
        jdbc.update("UPDATE economy_usage_capture_dispatch_group SET route_sha256=REPEAT('3',64) WHERE capture_pk=(SELECT id FROM economy_usage_capture WHERE execution_id='exec64')");
        assertEquals("MIXED_EXECUTION_CONFIG",sampleEvidence.inspect(BILLING_TASK,sid).verdict().name());
        assertTrue(sampleProjection.refresh(BILLING_TASK,sid,fixtureDecoder));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM economy_task_cost_sample_revision WHERE sample_state='EXCLUDED' AND reason_code='MIXED_EXECUTION_CONFIG'",Integer.class));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM economy_task_cost_sample_revision WHERE sample_state='READY'",Integer.class));
        jdbc.update("UPDATE economy_task_cost_sample SET evidence_revision=evidence_revision+1,sample_state='WAITING_EVIDENCE'");
        jdbc.update("UPDATE economy_usage_capture SET input_revision=2 WHERE execution_id='exec64'");
        assertEquals("USAGE_INCOMPLETE",sampleEvidence.inspect(BILLING_TASK,sid).verdict().name());assertEquals("WAITING_EVIDENCE",jdbc.queryForObject("SELECT sample_state FROM economy_task_cost_sample",String.class));
        assertEquals(sid,sampleProjection.pending(TENANT,null,"").getFirst().sampleId());
        assertTrue(sampleProjection.pending(TENANT,"other","").isEmpty());assertTrue(sampleProjection.pending(TENANT,CLIENT,sid).isEmpty());
        assertFalse(sampleProjection.refreshPending(TENANT,"other",sid,fixtureDecoder));
        var sweep=new cn.jia.task.scheduling.ScheduleKey(CLIENT,recovery.getName(),"cron:fixture","");
        recovery.executeScheduled(new cn.jia.task.scheduling.ScheduledInvocation(TENANT,sweep,1));
        assertEquals(sid,jdbc.queryForObject("SELECT business_key FROM task_scheduled_work WHERE handler=?",String.class,recovery.getName()));
        var work=new cn.jia.task.scheduling.ScheduledInvocation(TENANT,new cn.jia.task.scheduling.ScheduleKey(CLIENT,recovery.getName(),sid,""),2);
        int history=count("economy_task_cost_sample_revision"),money=count("economy_transaction");
        recovery.executeScheduled(work);recovery.executeScheduled(work);assertTrue(sampleProjection.pending(TENANT,CLIENT,"").isEmpty());
        assertEquals(history+1,count("economy_task_cost_sample_revision"));assertEquals(money,count("economy_transaction"));
    }
    @Test void acceptedTaskExcludesCurrentIncompleteCaptureButWaitsForARevisedProjection(){
        confirmMeasured();String sid=jdbc.queryForObject("SELECT sample_id FROM economy_task_cost_sample",String.class);
        prepareSubmitted();locked(()->{var m=new cn.jia.agent.entity.PersonalWorkspaceExecutionEntity().setTaskId(TASK).setOwnerJiacn(OWNER).setExecutionId("e").setRunId("r").setRequestHash("a".repeat(64));m.setTenantId(TENANT);m.setClientId(CLIENT);executionRows.registerTaskMember(m,cn.jia.agent.service.usage.NativeCaptureEvidence.captureIdentity("e","r"));return null;});
        decide("accepted");deliverTerminal();int money=count("economy_transaction");
        var decoder=textFixture(r->{throw new AssertionError("No operations may be extracted");});
        // This side consumes the versioned Economy projection, not opaque Runtime JSON or its close reason text.
        jdbc.update("INSERT INTO economy_usage_capture(tenant_id,client_id,owner_jiacn,task_id,agent_id,execution_id,run_id,capture_id,input_revision,projection_revision,coverage_state) SELECT ?,?,?,?,?,execution_id,run_id,capture_id,1,1,'INCOMPLETE' FROM economy_task_cost_sample_member",TENANT,CLIENT,OWNER,TASK,AGENT);
        assertTrue(sampleProjection.refresh(BILLING_TASK,sid,decoder));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM economy_task_cost_sample_revision WHERE sample_id=? AND sample_state='EXCLUDED' AND reason_code='USAGE_INCOMPLETE' AND JSON_LENGTH(operation_refs)=0",Integer.class,sid));
        jdbc.update("UPDATE economy_usage_capture SET input_revision=2");jdbc.update("UPDATE economy_task_cost_sample SET evidence_revision=evidence_revision+1");
        assertTrue(sampleProjection.refresh(BILLING_TASK,sid,decoder));assertEquals("WAITING_EVIDENCE",jdbc.queryForObject("SELECT sample_state FROM economy_task_cost_sample",String.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM economy_task_cost_sample_revision WHERE sample_id=? AND sample_state='EXCLUDED'",Integer.class,sid));assertEquals(money,count("economy_transaction"));
    }
    @Test void concurrentQuoteIntentsReturnOneImmutableWinnerAndUnavailableReplayIgnoresNewInputs() throws Exception {
        var task=new TaskBillingService.Task(new EconomyScope(TENANT,CLIENT),OWNER,TASK);var key=new TaskQuoteBatches.Key(task,PAYER,"same-key");
        var input=new TaskQuoteBatches.Intent("4",List.of("z","a","z"));assertEquals(List.of("a","z"),input.agentIds());
        var writes=new java.util.concurrent.atomic.AtomicInteger();java.util.function.Function<String,List<TaskQuoteBatches.Item>> persist=id->{writes.incrementAndGet();return input.agentIds().stream().map(agent->new TaskQuoteBatches.Item(agent,TaskQuoteBatches.State.UNAVAILABLE,null,id,null,TaskQuoteBatches.Reason.NO_BASELINE,null)).toList();};
        var start=new CountDownLatch(1);TaskQuoteBatches.Result winner;
        try(var pool=Executors.newFixedThreadPool(2)){var a=pool.submit(()->{start.await();return locked(()->batches.commit(key,input,persist));});var b=pool.submit(()->{start.await();return locked(()->batches.commit(key,input,persist));});start.countDown();winner=a.get(20,TimeUnit.SECONDS);assertEquals(winner,b.get(20,TimeUnit.SECONDS));}
        assertEquals(1,writes.get());assertEquals(winner,batches.replay(key,new TaskQuoteBatches.Intent("4",List.of("a","z"))));
        assertThrows(TaskQuoteBatches.IntentConflict.class,()->batches.replay(key,new TaskQuoteBatches.Intent("5",List.of("z","a"))));
        assertNull(batches.replay(new TaskQuoteBatches.Key(task,"different-payer","same-key"),input));
        var fresh=new TaskQuoteBatches.Key(task,PAYER,"another-key");assertThrows(InjectedFailure.class,()->locked(()->{batches.commit(fresh,input,persist);throw new InjectedFailure();}));assertNull(batches.replay(fresh,input));
        assertEquals(1,count("economy_task_quote_batch"));
    }
    @Test void frozenMembersResumeAfterLostAckAndCorruptLastPageCannotVerify() {
        assign();prepareSubmitted();
        locked(()->{for(int i=0;i<65;i++) {var m=new cn.jia.agent.entity.PersonalWorkspaceExecutionEntity().setTaskId(TASK).setOwnerJiacn(OWNER).setExecutionId("exec"+i).setRunId("r"+i).setRequestHash("a".repeat(64));
            m.setTenantId(TENANT);m.setClientId(CLIENT);executionRows.registerTaskMember(m,null);}return null;});
        decide("accepted");assertEquals("CAPTURED",charge());
        assertEquals(0,count("economy_task_membership_receipt"));
        var sweepKey=new cn.jia.task.scheduling.ScheduleKey(CLIENT,membershipSweep.getName(),"cron:fixture","");
        membershipSweep.executeScheduled(new cn.jia.task.scheduling.ScheduledInvocation(TENANT,sweepKey,1));
        var fact=jdbc.queryForObject("SELECT business_key FROM task_scheduled_work WHERE handler=?",String.class,membershipDelivery.getName());
        var job=new cn.jia.task.scheduling.ScheduledInvocation(TENANT,new cn.jia.task.scheduling.ScheduleKey(CLIENT,membershipDelivery.getName(),fact,""),2);
        membershipDelivery.executeScheduled(job);assertEquals(64,count("economy_task_membership_received_run"));
        jdbc.update("UPDATE agent_task_execution_member SET admission_sha256=REPEAT('b',64) WHERE execution_id='exec64'");
        assertThrows(IllegalStateException.class,()->membershipDelivery.executeScheduled(job));
        assertEquals(64,count("economy_task_membership_received_run"));assertEquals("PENDING",jdbc.queryForObject("SELECT delivery_state FROM agent_task_execution_set",String.class));
        jdbc.update("UPDATE agent_task_execution_member SET admission_sha256=REPEAT('a',64) WHERE execution_id='exec64'");
        membershipDelivery.executeScheduled(job);assertTrue(jdbc.queryForObject("SELECT membership_verified FROM economy_task_membership_receipt",Boolean.class));
        assertEquals("DELIVERED",jdbc.queryForObject("SELECT delivery_state FROM agent_task_execution_set",String.class));
        jdbc.update("UPDATE agent_task_execution_set SET delivery_state='PENDING'"); // crash window after Economy commit, before source ACK
        membershipDelivery.executeScheduled(job);assertEquals(65,count("economy_task_membership_received_run"));assertEquals(0,count("economy_task_cost_sample"));
        assertEquals("DELIVERED",jdbc.queryForObject("SELECT delivery_state FROM agent_task_execution_set",String.class));
    }
    @Test
    void reworkKeepsOriginalHoldAndNeverCaptures() {
        assign(); prepareSubmitted();
        decide("changes_requested");
        assertEquals("HELD", charge()); assertEquals(1, count("economy_transaction"));
        assertEquals(50, wallet(jdbc)); assertEquals("running", taskState());
        assertTrue(decide("changes_requested").getReplayed());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM agent_task_execution_set WHERE terminal_revision IS NOT NULL",Integer.class));
    }

    @Test void runningCancellationReleasesHoldAndClosesExecutionLeaseAndGrantTogether() throws Exception {
        prepareCancellation();assign();prepareRunning();
        var receipt=cancellation.cancel(TENANT,CLIENT,OWNER,PAYER,TASK,3);
        assertEquals("cancelled",receipt.status()); assertEquals(100,wallet(jdbc));assertEquals("RELEASED",charge());
        assertEquals("CANCELLED",executionState());assertEquals("CANCELLED",skillPhase());
        assertEquals("left",jdbc.queryForObject("SELECT member_status FROM agent_task_member",String.class));
        assertNull(jdbc.queryForObject("SELECT lease_token FROM agent_task_work_item",String.class));
        assertEquals(2,jdbc.queryForObject("SELECT attempt_count FROM agent_task_work_item",Integer.class));
        assertEquals("REVOKED",jdbc.queryForObject("SELECT state FROM agent_task_execution_grant",String.class));
        assertEquals(100L,jdbc.queryForObject("SELECT started_at FROM agent_task_meta",Long.class));
        assertNull(jdbc.queryForObject("SELECT conversation_lease_token FROM agent_personal_workspace_execution",String.class));
        assertEquals(receipt,cancellation.cancel(TENANT,CLIENT,OWNER,PAYER,TASK,3));
        assertEquals(2,count("economy_transaction"));
    }
    @Test void finalCancellationWriteFailureRollsBackMoneyExecutionSkillAndLease() throws Exception {
        prepareCancellation();assign();prepareRunning();failOccupation=true;
        assertThrows(InjectedFailure.class,()->cancellation.cancel(TENANT,CLIENT,OWNER,PAYER,TASK,3));
        assertEquals("running",taskState());assertEquals(50,wallet(jdbc));assertEquals("HELD",charge());
        assertEquals("QUEUED",executionState());assertEquals("RUNNING",skillPhase());
        assertEquals("work-lease",jdbc.queryForObject("SELECT lease_token FROM agent_task_work_item",String.class));
        assertEquals("working",jdbc.queryForObject("SELECT member_status FROM agent_task_member",String.class));
        assertEquals("ACTIVE",jdbc.queryForObject("SELECT state FROM agent_task_execution_grant",String.class));
    }
    @Test void failedTaskCanReleaseHoldWithoutRewritingFailedWorkHistory() throws Exception {
        prepareCancellation();assign();
        jdbc.update("UPDATE agent_task_meta SET reward_status='failed',task_version=3,started_at=100,failure_reason='fixture failure'");
        jdbc.update("UPDATE agent_task_member SET member_status='failed',started_at=100,completed_at=200");
        jdbc.update("UPDATE agent_task_work_item SET status='failed',attempt_count=2,completed_at=200");
        cancellation.cancel(TENANT,CLIENT,OWNER,PAYER,TASK,3);
        assertEquals("RELEASED",charge()); assertEquals(100,wallet(jdbc));
        assertEquals("failed",jdbc.queryForObject("SELECT member_status FROM agent_task_member",String.class));
        assertEquals("failed",jdbc.queryForObject("SELECT status FROM agent_task_work_item",String.class));
    }
    @Test void cancellationAndAcceptanceRaceHaveOnlyOneBusinessAndFinancialTerminal() throws Exception {
        prepareCancellation();assign();prepareSubmitted();
        try(var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);
            var cancel=pool.submit(()->{start.await();try {cancellation.cancel(TENANT,CLIENT,OWNER,PAYER,TASK,4);return true;}catch(RuntimeException lost){return false;}});
            var accept=pool.submit(()->{start.await();try {decide("accepted");return true;}catch(RuntimeException lost){return false;}});
            start.countDown();assertNotEquals(cancel.get(20,TimeUnit.SECONDS),accept.get(20,TimeUnit.SECONDS));
        }
        assertEquals("cancelled".equals(taskState())?"CANCELLED":"ACCEPTED",jdbc.queryForObject("SELECT terminal_state FROM agent_task_execution_set WHERE delivery_state='PENDING' AND terminal_revision=5",String.class));
        assertEquals(2,count("economy_transaction"));
        if("cancelled".equals(taskState())) {assertEquals("RELEASED",charge());assertEquals(100,wallet(jdbc));}
        else {assertEquals("completed",taskState());assertEquals("CAPTURED",charge());assertEquals(50,wallet(jdbc));}
    }
    private void prepareRunning() {
        jdbc.update("UPDATE agent_task_meta SET reward_status='running',task_version=3,started_at=100");
        jdbc.update("UPDATE agent_task_member SET member_status='working',started_at=100");
        jdbc.update("UPDATE agent_task_work_item SET status='running',version=5,attempt_count=2,lease_token='work-lease',lease_until=200");
        jdbc.update("""
            INSERT INTO agent_personal_workspace_execution
            (execution_id,tenant_id,client_id,owner_jiacn,task_id,run_id,target_agent_id,instruction,output_content_mime_type,
             execution_state,grant_revision,idempotency_key,request_hash,created_at,execution_mode,execution_protocol_version,
             conversation_lease_token,conversation_lease_version,conversation_lease_expires_at,conversation_provider_started_at,conversation_provider_lease_version)
            VALUES ('exec',?,?,?,?, 'run',?,'fixture instruction','text/plain','QUEUED',1,'exec-key',REPEAT('a',64),100,'CONVERSATION',6,'paid-lease',3,200,150,3)
            """,TENANT,CLIENT,OWNER,TASK,AGENT);
        jdbc.update("UPDATE agent_personal_workspace_execution SET business_scope='TASK',conversation_id='42',assignment_revision=3 WHERE execution_id='exec'");
        jdbc.update("""
            INSERT INTO agent_skill_execution (tenant_id,client_id,owner_jiacn,execution_id,snapshot_json,snapshot_sha256,
             phase,version,effects_json,created_at,updated_at) VALUES (?,?,?,'exec',?,?,'RUNNING',2,'{}',100,100)
            """,TENANT,CLIENT,OWNER,NativeCancellationFixture.snapshot(TASK),NativeCancellationFixture.snapshotDigest(TASK));
    }
    private String executionState(){return jdbc.queryForObject("SELECT execution_state FROM agent_personal_workspace_execution WHERE execution_id='exec'",String.class);}
    private String skillPhase(){return jdbc.queryForObject("SELECT phase FROM agent_skill_execution WHERE execution_id='exec'",String.class);}

    @Test
    void initialCancellationRefundsOriginalPayerAndReplaysWithoutMoreWrites() throws Exception {
        prepareCancellation(); assign(); prepareRetry();
        var receipt = cancel();
        assertEquals("cancelled", receipt.status());
        assertCancelled();
        int events = count("agent_task_event");
        assertEquals(receipt, cancel());
        assertEquals(events, count("agent_task_event"));
        assertEquals(2, count("economy_transaction"));
        assertEquals(1, count("economy_task_quote"));
        assertEquals(1, count("agent_task_execution_grant"));
    }

    @Test
    void pendingBootstrapCancellationRefundsWithoutInventingAttemptAndCannotBeClaimed() throws Exception {
        prepareCancellation(); assign();
        var receipt = cancel(); assertCancelled();
        assertEquals(0, jdbc.queryForObject("SELECT attempt_count FROM agent_task_bounty_bootstrap_outbox", Integer.class));
        assertEquals(receipt, cancel());
        new TransactionTemplate(manager).executeWithoutResult(status ->
                assertNull(outbox.findClaimableForUpdate(TENANT, CLIENT, OWNER, System.currentTimeMillis())));
    }

    @Test
    void claimVersusCancellationAlwaysClosesUnadmittedIntentAndRefundsOnce() throws Exception {
        prepareCancellation(); assign();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var cancelled = pool.submit(() -> { start.await(); return cancel(); });
            var claimed = pool.submit(() -> {
                start.await(); return bootstrap.claimNext(SCOPE, "fixture-worker", System.currentTimeMillis());
            });
            start.countDown();
            assertEquals("cancelled", cancelled.get(20, TimeUnit.SECONDS).status());
            var oldClaim = claimed.get(20, TimeUnit.SECONDS);
            assertCancelled();
            if (oldClaim != null) assertStaleClaim(oldClaim);
            assertNull(bootstrap.claimNext(SCOPE, "next-worker", System.currentTimeMillis()));
        }
    }

    @Test
    void claimedCancellationRejectsLateAdmissionAndAllOldReconcileOutcomes() throws Exception {
        prepareCancellation(); assign();
        var claim = bootstrap.claimNext(SCOPE, "fixture-worker", System.currentTimeMillis());
        assertNotNull(claim); assertEquals(1, claim.claimAttempt());
        var receipt = cancel(); assertCancelled(); assertEquals(receipt, cancel());
        assertNull(jdbc.queryForObject("SELECT lease_owner FROM agent_task_bounty_bootstrap_outbox", String.class));
        assertNull(jdbc.queryForObject("SELECT lease_until FROM agent_task_bounty_bootstrap_outbox", Long.class));
        assertEquals(1, jdbc.queryForObject("SELECT attempt_count FROM agent_task_bounty_bootstrap_outbox", Integer.class));
        var denied = assertThrows(AgentTaskExecutionGrantException.class, () ->
                assignment.admit(SCOPE, TASK, claim.grantId(), claim.grantVersion(), claim.assignmentRevision(),
                        AGENT, claim.permittedOperation(), false));
        assertEquals(AgentTaskExecutionGrantException.Reason.CONFLICT, denied.reason());
        assertStaleClaim(claim); assertCancelled();
    }

    @Test
    void claimedCancellationFailureRestoresOriginalWorkerLeaseAndHold() throws Exception {
        prepareCancellation(); assign();
        var claim = bootstrap.claimNext(SCOPE, "fixture-worker", System.currentTimeMillis());
        assertNotNull(claim);
        failOccupation = true;
        assertThrows(InjectedFailure.class, this::cancel);
        assertEquals("HELD", charge()); assertEquals(50, wallet(jdbc));
        assertEquals("assigned", taskState());
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT state FROM agent_task_execution_grant", String.class));
        assertEquals("CLAIMED", jdbc.queryForObject("SELECT status FROM agent_task_bounty_bootstrap_outbox", String.class));
        assertEquals(claim.leaseOwner(), jdbc.queryForObject("SELECT lease_owner FROM agent_task_bounty_bootstrap_outbox", String.class));
        assertEquals(claim.outboxVersion(), jdbc.queryForObject("SELECT version FROM agent_task_bounty_bootstrap_outbox", Long.class));
        var retry = new AgentTaskBountyBootstrapReconcileDTO(claim.bootstrapId(), claim.outboxVersion(),
                claim.leaseOwner(), claim.claimAttempt(), AgentTaskBountyBootstrapReconcileDTO.Outcome.RETRYABLE_FAILURE,
                null, null, "FIXTURE_RETRY");
        assertEquals("RETRY", bootstrap.reconcile(SCOPE, retry, System.currentTimeMillis()).status());
        failOccupation = false; cancel(); assertCancelled();
    }

    private void assertStaleClaim(AgentTaskBountyBootstrapClaimDTO claim) {
        for (var outcome : AgentTaskBountyBootstrapReconcileDTO.Outcome.values()) {
            boolean admitted = outcome == AgentTaskBountyBootstrapReconcileDTO.Outcome.ADMITTED;
            var late = new AgentTaskBountyBootstrapReconcileDTO(claim.bootstrapId(), claim.outboxVersion(),
                    claim.leaseOwner(), claim.claimAttempt(), outcome, admitted ? "late-conversation" : null,
                    admitted ? "late-request" : null, admitted ? null : "LATE_FAILURE");
            assertThrows(IllegalStateException.class, () -> bootstrap.reconcile(SCOPE, late, System.currentTimeMillis()));
        }
    }

    @Test
    void cancellationEventFailureRestoresMoneyGrantBootstrapAndChildren() throws Exception {
        prepareCancellation(); assign(); prepareRetry(); clearInvocations(broker);
        int events = count("agent_task_event");
        failEvent = TaskEventType.TASK_CANCELLED;
        assertThrows(InjectedFailure.class, this::cancel);
        assertCancellationRolledBack();
        assertEquals(events, count("agent_task_event")); verifyNoInteractions(broker);
        failEvent = null; cancel(); assertCancelled();
    }

    @Test
    void finalOccupationFailureAndOuterCommitKeepCancellationAtomic() throws Exception {
        prepareCancellation(); assign(); prepareRetry(); clearInvocations(broker);
        failOccupation = true;
        assertThrows(InjectedFailure.class, this::cancel);
        assertCancellationRolledBack(); verifyNoInteractions(broker);
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM agent_task_execution_set",Integer.class));
        failOccupation = false;
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            cancel();
            assertEquals(100, wallet(jdbc)); assertEquals(50, wallet(independent));
            assertEquals("assigned", independent.queryForObject("SELECT reward_status FROM agent_task_meta", String.class));
            assertEquals("ACTIVE", independent.queryForObject("SELECT state FROM agent_task_execution_grant", String.class));
            verifyNoInteractions(broker);
        });
        assertCancelled();
    }

    @Test
    void concurrentInitialCancellationRefundsOnceUnderTaskRootLock() throws Exception {
        prepareCancellation(); assign(); prepareRetry();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { start.await(); return cancel(); });
            var b = pool.submit(() -> { start.await(); return cancel(); });
            start.countDown();
            assertEquals(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        }
        assertCancelled();
    }

    @Test
    void neverAssignedCancellationNeedsNoBillAndCreatesNoMoneyTransactions() throws Exception {
        prepareCancellation();
        assertEquals("cancelled", cancellation.cancel(TENANT, CLIENT, OWNER, PAYER, TASK, 0).status());
        assertEquals(100, wallet(jdbc)); assertEquals(0, count("economy_task_charge"));
        assertEquals(0, count("economy_transaction"));
    }

    @Test
    void assignedMissingBillCannotCancelOrReleaseUnattributedFunds() throws Exception {
        prepareCancellation(); assign(); prepareRetry();
        // Explicit corruption fixture: never infer a free bill or refund arbitrary escrow.
        jdbc.update("DELETE FROM economy_task_charge");
        var failure = assertThrows(cn.jia.economy.task.TaskBillingException.class, this::cancel);
        assertEquals(cn.jia.economy.task.TaskBillingException.Reason.RESERVATION_REQUIRED, failure.reason());
        assertEquals(50, wallet(jdbc)); assertEquals("assigned", taskState());
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT state FROM agent_task_execution_grant", String.class));
        assertEquals(1, count("economy_transaction"));
    }

    private void prepareCancellation() throws Exception {
        // All cancellation inventories and writes use the production mapper, not fake existence results.
        createTable(DDL.resolve("schema.sql"), "agent_runtime", true);
        createTable(DDL.resolve("schema.sql"), "agent_command_delivery", true);
        createTable(DDL.resolve("schema.sql"), "agent_outbox_event", true);
        for (String file : List.of("agent-task-funding-v0.sql", "agent-selected-output-finalization-v1.sql",
                "agent-task-provider-cost-consent-v1.sql")) {
            new ResourceDatabasePopulator(new FileSystemResource(DDL.resolve(file))).execute(jdbc.getDataSource());
        }
        // Inventory reads need the production execution/lease columns, not a full Runtime schema upgrade.
        // Its standalone initializer also validates columns owned by a later native-protocol initializer.
        for (String file : List.of("agent-personal-workspace-v1_11-executions.sql",
                "agent-personal-workspace-v1_13-task-execution.sql",
                "agent-personal-workspace-v1_13-task-publication.sql",
                "agent-personal-workspace-v1_14-conversation-execution.sql",
                "agent-personal-workspace-v1_14-conversation-output.sql",
                "agent-personal-workspace-v1_15-conversation-lease.sql",
                "agent-personal-workspace-v1_16-conversation-provider-start.sql",
                "agent-skill-execution-v1.sql", "agent-personal-workspace-native-v5.sql", "agent-personal-workspace-native-v6.sql","agent-personal-workspace-metered-capture-v2.sql","agent-native-capture-profile-v1.sql")) {
            new ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("db/" + file))
                    .execute(jdbc.getDataSource());
        }
        new cn.jia.agent.config.ControlledImageFollowupV3SchemaInitializer(jdbc).afterPropertiesSet();
        // Legacy Chat base-table DDL lives in the existing mapper fixture; it is read-only in this suite.
        createTable(Path.of("../../chat/jia-chat-mapper/src/test/resources/db/schema.sql"), "chat_conversation", false);
        new ResourceDatabasePopulator(new FileSystemResource(Path.of(
                "../../chat/jia-chat-mapper/src/main/resources/db/chat-deliberation-schema.sql")))
                .execute(jdbc.getDataSource());
        jdbc.update("INSERT INTO agent_runtime(agent_id,name,status,current_task_id,tenant_id,client_id,owner_jiacn) "
                + "VALUES(?,?,'busy',?,?,?,?)", AGENT, "fixture", TASK, TENANT, CLIENT, OWNER);
    }

    private void createTable(Path source, String table, boolean ifNotExists) throws Exception {
        String schema = Files.readString(source);
        int start = schema.indexOf("CREATE TABLE " + (ifNotExists ? "IF NOT EXISTS " : "") + table + " (");
        assertTrue(start >= 0, table);
        jdbc.execute(schema.substring(start, schema.indexOf(";\n", start)));
    }

    private void prepareRetry() {
        // Seed a failed, unleased bootstrap attempt; this suite does not claim relay/network coverage.
        jdbc.update("UPDATE agent_task_bounty_bootstrap_outbox SET status='RETRY',attempt_count=1,"
                + "next_retry_at=1,version=2,last_error_code='FIXTURE_ADMISSION_FAILURE'");
    }

    private AgentTaskCancellationService.Receipt cancel() {
        return cancellation.cancel(TENANT, CLIENT, OWNER, PAYER, TASK, 1);
    }

    private void assertCancelled() {
        assertEquals("cancelled", taskState()); assertEquals("RELEASED", charge());
        assertEquals(100, wallet(jdbc)); assertEquals(2, count("economy_transaction"));
        assertEquals("REVOKED", jdbc.queryForObject("SELECT state FROM agent_task_execution_grant", String.class));
        assertEquals("DEAD", jdbc.queryForObject("SELECT status FROM agent_task_bounty_bootstrap_outbox", String.class));
        assertEquals("TASK_CANCELLED", jdbc.queryForObject("SELECT last_error_code FROM agent_task_bounty_bootstrap_outbox", String.class));
        assertEquals("left", jdbc.queryForObject("SELECT member_status FROM agent_task_member", String.class));
        assertEquals("cancelled", jdbc.queryForObject("SELECT status FROM agent_task_work_item", String.class));
        assertNull(jdbc.queryForObject("SELECT current_task_id FROM agent_runtime", String.class));
        assertEquals("online", jdbc.queryForObject("SELECT status FROM agent_runtime", String.class));
        assertEquals(0L, jdbc.queryForObject("SELECT SUM(balance_wen) FROM economy_account WHERE owner_type='TASK'", Long.class));
    }

    private void assertCancellationRolledBack() {
        assertEquals("assigned", taskState()); assertEquals("HELD", charge());
        assertEquals(50, wallet(jdbc)); assertEquals(1, count("economy_transaction"));
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT state FROM agent_task_execution_grant", String.class));
        assertEquals("RETRY", jdbc.queryForObject("SELECT status FROM agent_task_bounty_bootstrap_outbox", String.class));
        assertEquals("accepted", jdbc.queryForObject("SELECT member_status FROM agent_task_member", String.class));
        assertEquals("ready", jdbc.queryForObject("SELECT status FROM agent_task_work_item", String.class));
        assertEquals(TASK, jdbc.queryForObject("SELECT current_task_id FROM agent_runtime", String.class));
        assertEquals("busy", jdbc.queryForObject("SELECT status FROM agent_runtime", String.class));
    }

    /** Actual production controllers, signed JWT, Q/R codecs, task SQL and money SQL.
     * Identity/material observations remain the declared fixture doubles; no model/relay is run. */
    @Test void realHttpAssignmentAcceptanceAndReplayHaveOneFinancialEffect() throws Exception {
        startHttp();
        pointHttp(200); pointHttp(200);
        httpPost("/point-and-deliberate", pointBody().replace("recommendation-1", "other-recommendation"), "assign-http", true, 409);
        httpPost("/point-and-deliberate", pointBody().replace(quoteId, "other-quote"), "assign-http", true, 409);
        assertEquals(50, wallet(independent)); assertEquals("HELD", charge());
        assertEquals(1, count("economy_transaction")); assertEquals(1, count("agent_task_execution_grant"));
        prepareSubmitted();
        var batch = deliveryReads.listForTaskOwner(TENANT, CLIENT, OWNER, TASK).getFirst();
        String decision = ChatDeliveryReviewContract.json(ChatDeliveryReviewContract.body(
                new ChatDeliveryReviewContract.Accept("delivery-1", 4, 0, 1,
                        ChatDeliveryReviewContract.batchDigest(TASK, batch))));
        httpPost("/delivery-review-operations", decision, "accept-http", true, 200);
        httpPost("/delivery-review-operations", decision, "accept-http", true, 200);
        assertEquals(1, count("chat_task_delivery_review_operation"));
        assertEquals("COMMITTED", jdbc.queryForObject("SELECT state FROM chat_task_delivery_review_operation", String.class));
        assertEquals("CAPTURED", charge()); assertEquals("completed", taskState());
        assertEquals(50, wallet(independent)); assertEquals(2, count("economy_transaction"));
    }

    @Test void realHttpCancellationReleasesOnceAndRejectsUnauthenticatedMutation() throws Exception {
        prepareCancellation(); startHttp();
        httpPost("/point-and-deliberate", pointBody(), "assign-http", false, 401);
        assertAssignmentAbsent(); pointHttp(200); prepareRetry();
        String body = "{\"expectedTaskVersion\":\"1\"}";
        httpPost("/cancel", body, "cancel-http", true, 200);
        httpPost("/cancel", body, "cancel-http", true, 200);
        assertCancelled();
    }

    @Test void realHttpInsufficientBalanceAndFailedAssignmentNeverPartiallyCommit() throws Exception {
        startHttp(); jdbc.update("UPDATE economy_account SET balance_wen=49 WHERE account_id='wallet'");
        pointHttp(409);
        assertEquals(49, wallet(independent)); assertEquals("open", taskState());
        assertEquals(0, count("economy_transaction")); assertEquals(0, count("agent_task_execution_grant"));
        jdbc.update("UPDATE economy_account SET balance_wen=100 WHERE account_id='wallet'");
        failBootstrap = true; pointHttp(503); assertAssignmentAbsent();
        failBootstrap = false; pointHttp(200);
        assertEquals(50, wallet(independent)); assertEquals("HELD", charge());
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "CYF_BILLING_BROWSER", matches = "true")
    void browserSelectsAssigneeAndReservesRealWallet() throws Exception {
        startHttp();
        runBrowser("reserve");
        assertEquals(50, wallet(independent)); assertEquals("HELD", charge());
        assertEquals("assigned", taskState()); assertEquals(1, count("economy_transaction"));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "CYF_BILLING_BROWSER", matches = "true")
    void browserInsufficientFundsDoesNotAssignOrReserve() throws Exception {
        jdbc.update("UPDATE economy_account SET balance_wen=49 WHERE account_id='wallet'");
        startHttp();
        runBrowser("insufficient");
        assertEquals(49, wallet(independent)); assertEquals("open", taskState());
        assertEquals(0, count("economy_task_charge")); assertEquals(0, count("economy_transaction"));
    }

    private void runBrowser(String scenario) throws Exception {
        Path evidence = Path.of(System.getenv("CYF_BILLING_BROWSER_OUTPUT")).resolve(scenario);
        Files.createDirectories(evidence);
        Path input = httpDirectory.resolve("browser-input.json");
        Files.writeString(input, new ObjectMapper().writeValueAsString(Map.of(
                "origin", origin, "token", httpToken(), "quoteId", quoteId,
                "taskId", TASK, "agentId", AGENT, "owner", OWNER, "payer", PAYER,
                "tenant", TENANT, "scenario", scenario)));
        Files.setPosixFilePermissions(input, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        var process = new ProcessBuilder("node", "src/test/resources/browser/task-billing.cjs",
                input.toString(), evidence.toString()).redirectErrorStream(true)
                .redirectOutput(evidence.resolve("browser.log").toFile()).start();
        try {
            assertTrue(process.waitFor(300, TimeUnit.SECONDS), "isolated browser fixture did not finish");
            assertEquals(0, process.exitValue(), Files.readString(evidence.resolve("browser.log")));
        } finally {
            if (process.isAlive()) {
                // Capture descendants before terminating Node so a failed test cannot orphan Chromium.
                var descendants = process.descendants().toList();
                process.destroy();
                if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly().waitFor();
                descendants.forEach(handle -> { if (handle.isAlive()) handle.destroy(); });
            }
            Files.deleteIfExists(input);
        }
    }

    private String pointBody() {
        return "{\"targetAgentId\":\"" + AGENT + "\",\"expectedTaskVersion\":\"0\",\"requirementRevision\":\"1\","
                + "\"quoteId\":\"" + quoteId + "\",\"recommendationId\":\"recommendation-1\"}";
    }
    private void pointHttp(int expected) throws Exception { httpPost("/point-and-deliberate", pointBody(), "assign-http", true, expected); }
    private cn.jia.agent.api.IndependentTaskQuoteController independentQuoteController;
    /** The archived sample, cutoff, candidate and terms are deliberately synthetic. Test real HTTP/SQL/commit/replay, not accuracy. */
    private void verifyHistoryQuoteHttp(TaskBillingService.Task newTask,cn.jia.economy.task.TaskBudgetEstimator estimator,
                                        cn.jia.economy.task.TaskBudgetEstimator.Terms terms) throws Exception {
        String id=newTask.taskId();jdbc.update("INSERT INTO agent_task_meta(task_id,owner_jiacn,tenant_id,client_id) VALUES(?,?,?,?)",id,OWNER,TENANT,CLIENT);
        for(String file:List.of("agent-task-requirement-snapshot-v1.sql","agent-task-current-requirement-v1.sql"))
            new ResourceDatabasePopulator(new FileSystemResource(DDL.resolve(file))).execute(jdbc.getDataSource());
        var req=new AgentTaskRequirementSnapshotServiceImpl(jdbc,roots);
        var requirement=roots.executeWithLockedTaskRootInOwnerScope(TENANT,CLIENT,OWNER,id,r->req.captureOnCreate(SCOPE,id,"Fixture title","Fixture description"));
        var inputs=roots.executeWithLockedTaskRootInOwnerScope(TENANT,CLIENT,OWNER,id,r->assignment.snapshotTaskInputsWithinLockedTask(SCOPE,id));
        var agents=mock(cn.jia.agent.service.AgentExternalServicePolicyService.class);
        var targets=List.of(new cn.jia.agent.service.AgentExternalServicePolicyService.QuoteTarget(AGENT,OWNER,1,0));
        when(agents.quoteTargets(eq(SCOPE),eq(id),eq(List.of(AGENT)),anyBoolean())).thenReturn(targets);
        var source=mock(cn.jia.agent.service.recommendation.ProspectiveTaskExecution.class);
        var subject=new cn.jia.agent.service.recommendation.ProspectiveTaskExecution.Inputs(newTask,"0",1,requirement.sha256(),inputs.digest());
        var proofs=Map.of(AGENT,new cn.jia.agent.service.recommendation.ProspectiveTaskExecution.Evidence("fixture-fixed",terms));
        when(source.observe(subject,targets)).thenReturn(proofs);when(source.current(subject,targets,proofs)).thenReturn(true);
        var calls=new java.util.concurrent.atomic.AtomicInteger();var encoder=new cn.jia.agent.service.TaskFeatureEncoder(){
            public Identity identity(){return new Identity("cfg","fixture",1024);}
            public Vector encode(String text){calls.incrementAndGet();float[] vec=new float[1024];vec[0]=1;return new Vector(identity(),vec);}
        };
        var profile=new QuoteProfile();boolean diagnostic=Boolean.getBoolean("cyf.measuredBatchQuoteLoad");
        independentQuoteController=cn.jia.agent.service.recommendation.QuoteHttpTestWiring.controller(roots,batches,req,assignment,agents,
                mock(cn.jia.agent.service.recommendation.RecommendationMaterials.class),encoder,source,diagnostic?profile.wrap(cn.jia.economy.task.TaskBudgetEstimator.class,estimator,java.util.Set.of("prepare","lockSelected")):estimator,diagnostic?profile.wrap(TaskBillingService.class,billing,java.util.Set.of("storeMeasuredBatch")):billing);
        startHttp();int money=count("economy_transaction"),batchCount=count("economy_task_quote_batch"),fixed=count("economy_task_quote");
        var hot=new java.util.ArrayList<Long>();String firstId=null;long cold=0,replay=0;
        String token=httpToken();try(var client=HttpClient.newHttpClient()){
            for(int i=0;i<32;i++){
                String key=i==31?"fixture-0":"fixture-"+i;
                var request=HttpRequest.newBuilder(URI.create(origin+"/agent/tasks/"+id+"/quotes")).header("Content-Type","application/json")
                    .header("X-Request-Id","quote-http").header("Authorization","Bearer "+token).header("Idempotency-Key",key)
                    .POST(HttpRequest.BodyPublishers.ofString("{\"protocolVersion\":1,\"requestId\":\"quote-http\",\"data\":{\"expectedTaskVersion\":\"0\",\"agentIds\":[\""+AGENT+"\"]}}" )).build();
                long t=System.nanoTime();var response=client.send(request,HttpResponse.BodyHandlers.ofString());long elapsed=System.nanoTime()-t;
                assertEquals(200,response.statusCode(),response.body());var envelope=new ObjectMapper().readTree(response.body());assertEquals("E0",envelope.path("code").asText());
                var item=envelope.at("/data/quotes/0");assertEquals("AVAILABLE",item.path("state").asText());assertEquals("65",item.at("/amount/amountMinor").asText());
                if(i==0){firstId=item.path("quoteId").asText();assertFalse(firstId.isBlank());cold=elapsed;}
                else if(i==31){assertEquals(firstId,item.path("quoteId").asText());replay=elapsed;}
                else {assertNotEquals(firstId,item.path("quoteId").asText());hot.add(elapsed);}
            }
        }
        assertEquals(1,calls.get());verify(source,times(31)).observe(subject,targets);verify(source,times(31)).current(subject,targets,proofs);
        assertEquals(batchCount+31,count("economy_task_quote_batch"));assertEquals(fixed+31,count("economy_task_quote"));assertEquals(money,count("economy_transaction"));
        hot.sort(Long::compare);System.out.printf(java.util.Locale.ROOT,"MEASURED_QUOTE_HTTP coldFixtureMs=%.3f hotCount=30 p50ms=%.3f p95ms=%.3f maxms=%.3f replayMs=%.3f fixtureEncodes=%d%n",
                cold/1e6,hot.get(14)/1e6,hot.get(28)/1e6,hot.getLast()/1e6,replay/1e6,calls.get());
        if(Boolean.getBoolean("cyf.measuredBatchQuoteLoad"))measureBatchQuoteHttp(id,token,agents,source,subject,terms,calls,profile);
    }

    /** Test-scoped attribution only; no application counters, raw SQL/inputs, or per-row log lines. */
    private static final class QuoteProfile {
        final java.util.Map<String,java.util.concurrent.atomic.LongAdder> calls=new java.util.concurrent.ConcurrentHashMap<>(),nanos=new java.util.concurrent.ConcurrentHashMap<>();
        <T>T wrap(Class<T> type,T delegate,java.util.Set<String> names){
            var factory=new ProxyFactory();factory.setInterfaces(type);factory.setTarget(delegate);
            factory.addAdvice((org.aopalliance.intercept.MethodInterceptor)i->{String name=i.getMethod().getName();if(!names.contains(name))return i.proceed();long t=System.nanoTime();try{return i.proceed();}finally{
                nanos.computeIfAbsent(name,k->new java.util.concurrent.atomic.LongAdder()).add(System.nanoTime()-t);calls.computeIfAbsent(name,k->new java.util.concurrent.atomic.LongAdder()).increment();}});
            return type.cast(factory.getProxy());
        }
        long count(String name){return calls.getOrDefault(name,new java.util.concurrent.atomic.LongAdder()).sum();}
        double ms(String name){return nanos.getOrDefault(name,new java.util.concurrent.atomic.LongAdder()).sum()/1e6;}
        void reset(){calls.clear();nanos.clear();}
    }
    private record WorkspaceRows(PersonalWorkspaceDao files,PersonalWorkspaceTaskLinkDao links) {}
    private WorkspaceRows quoteWorkspace() throws Exception {
        for(String schema:List.of("agent-personal-workspace-v1.sql","agent-personal-workspace-v1_11-task-links.sql"))
            new ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("db/"+schema)).execute(jdbc.getDataSource());
        var config=new MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);
        for(var m:List.of(PersonalWorkspaceFileMapper.class,PersonalWorkspaceVersionMapper.class,PersonalWorkspaceOperationMapper.class,PersonalWorkspaceTaskFileLinkMapper.class))config.addMapper(m);
        var factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(jdbc.getDataSource());factory.setConfiguration(config);
        var sql=new SqlSessionTemplate(factory.getObject());
        var files=new PersonalWorkspaceDaoImpl(sql.getMapper(PersonalWorkspaceFileMapper.class),sql.getMapper(PersonalWorkspaceVersionMapper.class),sql.getMapper(PersonalWorkspaceOperationMapper.class));
        var links=new PersonalWorkspaceTaskLinkDaoImpl(sql.getMapper(PersonalWorkspaceTaskFileLinkMapper.class));
        return new WorkspaceRows(files,links);
    }
    private void attachQuoteFile(int i) { attachQuoteFile(TASK,i); }
    private void attachQuoteFile(String task,int i) {
        String id="file-"+i;
        jdbc.update("INSERT INTO agent_personal_workspace_file(file_id,owner_jiacn,source_kind,display_name,media_family,state,metadata_revision,latest_version,created_at,tenant_id,client_id) VALUES(?,?,'UPLOAD',?,'DOCUMENT','ACTIVE',1,1,1,?,?)",id,OWNER,id,TENANT,CLIENT);
        jdbc.update("INSERT INTO agent_personal_workspace_file_version(file_id,owner_jiacn,version,original_filename,content_mime_type,byte_length,content_hash,storage_uri,created_at,tenant_id,client_id) VALUES(?,?,1,?,'text/plain',7,?,'private:v',1,?,?)",id,OWNER,id,"b".repeat(64),TENANT,CLIENT);
        jdbc.update("INSERT INTO agent_personal_workspace_task_file_link(relation_id,owner_jiacn,task_id,file_id,file_version,link_role,link_state,relation_revision,created_at,tenant_id,client_id) VALUES(?,?,?,?,1,'INPUT','ACTIVE',1,1,?,?)",id,OWNER,task,id,TENANT,CLIENT);
    }
    @Test void quoteCatalogueMeasuresRealLockedInputsAndRejectsConcurrentTrash() throws Exception {
        var workspace=quoteWorkspace();var links=workspace.links();var files=workspace.files();var profile=new QuoteProfile();
        ReflectionTestUtils.setField(assignment,"taskLinks",profile.wrap(PersonalWorkspaceTaskLinkDao.class,links,java.util.Set.of("list","lockBySelection")));
        ReflectionTestUtils.setField(assignment,"workspace",profile.wrap(PersonalWorkspaceDao.class,files,java.util.Set.of("lockFile","selectedMetadata")));
        java.util.function.Supplier<AgentTaskExecutionGrantService.InputCatalogue> read=()->locked(()->assignment.snapshotTaskInputsWithinLockedTask(SCOPE,TASK));
        for(int i=0;i<33;i++) {
            attachQuoteFile(i);
            if(i!=0 && i!=31) continue;
            int n=i+1;var expected=read.get();assertEquals(n,expected.inputs().size());profile.reset();long[] ms=new long[30];
            for(int k=0;k<ms.length;k++){long t=System.nanoTime();assertEquals(expected,read.get());ms[k]=System.nanoTime()-t;}
            java.util.Arrays.sort(ms);for(String method:List.of("lockFile","lockBySelection"))assertEquals(30L*n,profile.count(method));
            assertEquals(30,profile.count("list"));assertEquals(30,profile.count("selectedMetadata"));
            System.out.printf(java.util.Locale.ROOT,"D76 catalogue n=%d rounds=30 txnP95Ms=%.3f listMs=%.3f fileLockMs=%.3f relationLockMs=%.3f versionMs=%.3f%n",n,ms[28]/1e6,profile.ms("list"),profile.ms("lockFile"),profile.ms("lockBySelection"),profile.ms("selectedMetadata"));
        }
        assertEquals(AgentTaskExecutionGrantException.Reason.BAD_REQUEST,assertThrows(AgentTaskExecutionGrantException.class,read::get).reason());
        jdbc.update("UPDATE agent_personal_workspace_task_file_link SET link_state='DETACHED' WHERE file_id='file-32'");
        var beforeMissing=read.get();
        jdbc.update("UPDATE agent_personal_workspace_task_file_link SET file_version=2 WHERE file_id='file-30'");
        assertEquals(AgentTaskExecutionGrantException.Reason.NOT_FOUND,assertThrows(AgentTaskExecutionGrantException.class,read::get).reason());
        jdbc.update("UPDATE agent_personal_workspace_task_file_link SET file_version=1 WHERE file_id='file-30'");
        assertEquals(beforeMissing,read.get());
        try(var writer=independent.getDataSource().getConnection();var worker=Executors.newSingleThreadExecutor()) {
            writer.setAutoCommit(false);
            try {
                try(var stmt=writer.createStatement()){assertEquals(1,stmt.executeUpdate("UPDATE agent_personal_workspace_file SET state='TRASHED' WHERE file_id='file-0'"));}
                var pending=worker.submit(()->assertThrows(AgentTaskExecutionGrantException.class,read::get).reason());
                long start=System.nanoTime();boolean waiting=false;
                while(System.nanoTime()-start<TimeUnit.SECONDS.toNanos(5)) {
                    if(admin.queryForObject("SELECT COUNT(*) FROM performance_schema.data_lock_waits w JOIN performance_schema.data_locks l ON l.ENGINE=w.ENGINE AND l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID WHERE l.OBJECT_SCHEMA=? AND l.OBJECT_NAME='agent_personal_workspace_file'",Integer.class,database)>0){waiting=true;break;}
                    if(pending.isDone())break;Thread.sleep(5);
                }
                assertTrue(waiting,"no observed row-lock wait on this fixture's workspace file");writer.commit();
                assertEquals(AgentTaskExecutionGrantException.Reason.NOT_FOUND,pending.get(5,TimeUnit.SECONDS));
            } finally {writer.rollback();}
        }
        assertEquals(0,count("economy_transaction"));
    }
    @Test void signedMaterialQuoteWithoutDispatchConfigurationDistinguishesRetryStaleAndReplay() throws Exception {
        var workspace=quoteWorkspace();var authority=quoteAndRecommendationAuthority();
        ReflectionTestUtils.setField(assignment,"taskLinks",workspace.links());ReflectionTestUtils.setField(assignment,"workspace",workspace.files());
        for(int i=0;i<32;i++) attachQuoteFile(i);
        String agent="agt_00000000000000000000000000000001";
        jdbc.update("INSERT INTO agent_persona_binding(jiacn,persona_code,agent_id,bound_at,status,tenant_id,client_id) VALUES(?,?,?,1,1,?,?)",OWNER,"quote-fixture",agent,TENANT,CLIENT);
        long binding=jdbc.queryForObject("SELECT id FROM agent_persona_binding WHERE agent_id=?",Long.class,agent);
        jdbc.update("INSERT INTO agent_identity_registry(canonical_agent_id,canonical_type,lifecycle_status,client_id,owner_jiacn,tenant_id,binding_id,provisioned_at,activated_at,audit_reason,create_time,update_time) VALUES (?,'OPAQUE','ACTIVE',?,?,?,?,1,1,'quote-fixture',1,1)",agent,CLIENT,OWNER,TENANT,binding);
        locked(()->authority.req().captureOnCreate(SCOPE,TASK,"有附件的事项","核对资料"));
        var storage=mock(PersonalWorkspaceStorage.class);var writes=mock(PersonalWorkspaceWriteService.class);
        var materials=new cn.jia.agent.service.recommendation.RecommendationMaterials(proxy(new PersonalWorkspaceTaskLinkServiceImpl(workspace.links()),PersonalWorkspaceTaskLinkService.class),new PersonalWorkspaceServiceImpl(workspace.files(),storage,writes));
        var calls=new java.util.concurrent.atomic.AtomicInteger();var encoder=new TaskFeatureEncoder(){
            public Identity identity(){return new Identity("cfg","fixture",1024);}
            public Vector encode(String text){
                assertTrue(text.contains("file-31"));assertFalse(text.contains("private:v"));assertFalse(text.contains("b".repeat(64)));
                int n=calls.incrementAndGet();if(n==1)throw new Unavailable();
                if(n==2)assertEquals(1,independent.update("UPDATE agent_personal_workspace_file SET display_name='修订后的名称',metadata_revision=2 WHERE file_id='file-0'"));
                if(n==3)assertTrue(text.contains("修订后的名称"));
                float[] vec=new float[1024];vec[0]=1;return new Vector(identity(),vec);
            }
        };
        var engine=mock(cn.jia.economy.service.impl.MeasuredHistoryEstimateEngine.class);
        var estimates=proxy(new cn.jia.economy.service.impl.TaskBudgetEstimatorService(calibrationCatalog,historyCatalog,engine),cn.jia.economy.task.TaskBudgetEstimator.class);
        independentQuoteController=cn.jia.agent.service.recommendation.QuoteHttpTestWiring.controller(roots,batches,authority.req(),assignment,authority.policies(),materials,encoder,null,estimates,billing);
        startHttp();int fixed=count("economy_task_quote"),batchCount=count("economy_task_quote_batch"),money=count("economy_transaction");long balance=wallet(jdbc);
        var json=new ObjectMapper();String token=httpToken();try(var client=HttpClient.newHttpClient()){
            class Post {tools.jackson.databind.JsonNode post(String id,String key,int status,String code)throws Exception{
                var body=json.writeValueAsString(Map.of("protocolVersion",1,"requestId","d77","data",Map.of("expectedTaskVersion","0","agentIds",List.of(id))));
                var request=HttpRequest.newBuilder(URI.create(origin+"/agent/tasks/"+TASK+"/quotes")).header("Content-Type","application/json").header("Authorization","Bearer "+token).header("X-Request-Id","d77").header("Idempotency-Key",key).POST(HttpRequest.BodyPublishers.ofString(body)).build();
                var response=client.send(request,HttpResponse.BodyHandlers.ofString());assertEquals(status,response.statusCode(),response.body());var env=json.readTree(response.body());assertEquals(code,env.path("code").asText());return env.path("data");
            }}var http=new Post();String key="same-intent";
            http.post("not-visible","denied",404,"QUOTE_NOT_FOUND");assertEquals(0,calls.get());
            http.post(agent,key,503,"QUOTE_FEATURE_UNAVAILABLE");assertEquals(batchCount,count("economy_task_quote_batch"));
            http.post(agent,key,409,"QUOTE_STALE");assertEquals(batchCount,count("economy_task_quote_batch"));
            var original=http.post(agent,key,200,"E0");var item=original.path("quotes").get(0);assertEquals(agent,item.path("agentId").asText());
            assertEquals("UNAVAILABLE",item.path("state").asText());assertEquals("BILLING_ROUTE_MISSING",item.path("unavailableReason").asText());
            assertFalse(item.path("amount").isObject());assertFalse(item.path("quoteId").isString());assertEquals(3,calls.get());
            assertEquals(1,jdbc.update("UPDATE agent_personal_workspace_file SET state='TRASHED' WHERE file_id='file-0'"));
            assertEquals(original,http.post(agent,key,200,"E0"));http.post(agent,"fresh-intent",404,"QUOTE_NOT_FOUND");
        }
        assertEquals(3,calls.get());assertEquals(batchCount+1,count("economy_task_quote_batch"));assertEquals(fixed,count("economy_task_quote"));assertEquals(money,count("economy_transaction"));assertEquals(balance,wallet(jdbc));verifyNoInteractions(engine,storage,writes);
    }
    private record QuoteWrites(long calls,long rows,long dbPicos,long bytes){}
    private QuoteWrites readQuoteWrites(String task){
        var w=admin.queryForObject("""
            SELECT COALESCE(SUM(COUNT_STAR),0),COALESCE(SUM(SUM_ROWS_AFFECTED),0),COALESCE(SUM(SUM_TIMER_WAIT),0)
            FROM performance_schema.events_statements_summary_by_digest WHERE SCHEMA_NAME=? AND DIGEST_TEXT LIKE 'INSERT INTO `economy_task_quote` %'
            """,(r,n)->new long[]{r.getLong(1),r.getLong(2),r.getLong(3)},database);
        long bytes=jdbc.queryForObject("SELECT COALESCE(SUM(OCTET_LENGTH(snapshot_json)),0) FROM economy_task_quote WHERE tenant_id=? AND client_id=? AND owner_jiacn=? AND task_id=?",Long.class,TENANT,CLIENT,OWNER,task);
        return new QuoteWrites(w[0],w[1],w[2],bytes);
    }
    // Same feature cache as the preceding single-candidate request. Terms and roster are fixture inputs;
    // estimation, reference repricing, individual bindings, atomic persistence and replay are not mocked.
    private void measureBatchQuoteHttp(String task,String token,cn.jia.agent.service.AgentExternalServicePolicyService agents,
            cn.jia.agent.service.recommendation.ProspectiveTaskExecution source,cn.jia.agent.service.recommendation.ProspectiveTaskExecution.Inputs subject,
            cn.jia.economy.task.TaskBudgetEstimator.Terms terms,java.util.concurrent.atomic.AtomicInteger encodes,QuoteProfile profile)throws Exception{
        profile.reset();var writes=readQuoteWrites(task);
        int money=count("economy_transaction"),batchCount=count("economy_task_quote_batch"),fixed=count("economy_task_quote");
        var roster=java.util.stream.IntStream.range(0,108).mapToObj(i->new cn.jia.agent.service.AgentExternalServicePolicyService.QuoteTarget(String.format(java.util.Locale.ROOT,"q-target-%03d",i),OWNER,100+i,0)).toList();
        var ids=roster.stream().map(c->c.agent()).toList();var noConfig=ids.getLast();var proofs=new java.util.HashMap<String,cn.jia.agent.service.recommendation.ProspectiveTaskExecution.Evidence>();
        for(var c:roster)if(!c.agent().equals(noConfig))proofs.put(c.agent(),new cn.jia.agent.service.recommendation.ProspectiveTaskExecution.Evidence("frozen-terms",terms));
        when(agents.quoteTargets(eq(SCOPE),eq(task),eq(ids),anyBoolean())).thenReturn(roster);when(source.observe(subject,roster)).thenReturn(proofs);when(source.current(subject,roster,proofs)).thenReturn(true);
        var mapper=new ObjectMapper();String body=mapper.writeValueAsString(Map.of("protocolVersion",1,"requestId","multi-quote","data",Map.of("expectedTaskVersion","0","agentIds",ids)));
        var samples=new java.util.ArrayList<Long>();var all=new java.util.HashSet<String>();tools.jackson.databind.JsonNode first=null;long replay=0;
        try(var client=HttpClient.newHttpClient()){
            for(int i=0;i<=30;i++){
                int key=i==30?0:i;var req=HttpRequest.newBuilder(URI.create(origin+"/agent/tasks/"+task+"/quotes")).header("Content-Type","application/json").header("Authorization","Bearer "+token).header("Idempotency-Key","quote-batch-"+key).header("X-Request-Id","multi-quote").POST(HttpRequest.BodyPublishers.ofString(body)).build();
                long t=System.nanoTime();var res=client.send(req,HttpResponse.BodyHandlers.ofString());long elapsed=System.nanoTime()-t;
                assertEquals(200,res.statusCode());var env=mapper.readTree(res.body());assertEquals("E0",env.path("code").asText());var data=env.path("data");if(i==30){replay=elapsed;assertEquals(first,data);break;}
                samples.add(elapsed);if(i==0)first=data;var items=data.path("quotes");assertEquals(108,items.size());String batch=items.get(0).path("recommendationId").asText();assertFalse(batch.isBlank());
                for(int j=0;j<ids.size();j++){var item=items.get(j);assertEquals(ids.get(j),item.path("agentId").asText());assertEquals(batch,item.path("recommendationId").asText());
                    if(j+1==ids.size()){assertEquals("UNAVAILABLE",item.path("state").asText());assertEquals("BILLING_ROUTE_MISSING",item.path("unavailableReason").asText());assertFalse(item.path("amount").isObject());}
                    else{assertEquals("AVAILABLE",item.path("state").asText());assertEquals("65",item.at("/amount/amountMinor").asText());String q=item.path("quoteId").asText();assertFalse(q.isBlank());assertTrue(all.add(q));}
                }
            }
        }
        var end=readQuoteWrites(task);long bytes=end.bytes()-writes.bytes();
        System.out.printf(java.util.Locale.ROOT,"BATCH_QUOTE_COST batches=30 candidates=108 prepareCalls=%d prepareMs=%.3f lockSelectedCalls=%d lockSelectedMs=%.3f storeCalls=%d storeMs=%.3f insertCalls=%d rows=%d insertMs=%.3f snapshotBytes=%d%n",profile.count("prepare"),profile.ms("prepare"),profile.count("lockSelected"),profile.ms("lockSelected"),profile.count("storeMeasuredBatch"),profile.ms("storeMeasuredBatch"),end.calls()-writes.calls(),end.rows()-writes.rows(),(end.dbPicos()-writes.dbPicos())/1e9,bytes);
        assertEquals(60,profile.count("prepare"));assertEquals(30,profile.count("lockSelected"));assertEquals(30,profile.count("storeMeasuredBatch"));assertTrue(end.calls()-writes.calls()<3210);assertEquals(3210,end.rows()-writes.rows());assertTrue(bytes>0);
        assertEquals(3210,all.size());assertEquals(1,encodes.get());assertEquals(batchCount+30,count("economy_task_quote_batch"));assertEquals(fixed+3210,count("economy_task_quote"));assertEquals(money,count("economy_transaction"));
        verify(source,times(30)).observe(subject,roster);verify(source,times(30)).current(subject,roster,proofs);
        samples.sort(Long::compare);System.out.printf(java.util.Locale.ROOT,"MEASURED_BATCH_QUOTE_HTTP candidates=108 available=107 hotN=30 p50ms=%.3f p95ms=%.3f maxms=%.3f replayMs=%.3f persistedQuotes=%d%n",samples.get(14)/1e6,samples.get(28)/1e6,samples.getLast()/1e6,replay/1e6,all.size());
    }

    private SqlSessionTemplate recommendationSql;
    private cn.jia.agent.api.AssigneeRecommendationController recommendationController;
    private <T> T withMapper(T dao,Class<?> mapper){ReflectionTestUtils.setField(dao,"baseMapper",recommendationSql.getMapper(mapper));return dao;}
    private record Authority(AgentServiceImpl agents,AgentExternalServicePolicyService policies,AgentTaskRequirementSnapshotServiceImpl req) {}
    private Authority quoteAndRecommendationAuthority() throws Exception {
        var ds=jdbc.getDataSource();String schema=Files.readString(DDL.resolve("schema.sql"));int start=schema.indexOf("CREATE TABLE IF NOT EXISTS agent_runtime (");assertTrue(start>=0);jdbc.execute(schema.substring(start,schema.indexOf(";\n",start)));
        var identityDdl=Files.readString(DDL.resolve("agent-identity-schema.sql"));int boundary=identityDdl.indexOf("-- A02 uses a temporary assertion procedure");assertTrue(boundary>0);
        new ResourceDatabasePopulator(new org.springframework.core.io.ByteArrayResource(identityDdl.substring(0,boundary).getBytes(StandardCharsets.UTF_8))).execute(ds);
        new cn.jia.agent.config.AgentExternalServicePolicySchemaInitializer(jdbc).afterPropertiesSet();
        for(String file:List.of("agent-task-requirement-snapshot-v1.sql","agent-task-current-requirement-v1.sql"))new ResourceDatabasePopulator(new FileSystemResource(DDL.resolve(file))).execute(ds);
        var meta=withMapper(new AgentTaskMetaDaoImpl(),AgentTaskMetaMapper.class);
        var runtimes=withMapper(new AgentRuntimeDaoImpl(),AgentRuntimeMapper.class);
        var bindings=withMapper(new AgentPersonaBindingDaoImpl(),AgentPersonaBindingMapper.class);
        var identities=proxy(new AgentIdentityServiceImpl(withMapper(new AgentIdentityRegistryDaoImpl(),AgentIdentityRegistryMapper.class),withMapper(new AgentIdentityAliasDaoImpl(),AgentIdentityAliasMapper.class),bindings),AgentIdentityService.class);
        var policies=proxy(new AgentExternalServicePolicyServiceImpl(new AgentExternalServicePolicyDaoImpl(recommendationSql.getMapper(AgentExternalServicePolicyMapper.class)),identities,meta,new AgentTaskExecutionGrantDaoImpl(recommendationSql.getMapper(AgentTaskExecutionGrantMapper.class)),runtimes),AgentExternalServicePolicyService.class);
        var agents=new AgentServiceImpl(runtimes,identities,null,bindings,meta,null,null,null,null,null,null,null,null,null,null,roots,null,new cn.jia.core.security.FixedTenantAdmissionPolicy(TENANT,CLIENT));
        var req=new AgentTaskRequirementSnapshotServiceImpl(jdbc,roots);agents.setRequirementSnapshots(req);agents.setExternalServicePolicies(policies);
        return new Authority(agents,policies,req);
    }
    @Test @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="cyf.capabilityEsLoad",matches="true")
    void signedRecommendationWithRealCandidateAuthorityAndIsolatedEs() throws Exception {
        var authority=quoteAndRecommendationAuthority();var agents=authority.agents();var req=authority.req();
        var installed=new cn.jia.agent.skill.InstalledPurposeHttpFixture(jdbc,manager,TENANT,CLIENT,OWNER);
        var skills=installed.skills;
        var workspace=quoteWorkspace();var storage=mock(PersonalWorkspaceStorage.class);var writes=mock(PersonalWorkspaceWriteService.class);
        var materials=new cn.jia.agent.service.recommendation.RecommendationMaterials(proxy(new PersonalWorkspaceTaskLinkServiceImpl(workspace.links()),PersonalWorkspaceTaskLinkService.class),new PersonalWorkspaceServiceImpl(workspace.files(),storage,writes));
        try(var es=new cn.jia.agent.service.recommendation.RecommendationHttpTestWiring(agents,skills,materials,roots)){
            recommendationController=es.controller;startHttp();var expected=new java.util.LinkedHashSet<String>();
            for(int n:List.of(1,108)){
                String task="rec-http-"+n;jdbc.update("INSERT INTO agent_task_meta(task_id,owner_jiacn,tenant_id,client_id) VALUES(?,?,?,?)",task,OWNER,TENANT,CLIENT);
                roots.executeWithLockedTaskRootInOwnerScope(TENANT,CLIENT,OWNER,task,r->req.captureOnCreate(SCOPE,task,"合同审查 "+n,"核对合同条款"));
                attachQuoteFile(task,n);String file="file-"+n;
                jdbc.update("UPDATE agent_personal_workspace_file_version SET original_filename=? WHERE tenant_id=? AND client_id=? AND owner_jiacn=? AND file_id=? AND version=1","pinned-v1-"+n,TENANT,CLIENT,OWNER,file);
                jdbc.update("INSERT INTO agent_personal_workspace_file_version(file_id,owner_jiacn,version,original_filename,content_mime_type,byte_length,content_hash,storage_uri,created_at,tenant_id,client_id) VALUES(?,?,2,'unselected-v2','text/plain',8,?,'private:v2',2,?,?)",file,OWNER,"c".repeat(64),TENANT,CLIENT);
                assertEquals(1,jdbc.update("UPDATE agent_personal_workspace_file SET latest_version=2,metadata_revision=2 WHERE tenant_id=? AND client_id=? AND owner_jiacn=? AND file_id=?",TENANT,CLIENT,OWNER,file));
                for(int i=expected.size();i<n;i++){
                    String id="agt_"+String.format(java.util.Locale.ROOT,"%032x",i+1),ability="合同审查 "+i;
                    jdbc.update("INSERT INTO agent_persona_binding(jiacn,persona_code,agent_id,bound_at,status,tenant_id,client_id) VALUES (?,?,?,1,1,?,?)",OWNER,"rec"+i,id,TENANT,CLIENT);
                    long binding=jdbc.queryForObject("SELECT id FROM agent_persona_binding WHERE agent_id=?",Long.class,id);
                    jdbc.update("INSERT INTO agent_identity_registry(canonical_agent_id,canonical_type,lifecycle_status,client_id,owner_jiacn,tenant_id,binding_id,provisioned_at,activated_at,audit_reason,create_time,update_time) VALUES (?,'OPAQUE','ACTIVE',?,?,?,?,1,1,'rec-http-fixture',1,1)",id,CLIENT,OWNER,TENANT,binding);
                    var abilities=i==1?List.<String>of():List.of(ability,"合同条款核对 "+i);
                    jdbc.update("INSERT INTO agent_runtime(agent_id,name,owner_jiacn,binding_id,status,tenant_id,client_id,abilities) VALUES (?,?,?,?,'offline',?,?,?)",id,"测试候选"+i,OWNER,binding,TENANT,CLIENT,new ObjectMapper().writeValueAsString(abilities));
                    es.index(TENANT,CLIENT,OWNER,id,abilities);if(i%8==1){installed.installed(id);es.indexSkill(TENANT,CLIENT,id,skills);}expected.add(id);
                }
                if(n==108){for(var cursor:List.of("",expected.stream().skip(99).findFirst().orElseThrow())){explainRecPage(cursor);}}
                es.refresh();assertEquals(n==1?2L:228L,es.documentCount());measureRecommendationHttp(task,expected,es.encodes);
                if(n==1){String agent=expected.iterator().next();
                    assertEquals(1,jdbc.update("UPDATE agent_runtime SET abilities='[]' WHERE tenant_id=? AND client_id=? AND agent_id=?",TENANT,CLIENT,agent));
                    String key="rec-withdrawn";var request=HttpRequest.newBuilder(URI.create(origin+"/agent/tasks/"+task+"/recommend")).header("Authorization","Bearer "+httpToken()).header("X-Request-Id",key).header("Content-Type","application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"protocolVersion\":1,\"requestId\":\"rec-withdrawn\",\"data\":{\"expectedTaskVersion\":\"0\"}}" )).build();
                    try(var client=HttpClient.newHttpClient()){var res=client.send(request,HttpResponse.BodyHandlers.ofString());assertEquals(200,res.statusCode());var rows=new ObjectMapper().readTree(res.body()).at("/data/recommendations");assertEquals(1,rows.size());var row=rows.get(0);assertEquals(agent,row.at("/agent/agentId").asText());assertTrue(row.path("evidence").isArray());assertEquals(0,row.path("evidence").size());assertEquals("UNKNOWN",row.path("recommendationLevel").asText());}
                    assertEquals(2L,es.documentCount());assertEquals(1,jdbc.update("UPDATE agent_runtime SET abilities=? WHERE tenant_id=? AND client_id=? AND agent_id=?",new ObjectMapper().writeValueAsString(List.of("合同审查 0","合同条款核对 0")),TENANT,CLIENT,agent));
                }
                org.mockito.Mockito.verifyNoInteractions(storage,writes);
                if(n==108){int before=es.encodes.get();String agent=expected.iterator().next();
                    String onlySkill=expected.stream().skip(1).findFirst().orElseThrow();assertInstalledHttp(task,onlySkill,true);
                    installed.withdraw();assertInstalledHttp(task,onlySkill,false);assertEquals(228L,es.documentCount());
                    assertEquals(1,jdbc.update("DELETE FROM agent_runtime WHERE tenant_id=? AND client_id=? AND owner_jiacn=? AND agent_id=?",TENANT,CLIENT,OWNER,agent));
                    checkRecError(task,503,"RECOMMENDATION_UNAVAILABLE");
                    jdbc.update("INSERT INTO agent_task_meta(task_id,owner_jiacn,tenant_id,client_id) VALUES('foreign-rec','different-owner',?,?)",TENANT,CLIENT);
                    checkRecError("foreign-rec",404,"RECOMMENDATION_NOT_FOUND");assertEquals(before,es.encodes.get());
                }
            }
        }
    }
    /** Called only inside the isolated loopback HTTP/MySQL/ES fixture. The initial query vector uses
     * the fixture encoder; cold refers to the API vector cache, not a real provider or cold OS. */
    private void checkRecError(String task,int status,String code)throws Exception {
        String id="rec-error";var request=HttpRequest.newBuilder(URI.create(origin+"/agent/tasks/"+task+"/recommend"))
                .header("Authorization","Bearer "+httpToken()).header("X-Request-Id",id).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"protocolVersion\":1,\"requestId\":\"rec-error\",\"data\":{\"expectedTaskVersion\":\"0\"}}" )).build();
        try(var client=HttpClient.newHttpClient()) {var r=client.send(request,HttpResponse.BodyHandlers.ofString());assertEquals(status,r.statusCode());var env=new ObjectMapper().readTree(r.body());assertEquals(code,env.path("code").asText());assertTrue(env.at("/data/recommendations").isMissingNode());}
    }
    private void assertInstalledHttp(String task,String agent,boolean visible)throws Exception {
        String key=visible?"published":"withdrawn";
        var request=HttpRequest.newBuilder(URI.create(origin+"/agent/tasks/"+task+"/recommend")).header("Authorization","Bearer "+httpToken()).header("X-Request-Id",key).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"protocolVersion\":1,\"requestId\":\""+key+"\",\"data\":{\"expectedTaskVersion\":\"0\"}}" )).build();
        try(var client=HttpClient.newHttpClient()){var res=client.send(request,HttpResponse.BodyHandlers.ofString());assertEquals(200,res.statusCode());var data=new ObjectMapper().readTree(res.body()).path("data");assertEquals("HYBRID",data.path("retrievalMode").asText());var rows=data.path("recommendations");assertEquals(108,rows.size());int matched=0;
            for(var row:rows)if(agent.equals(row.at("/agent/agentId").asText())){matched++;var evidence=row.path("evidence");assertTrue(evidence.isArray());assertEquals(visible?1:0,evidence.size());if(visible){assertEquals("INSTALLED_SKILL",evidence.get(0).path("kind").asText());assertEquals("合同审查",evidence.get(0).path("label").asText());}else assertEquals("UNKNOWN",row.path("recommendationLevel").asText());}assertEquals(1,matched);}
    }
    private long paidInventorySelects(){return admin.queryForObject("SELECT COALESCE(SUM(COUNT_STAR),0) FROM performance_schema.events_statements_summary_by_digest WHERE SCHEMA_NAME=? AND DIGEST_TEXT LIKE 'SELECT %' AND DIGEST_TEXT LIKE '%economy_hosting_lease%' AND DIGEST_TEXT LIKE '%inventory_snapshot_json%'",Long.class,database);}
    // Test-owned mysqld only. Read counters from the admin schema outside each timed request group.
    // No statement text, task data, individual event history, credentials or parameters are logged.
    private record RecSql(long statements,long examined,long sent,long statementPicos,long roots,long waits,long waitMillis){
        RecSql minus(RecSql old){return new RecSql(statements-old.statements,examined-old.examined,sent-old.sent,statementPicos-old.statementPicos,roots-old.roots,waits-old.waits,waitMillis-old.waitMillis);}
    }
    private record Shape(long calls,long examined,long sent,long picos){}
    private java.util.Map<String,Shape> recShapes(){
        var result=new java.util.TreeMap<String,Shape>();
        admin.query("SELECT DIGEST,COUNT_STAR,SUM_ROWS_EXAMINED,SUM_ROWS_SENT,SUM_TIMER_WAIT FROM performance_schema.events_statements_summary_by_digest WHERE SCHEMA_NAME=? AND DIGEST_TEXT LIKE 'SELECT %' AND DIGEST IS NOT NULL",(org.springframework.jdbc.core.RowCallbackHandler)rs->{result.put(rs.getString(1),new Shape(rs.getLong(2),rs.getLong(3),rs.getLong(4),rs.getLong(5)));},database);
        return result;
    }
    // Link performance_schema's server-generated digest to the actual mapper query. Prepared parameters
    // are the same synthetic scope/cursor as the measured fixture; nothing from EXPLAIN is logged except access metadata.
    private void explainRecPage(String cursor)throws Exception{
        var cfg=recommendationSql.getConfiguration();var stmt=cfg.getMappedStatement(AgentExternalServicePolicyMapper.class.getName()+".candidates");
        var params=java.util.Map.of("tenant",TENANT,"client",CLIENT,"owner",OWNER,"after",cursor,"limit",101);var bound=stmt.getBoundSql(params);
        // The MySQL digest parser does not accept an unbound PREPARE template. Observe the
        // real mapper in a quiescent, single-reader window instead of interpolating SQL literals.
        var before=recShapes();var rows=recommendationSql.getMapper(AgentExternalServicePolicyMapper.class).candidates(TENANT,CLIENT,OWNER,cursor,101);
        assertEquals(cursor.isEmpty()?101:8,rows.size());var matched=new java.util.ArrayList<String>();
        recShapes().forEach((key,now)->{var old=before.getOrDefault(key,new Shape(0,0,0,0));
            if(now.calls()-old.calls()==1 && now.sent()-old.sent()==rows.size())matched.add(key);
        });assertEquals(1,matched.size(),"one actual page statement should match this isolated observation");var digest=matched.getFirst();
        try(var conn=jdbc.getDataSource().getConnection();var ps=conn.prepareStatement("EXPLAIN FORMAT=JSON "+bound.getSql())){
            cfg.newParameterHandler(stmt,params,bound).setParameters(ps);try(var r=ps.executeQuery()){assertTrue(r.next());reportPlan(digest,cursor.isEmpty()?"first":"next",new ObjectMapper().readTree(r.getString(1)));}
        }
    }
    private void reportPlan(String digest,String page,tools.jackson.databind.JsonNode node){
        if(node.isObject()&&node.has("table_name")&&node.has("access_type"))System.out.printf("RECOMMEND_SQL_PLAN digest=%s page=%s table=%s access=%s key=%s estimatedRowsPerScan=%s%n",digest,page,node.path("table_name").asText(),node.path("access_type").asText(),node.path("key").asText("none"),node.path("rows_examined_per_scan").asText("unknown"));
        for(var child:node)reportPlan(digest,page,child);
    }
    private void reportShapes(int size,java.util.Map<String,Shape> before){
        var ranked=new java.util.ArrayList<java.util.Map.Entry<String,Shape>>();recShapes().forEach((key,now)->{
            var old=before.getOrDefault(key,new Shape(0,0,0,0));if(now.calls()>old.calls())ranked.add(java.util.Map.entry(key,new Shape(now.calls()-old.calls(),now.examined()-old.examined(),now.sent()-old.sent(),now.picos()-old.picos())));
        });
        ranked.sort(java.util.Comparator.<java.util.Map.Entry<String,Shape>>comparingLong(e->e.getValue().examined()).reversed().thenComparing(java.util.Map.Entry::getKey));
        for(var e:ranked){var s=e.getValue();System.out.printf(java.util.Locale.ROOT,"RECOMMEND_SQL_DIGEST candidates=%d digest=%s calls=%d examined=%d sent=%d selectMs=%.3f%n",size,e.getKey(),s.calls(),s.examined(),s.sent(),s.picos()/1e9);}
    }
    private RecSql readRecSql(){
        assertEquals(1,admin.queryForObject("SELECT @@performance_schema",Integer.class));
        var values=admin.queryForObject("""
                SELECT COALESCE(SUM(COUNT_STAR),0),COALESCE(SUM(SUM_ROWS_EXAMINED),0),COALESCE(SUM(SUM_ROWS_SENT),0),
                    COALESCE(SUM(SUM_TIMER_WAIT),0),COALESCE(SUM(CASE WHEN DIGEST_TEXT LIKE '%`agent_task_meta`%' AND DIGEST_TEXT LIKE '%FOR UPDATE%' THEN COUNT_STAR ELSE 0 END),0)
                FROM performance_schema.events_statements_summary_by_digest WHERE SCHEMA_NAME=? AND DIGEST_TEXT LIKE 'SELECT %'
                """,(rs,n)->new long[]{rs.getLong(1),rs.getLong(2),rs.getLong(3),rs.getLong(4),rs.getLong(5)},database);
        var lock=admin.queryForObject("""
                SELECT MAX(CASE WHEN VARIABLE_NAME='INNODB_ROW_LOCK_WAITS' THEN CAST(VARIABLE_VALUE AS UNSIGNED) END),
                       MAX(CASE WHEN VARIABLE_NAME='INNODB_ROW_LOCK_TIME' THEN CAST(VARIABLE_VALUE AS UNSIGNED) END)
                FROM performance_schema.global_status WHERE VARIABLE_NAME IN ('INNODB_ROW_LOCK_WAITS','INNODB_ROW_LOCK_TIME')
                """,(rs,n)->{var count=rs.getObject(1);var millis=rs.getObject(2);assertNotNull(count);assertNotNull(millis);return new long[]{rs.getLong(1),rs.getLong(2)};});
        return new RecSql(values[0],values[1],values[2],values[3],values[4],lock[0],lock[1]);
    }
    private void measureRecommendationHttp(String task, java.util.Set<String> expected, java.util.concurrent.atomic.AtomicInteger encodes) throws Exception {
        int before=encodes.get();String token=httpToken();var mapper=new ObjectMapper();
        try(var client=HttpClient.newHttpClient()){
            record Observation(long nanos,boolean degraded,boolean invalid){}
            class Probe { Observation run(String id)throws Exception{
                var req=HttpRequest.newBuilder(URI.create(origin+"/agent/tasks/"+task+"/recommend")).header("Authorization","Bearer "+token)
                    .header("Content-Type","application/json").header("X-Request-Id",id).timeout(java.time.Duration.ofSeconds(20))
                    .POST(HttpRequest.BodyPublishers.ofString("{\"protocolVersion\":1,\"requestId\":\""+id+"\",\"data\":{\"expectedTaskVersion\":\"0\"}}" )).build();
                long t=System.nanoTime();var res=client.send(req,HttpResponse.BodyHandlers.ofString());long elapsed=System.nanoTime()-t;
                var env=mapper.readTree(res.body());var data=env.path("data");var rows=data.path("recommendations");var seen=new java.util.HashSet<String>();
                boolean bad=res.statusCode()!=200||!"E0".equals(env.path("code").asText())||!id.equals(env.path("requestId").asText())||!task.equals(data.path("taskId").asText())||!"0".equals(data.path("taskVersion").asText())||!data.path("candidateSetComplete").asBoolean()||!rows.isArray();
                for(var row:rows)if(!seen.add(row.at("/agent/agentId").asText())||row.path("evidence").size()==0||!"UNKNOWN".equals(row.path("recommendationLevel").asText()))bad=true;
                return new Observation(elapsed,!"HYBRID".equals(data.path("retrievalMode").asText()),bad||!seen.equals(expected));
            }}
            var probe=new Probe();var cold=probe.run("rec-cold-"+expected.size());assertFalse(cold.invalid());assertFalse(cold.degraded());assertEquals(before+1,encodes.get());
            for(int concurrency:List.of(1,5,10)){
                long inventoryBefore=paidInventorySelects();var sqlBefore=readRecSql();var shapes=concurrency==1?recShapes():null;
                int invalid=0,degraded=0;var times=new java.util.ArrayList<Long>();
                try(var pool=java.util.concurrent.Executors.newFixedThreadPool(concurrency)){
                    for(int round=0;round<30/concurrency;round++){
                        var gate=new java.util.concurrent.CyclicBarrier(concurrency);var pending=new java.util.ArrayList<java.util.concurrent.Future<Observation>>();
                        for(int i=0;i<concurrency;i++){String key="rec-"+expected.size()+"-"+concurrency+"-"+round+"-"+i;
                            pending.add(pool.submit(()->{gate.await(15,java.util.concurrent.TimeUnit.SECONDS);return probe.run(key);}));}
                        for(var f:pending){var s=f.get(25,java.util.concurrent.TimeUnit.SECONDS);times.add(s.nanos());if(s.invalid())invalid++;if(s.degraded())degraded++;}
                    }
                }
                long inventory=paidInventorySelects()-inventoryBefore;int installs=expected.size()==1?0:14;
                System.out.printf("RECOMMEND_INVENTORY installations=%d concurrency=%d n=%d selects=%d%n",installs,concurrency,times.size(),inventory);
                assertEquals(installs==0?0L:2L*times.size(),inventory,"two independent observations, not two queries per installation");
                times.sort(Long::compare);System.out.printf(java.util.Locale.ROOT,"RECOMMEND_HTTP candidates=%d concurrency=%d coldFixtureMs=%.3f hotN=%d p50ms=%.3f p95ms=%.3f maxms=%.3f invalid=%d degraded=%d%n",expected.size(),concurrency,cold.nanos()/1e6,times.size(),times.get(14)/1e6,times.get(28)/1e6,times.getLast()/1e6,invalid,degraded);
                var delta=readRecSql().minus(sqlBefore);if(shapes!=null)reportShapes(expected.size(),shapes);
                System.out.printf(java.util.Locale.ROOT,"RECOMMEND_MYSQL candidates=%d concurrency=%d n=%d selects=%d rowsExamined=%d rowsSent=%d sumSelectMs=%.3f matchingRootLocks=%d rowWaits=%d sumRowWaitMs=%d%n",expected.size(),concurrency,times.size(),delta.statements(),delta.examined(),delta.sent(),delta.statementPicos()/1e9,delta.roots(),delta.waits(),delta.waitMillis());
                assertTrue(delta.statements()>0 && delta.examined()>0 && delta.sent()>0 && delta.statementPicos()>0,"missing statement observations");assertTrue(delta.waits()>=0 && delta.waitMillis()>=0);
                assertEquals(0,invalid);assertEquals(0,degraded);assertEquals(before+1,encodes.get());
            }
        }
    }
    private void startHttp() throws Exception {
        // The current Web uses delivery-review-operations, not the legacy raw-body formal decision route.
        new ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("db/chat-task-delivery-review-v1.sql"))
                .execute(jdbc.getDataSource());
        var store = new ChatTaskDeliveryReviewStore(jdbc);
        reviewSources = mock(ChatTaskDeliveryReviewReadService.class);
        // Material carrier availability is the same declared fixture observation as assignment inputs;
        // the persisted batch, operation, decision and wallet are all real SQL/services.
        when(reviewSources.sourceItems(any(), eq(TASK), any())).thenReturn(List.of(
                new ChatTaskDeliveryReviewReadService.Item("artifact-1", "1", "a".repeat(64), "result", "RESULT", null, null)));
        var reviewTransactions = proxy(new ChatTaskDeliveryReviewTransaction(roots, store, deliveryReads, decisions, reviewSources),
                ChatTaskDeliveryReviewTransaction.class);
        reviews = new ChatTaskDeliveryReviewService(store, reviewTransactions, new ChatSelectedOutputFinalizationStore(jdbc));
        signingKey = new byte[32]; new SecureRandom().nextBytes(signingKey);
        var decoder = NimbusJwtDecoder.withSecretKey(new SecretKeySpec(signingKey, "HmacSHA256"))
                .macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer("cyf-agent-billing-fixture"));
        httpServer = new Tomcat(); httpServer.setBaseDir(httpDirectory.toString()); httpServer.setPort(0);
        httpServer.getConnector().setProperty("address", "127.0.0.1");
        var context = httpServer.addContext("", Files.createDirectory(httpDirectory.resolve("webroot")).toString());
        context.setParentClassLoader(getClass().getClassLoader());
        httpContext = new AnnotationConfigWebApplicationContext(); httpContext.setServletContext(context.getServletContext());
        httpContext.register(HttpApp.class);
        httpContext.addBeanFactoryPostProcessor(factory -> {
            factory.registerSingleton("jwtDecoder", decoder);
            if(recommendationController!=null)factory.registerSingleton("recommendationController",recommendationController);
            if(independentQuoteController!=null)factory.registerSingleton("independentQuoteController",independentQuoteController);
            factory.registerSingleton("walletController", new cn.jia.economy.api.EconomyWalletController(walletGate, walletReads));
            factory.registerSingleton("assignmentController", new AgentTaskDeliberationOperationController(operationReads, assignment));
            factory.registerSingleton("cancellationController", new AgentTaskCancellationController(cancellation));
            factory.registerSingleton("decisionController", new AgentTaskDeliveryReviewController(reviews,
                    new HumanSenderIdentityResolver(mock(cn.jia.user.service.UserService.class)),
                    new TenantScopeResolver(), reviewSources));
        });
        httpContext.refresh();
        Tomcat.addServlet(context, "dispatcher", new DispatcherServlet(httpContext)).setLoadOnStartup(1);
        context.addServletMappingDecoded("/", "dispatcher");
        addHttpFilter(context, "requestId", new RequestIdFilter());
        addHttpFilter(context, "contextLifecycle", new cn.jia.core.filter.EsContextFilter());
        addHttpFilter(context, "security", new DelegatingFilterProxy("springSecurityFilterChain", httpContext));
        addHttpFilter(context, "authenticatedContext", new cn.jia.oauth.filter.EsSecurityContextFilter(
                new cn.jia.core.security.FixedTenantAdmissionPolicy(TENANT, CLIENT)));
        httpServer.start(); origin = "http://127.0.0.1:" + httpServer.getConnector().getLocalPort();
    }
    private static void addHttpFilter(org.apache.catalina.Context context, String name, jakarta.servlet.Filter filter) {
        var def = new FilterDef(); def.setFilterName(name); def.setFilter(filter); context.addFilterDef(def);
        var map = new FilterMap(); map.setFilterName(name); map.addURLPattern("/*");
        map.setDispatcher(DispatcherType.REQUEST.name()); context.addFilterMap(map);
    }
    private void httpPost(String route, String data, String key, boolean authenticated, int expected) throws Exception {
        String body = "{\"protocolVersion\":1,\"requestId\":\"billing-http\",\"data\":" + data + "}";
        var request = HttpRequest.newBuilder(URI.create(origin + "/agent/tasks/" + TASK + route))
                .header("Content-Type", "application/json").header("X-Request-Id", "billing-http")
                .header("Idempotency-Key", key).POST(HttpRequest.BodyPublishers.ofString(body));
        if (authenticated) request.header("Authorization", "Bearer " + httpToken());
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(expected, response.statusCode(), response.body());
            if (expected == 200) {
                var json = new ObjectMapper().readTree(response.body());
                assertEquals(1, json.path("protocolVersion").asInt());
                assertEquals("E0", json.path("code").asText());
                assertEquals("billing-http", json.path("requestId").asText());
            }
        }
    }
    private String httpToken() throws Exception {
        var encoder = Base64.getUrlEncoder().withoutPadding(); long now = Instant.now().getEpochSecond();
        String header = encoder.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = encoder.encodeToString(new ObjectMapper().writeValueAsBytes(Map.of(
                "iss", "cyf-agent-billing-fixture", "sub", PAYER, "jiacn", OWNER, "tenant_id", TENANT,
                "client_id", CLIENT, "iat", now, "exp", now + 3600)));
        String content = header + "." + payload; var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
        return content + "." + encoder.encodeToString(mac.doFinal(content.getBytes(StandardCharsets.US_ASCII)));
    }

    private AgentTaskExecutionGrantDTO assign() {
        return assignment.assignForDeliberation(SCOPE, TASK, "assign-1", AGENT, 0, 1,
                new AgentTaskExecutionGrantService.Payment(PAYER, quoteId, "recommendation-1"));
    }
    private void prepareSubmitted() {
        // Seed the pre-existing execution result only; submission/runtime behavior is not this fixture's scope.
        jdbc.update("UPDATE agent_task_meta SET reward_status='reviewing',task_version=4,started_at=100");
        jdbc.update("UPDATE agent_task_work_item SET status='submitted',result_artifact_id='artifact-1',submitted_at=200,version=8");
        String work = jdbc.queryForObject("SELECT work_item_id FROM agent_task_work_item", String.class);
        jdbc.update("""
                INSERT INTO agent_task_formal_delivery(task_id,work_item_id,delivery_id,revision,producer_agent_id,
                  run_id,summary,state,submission_digest,manifest_artifact_id,manifest_artifact_version,
                  submitted_at,version,tenant_id,client_id)
                VALUES(?,?,'delivery-1',1,?,'run-1','fixture result','submitted',?,'artifact-1',1,200,0,?,?)
                """, TASK, work, AGENT, "a".repeat(64), TENANT, CLIENT);
        jdbc.update("""
                INSERT INTO agent_task_formal_delivery_item(delivery_id,artifact_id,artifact_version,content_hash,
                  purpose,item_order,tenant_id,client_id) VALUES('delivery-1','artifact-1',1,?,'result',0,?,?)
                """, "a".repeat(64), TENANT, CLIENT);
    }
    private AgentTaskFormalDeliveryViewDTO decide(String decision) {
        var command = new AgentTaskFormalDeliveryDecisionDTO(); command.setDeliveryId("delivery-1");
        command.setDecision(decision); command.setExpectedTaskVersion(4L); command.setExpectedDeliveryVersion(0L);
        if ("changes_requested".equals(decision)) command.setReviewReason("add tests");
        return decisions.decide(TENANT, CLIENT, TASK, OWNER, command);
    }
    private void assertAssignmentAbsent() {
        assertEquals(100, wallet(jdbc)); assertEquals("open", taskState());
        assertEquals(0L, jdbc.queryForObject("SELECT task_version FROM agent_task_meta", Long.class));
        for (String table : List.of("economy_task_charge", "economy_transaction", "economy_entry",
                "economy_escrow", "economy_escrow_funding_lot", "agent_task_member", "agent_task_work_item",
                "agent_task_event", "agent_task_execution_grant", "agent_task_bounty_bootstrap_outbox")) {
            assertEquals(0, count(table), table);
        }
        assertEquals(1, count("economy_task_quote")); // immutable quote predates the failed assignment
    }
    private <T> T proxy(Object target, Class<T> type) {
        var factory = new ProxyFactory(target);
        factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return type.cast(factory.getProxy());
    }
    private <T> T locked(java.util.function.Supplier<T> work) {
        return roots.executeWithLockedTaskRootInOwnerScope(TENANT, CLIENT, OWNER, TASK, root -> work.get());
    }
    private long wallet(JdbcTemplate db) {
        return db.queryForObject("SELECT balance_wen FROM economy_account WHERE account_id='wallet'", Long.class);
    }
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    private String charge() { return jdbc.queryForObject("SELECT state FROM economy_task_charge", String.class); }
    private String taskState() { return jdbc.queryForObject("SELECT reward_status FROM agent_task_meta", String.class); }
    private static class InjectedFailure extends RuntimeException { }
}
