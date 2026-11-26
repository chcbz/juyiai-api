package cn.jia.agent.service.impl;

import cn.jia.chat.service.usage.ChatUsageDispatchAuthority.TaskSource;
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
import java.util.Set;
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
import cn.jia.economy.task.TaskBenchmarkAdmission;
import static cn.jia.economy.task.TaskBenchmarkAdmission.SponsorshipState.*;
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
import cn.jia.agent.service.NativeTaskPreparation;
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
    private AgentTaskMutationTransactionImpl roots;
    private TaskBillingService billing,inlineBilling;
    private cn.jia.economy.task.TaskQuoteBatches batches;
    private java.util.function.Supplier<AgentTaskExecutionGrantServiceImpl> newAssignment;
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

    private com.zaxxer.hikari.HikariDataSource ownedHistoryPool;
    private final java.util.concurrent.atomic.AtomicLong physicalConnects=new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong appBorrows=new java.util.concurrent.atomic.AtomicLong();
    // Count actual new physical connections, not pool borrows. Only the application path is counted.
    private javax.sql.DataSource denseDataSource(DriverManagerDataSource owned,boolean pooled){
        var counting=new org.springframework.jdbc.datasource.DelegatingDataSource(owned){
            @Override public java.sql.Connection getConnection() throws java.sql.SQLException {var c=super.getConnection();physicalConnects.incrementAndGet();return c;}
            @Override public java.sql.Connection getConnection(String u,String p) throws java.sql.SQLException {var c=super.getConnection(u,p);physicalConnects.incrementAndGet();return c;}
        };
        javax.sql.DataSource managed=counting;
        if(pooled){
        var cfg=new com.zaxxer.hikari.HikariConfig();cfg.setDataSource(counting);cfg.setMinimumIdle(5);cfg.setMaximumPoolSize(15);cfg.setConnectionTestQuery("SELECT 1");
        ownedHistoryPool=new com.zaxxer.hikari.HikariDataSource(cfg);managed=ownedHistoryPool;
        }
        return new org.springframework.jdbc.datasource.DelegatingDataSource(managed){
            @Override public java.sql.Connection getConnection() throws java.sql.SQLException {var c=super.getConnection();appBorrows.incrementAndGet();return c;}
            @Override public java.sql.Connection getConnection(String u,String p) throws java.sql.SQLException {var c=super.getConnection(u,p);appBorrows.incrementAndGet();return c;}
        };
    }
    private final io.micrometer.observation.ObservationRegistry quoteWrites=io.micrometer.observation.ObservationRegistry.create();
    @BeforeEach
    void setUp(org.junit.jupiter.api.TestInfo info) throws Exception {
        // Owned ephemeral MySQL only; never connect to a configured/production database.
        database = "cyf_agentbill_" + Long.toUnsignedString(System.nanoTime(), 36);
        admin.execute("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
        String test=info.getTestMethod().orElseThrow().getName();var owned=source(database);
        javax.sql.DataSource source=test.startsWith("denseHistory")?denseDataSource(owned,test.startsWith("denseHistoryPooled")):owned;
        jdbc = new JdbcTemplate(source);
        independent = new JdbcTemplate(source(database));
        new EconomySchemaInitializer(jdbc).afterPropertiesSet();
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("db/economy-task-quote-preparation-v1.sql")).execute(jdbc.getDataSource());
        if(!info.getTestMethod().orElseThrow().getName().startsWith("denseHistory"))applyHistoryFeatures();
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
        for(var m:List.of(AgentRuntimeMapper.class,AgentIdentityRegistryMapper.class,AgentIdentityAliasMapper.class,AgentExternalServicePolicyMapper.class,AgentRuntimeV1InstallationMapper.class))config.addMapper(m);
        config.addMapper(cn.jia.economy.mapper.EconomyUsageCaptureMapper.class);
        config.addMapper(cn.jia.economy.mapper.ConfirmedSampleMapper.class);
        config.addMapper(cn.jia.chat.deliberation.ChatDeliberationMapper.class);
        config.addMapper(cn.jia.chat.deliberation.ChatContextHistoryMapper.class);
        config.addMapper(cn.jia.chat.deliberation.ChatDispatchAuthorityMapper.class);
        config.addMapper(cn.jia.chat.mapper.ChatMessageMapper.class);
        config.addMapper(cn.jia.chat.mapper.ChatConversationMapper.class);
        config.addMapper(cn.jia.chat.mapper.AgentTaskThreadMapper.class);
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
        var posting=new EconomyPostingServiceImpl(ledger,manager,walletGate);
        billing=proxy(new TaskBillingServiceImpl(sql.getMapper(EconomyTaskBillingMapper.class),ledger,posting,new ObjectMapper(),pricing,true,java.util.Optional.of(quoteWrites)),TaskBillingService.class);
        inlineBilling=proxy(new TaskBillingServiceImpl(sql.getMapper(EconomyTaskBillingMapper.class),ledger,posting,new ObjectMapper(),pricing),TaskBillingService.class);
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
        operationReads = new AgentTaskDeliberationOperationReadServiceImpl(
                new AgentTaskExecutionGrantDaoImpl(sql.getMapper(AgentTaskExecutionGrantMapper.class)), outbox,
                new ControlledImageBridgeOperationDaoImpl(sql.getMapper(ControlledImageBridgeOperationMapper.class)),
                requirements, roots, new ObjectMapper());
        var authority = mock(TaskAssigneeAuthorityService.class);
        var target = new TaskAssigneeAuthorityService.Target(TENANT, CLIENT, OWNER, AGENT, 1L, null);
        when(authority.lockForAssignment(TENANT, CLIENT, OWNER, AGENT)).thenReturn(target);
        when(authority.requireGrantTarget(eq(TENANT), eq(CLIENT), eq(OWNER), anyString(), anyString(), anyLong()))
                .thenReturn(target);
        newAssignment = () -> {
            var service = new AgentTaskExecutionGrantServiceImpl(
                    new AgentTaskExecutionGrantDaoImpl(sql.getMapper(AgentTaskExecutionGrantMapper.class)),
                    outbox, links, mock(PersonalWorkspaceDao.class), legacy, identities, roots,
                    new ObjectMapper(), requirements, billing, meta, mock(cn.jia.agent.service.AgentHostingWorkAdmission.class));
            service.setAssigneeAuthority(authority);
            // Live Agent transport is an external observation; SQL/money remains real here.
            service.setDeliberationRuntime(executor -> {
                assertEquals(new TaskDeliberationRuntimeLookup.Scope(TENANT, CLIENT, OWNER, AGENT), executor);
                return true;
            });
            return service;
        };
        assignment = newAssignment.get();
        deliveryReads = proxy(new AgentTaskFormalDeliveryReadServiceImpl(
                new AgentTaskFormalDeliveryDaoImpl(sql.getMapper(AgentTaskFormalDeliveryMapper.class)), meta),
                AgentTaskFormalDeliveryReadService.class);
        executionRows=new PersonalWorkspaceExecutionDaoImpl(sql.getMapper(PersonalWorkspaceExecutionMapper.class),
                sql.getMapper(PersonalWorkspaceExecutionInputMapper.class),sql.getMapper(PersonalWorkspaceExecutionOutputMapper.class));
        var fixed=new cn.jia.core.security.FixedTenantAdmissionPolicy(TENANT,CLIENT);
        calibrationCatalog=proxy(new cn.jia.economy.service.impl.TaskCalibrationCatalogService(sql.getMapper(cn.jia.economy.mapper.TaskCalibrationMapper.class),new ObjectMapper()),cn.jia.economy.task.TaskCalibrationCatalog.class);
        historyCatalog=proxy(new cn.jia.economy.service.impl.TaskHistoryCatalogService(sql.getMapper(cn.jia.economy.mapper.ConfirmedSampleMapper.class),new ObjectMapper()),cn.jia.economy.task.TaskHistoryCatalog.class);
        sampleEvidence=proxy(new cn.jia.economy.service.impl.ConfirmedSampleEvidenceReader(sql.getMapper(cn.jia.economy.mapper.ConfirmedSampleMapper.class),new ObjectMapper()),cn.jia.economy.usage.ConfirmedSampleEvidence.class);
        sampleProjection=proxy(new cn.jia.economy.service.impl.ConfirmedSampleProjectionService(sampleEvidence,sql.getMapper(cn.jia.economy.mapper.ConfirmedSampleMapper.class),new ObjectMapper(),manager),cn.jia.economy.usage.ConfirmedSampleProjection.class);
        var schedules=new cn.jia.task.scheduling.ScheduledWorkStore(sql.getMapper(cn.jia.task.mapper.ScheduledWorkMapper.class),fixed,manager);
        var beans=new org.springframework.beans.factory.support.DefaultListableBeanFactory();beans.registerSingleton("sched",schedules);
        var receipts=proxy(new cn.jia.economy.service.impl.TaskMembershipReceiptStore(sql.getMapper(cn.jia.economy.mapper.TaskMembershipReceiptMapper.class)),cn.jia.economy.usage.TaskMembershipReceipt.class);
        membershipDelivery=new cn.jia.agent.service.usage.TaskMembershipDelivery(executionRows,receipts,beans.getBeanProvider(cn.jia.task.scheduling.ScheduleService.class),fixed);
        recovery=new cn.jia.agent.service.usage.ConfirmedSampleRecovery(sampleProjection,new cn.jia.agent.service.usage.NativeSampleResponseDecoder(org.mockito.Mockito.mock(cn.jia.economy.usage.UsageCostService.class)),fixed,schedules,mock(cn.jia.task.scheduling.CronScheduleService.class),"0 * * * * *");
        membershipSweep=new cn.jia.agent.service.usage.TaskMembershipSweep(executionRows,fixed,manager,schedules,mock(cn.jia.task.scheduling.CronScheduleService.class),"0 * * * * *");
        new ResourceDatabasePopulator(
                new org.springframework.core.io.ClassPathResource("db/economy-task-benchmark-case-v1.sql"),
                new org.springframework.core.io.ClassPathResource("db/economy-task-benchmark-sponsorship-v1.sql"))
                .execute(jdbc.getDataSource());
        recommendationSql.getConfiguration().addMapper(cn.jia.economy.mapper.TaskBenchmarkMapper.class);
        benchmarks = proxy(new cn.jia.economy.service.impl.TaskBenchmarkAdmissionService(
                recommendationSql.getMapper(cn.jia.economy.mapper.TaskBenchmarkMapper.class),new ObjectMapper()),
                TaskBenchmarkAdmission.class);
        decisions = proxy(new AgentTaskFormalDeliveryDecisionServiceImpl(
                new AgentTaskFormalDeliveryDaoImpl(sql.getMapper(AgentTaskFormalDeliveryMapper.class)),
                meta, members, work, roots, events, billing, benchmarks, executionRows), AgentTaskFormalDeliveryDecisionService.class);
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
                new AgentTaskExecutionGrantDaoImpl(sql.getMapper(AgentTaskExecutionGrantMapper.class)), cancelDao, billing, benchmarks,
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
        if(ownedHistoryPool!=null)ownedHistoryPool.close();
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
        var estimate=fixtureNeighborEstimate();
        var prepared=new TaskBillingService.MeasuredPreparation(features,new TaskBillingService.MeasuredGroup("NATIVE_DISPATCH_GROUP_V1","c".repeat(64),"f".repeat(64),"r1","d".repeat(64),"cp"),"v1","fixture-holdout","fixture",estimate);
        var offer=locked(()->billing.storeMeasured(BILLING_TASK,binding,PAYER,prepared));
        return new PreparedFixture(binding,features,offer,prepared);
    }
    private MeasuredNeighborEstimate.Estimate fixtureNeighborEstimate(){
        var unit=new TaskQuoteCalculator.ToolKey("test","draw","frame");
        var fact=new MeasuredTaskRepricing.Fact(new MeasuredTaskRepricing.Operation("source","op"),"draw","frame","fixture-proof",new MeasuredTaskRepricing.Units(unit,BigDecimal.ONE));
        return MeasuredNeighborEstimate.aggregate(fixtureReference(),List.of(new MeasuredNeighborEstimate.Neighbor("training-case","7",BigDecimal.ONE,List.of(fact))));
    }
    private MeasuredTaskRepricing.Reference fixtureReference(){
        var unit=new TaskQuoteCalculator.ToolKey("test","draw","frame");
        var token=new ModelTokenPrice("fixture","model","tier","test-price","synthetic",Map.of(ModelTokenPrice.Category.INPUT,new BigDecimal("10000")),null,Map.of());
        return new MeasuredTaskRepricing.Reference("r1",Map.of("MODEL_RESPONSE",Map.of("INFERENCE",new MeasuredTaskRepricing.TokenRate("fixture",token)),
                "draw",Map.of("frame",new MeasuredTaskRepricing.UnitRate(unit,new TaskQuoteCalculator.ToolPrice("r1","fixture",new BigDecimal("0.25"))))));
    }
    private MeasuredTaskRepricing.Reference fixtureModelReference(){
        return new MeasuredTaskRepricing.Reference("r1",Map.of("MODEL_RESPONSE",fixtureReference().byKind().get("MODEL_RESPONSE")));
    }
    private String fixtureBookPin(MeasuredTaskRepricing.Reference reference){return new cn.jia.economy.service.impl.FrozenCalibrationStudy(new ObjectMapper(),null).freezeReference(reference).sha256();}
    private Map<String,Map<String,cn.jia.economy.pricing.TaskReferencePricing.Route>> fixtureDrawRoutes(){return Map.of("draw",Map.of("frame",new cn.jia.economy.pricing.TaskReferencePricing.UnitRoute(new TaskQuoteCalculator.ToolKey("test","draw","frame"))));}
    private TaskBenchmarkAdmission benchmarks;
    private record SponsoredFixture(TaskBenchmarkAdmission service, TaskBillingService.Task task,
            TaskBenchmarkAdmission.Manifest manifest, String manifestSha,
            TaskBenchmarkAdmission.SponsorshipAuthority authority) {}

    private SponsoredFixture sponsorshipFixture(boolean approved) throws Exception {
        var task = new TaskBillingService.Task(BILLING_TASK.scope(),OWNER,"sponsored-benchmark");
        jdbc.update("INSERT INTO agent_task_meta(task_id,owner_jiacn,tenant_id,client_id) VALUES(?,?,?,?)",
                task.taskId(),OWNER,TENANT,CLIENT);
        var group = new TaskBillingService.MeasuredGroup("NATIVE_DISPATCH_GROUP_V1","c".repeat(64),
                "f".repeat(64),"r1","d".repeat(64),"cp");
        var features = new TaskEstimateFeatures(TaskEstimateFeatures.VERSION,"fixture","a".repeat(64),
                "b".repeat(64),"c".repeat(64),new TaskEstimateFeatures.Identity("fixture","test",1024),
                java.util.Collections.nCopies(1024,1f),List.of(),null);
        var manifest = new TaskBenchmarkAdmission.Manifest("benchmark-case-v1",task,"sponsor-case",AGENT,
                "fixture only: not a production grant","review entire task",group,features);
        var json = new ObjectMapper(); String raw = json.writeValueAsString(manifest);
        String sha = jdbc.queryForObject("SELECT SHA2(?,256)",String.class,raw);
        jdbc.update("INSERT INTO economy_task_benchmark_case(tenant_id,client_id,owner_jiacn,task_id,benchmark_case_id,manifest_json,manifest_sha) VALUES(?,?,?,?,?,?,?)",
                TENANT,CLIENT,OWNER,task.taskId(),manifest.caseId(),raw,sha);
        var authority = new TaskBenchmarkAdmission.SponsorshipAuthority("benchmark-sponsorship-v1",task,
                manifest.caseId(),sha,AGENT,"ASSIGN_AND_START:fixture","e".repeat(64),"f".repeat(64),
                "isolated fixture sponsorship only");
        if (approved) {
            String body = json.writeValueAsString(authority);
            jdbc.update("INSERT INTO economy_task_benchmark_sponsorship(tenant_id,client_id,owner_jiacn,task_id,authority_json,authority_sha) VALUES(?,?,?,?,?,SHA2(?,256))",
                    TENANT,CLIENT,OWNER,task.taskId(),body,body);
        }
        return new SponsoredFixture(benchmarks,task,manifest,sha,authority);
    }
    private <T> T sponsoredLock(SponsoredFixture f, java.util.function.Supplier<T> work) {
        return roots.executeWithLockedTaskRootInOwnerScope(TENANT,CLIENT,OWNER,f.task().taskId(),root -> work.get());
    }
    private TaskBenchmarkAdmission.Sponsorship activate(SponsoredFixture f) {
        var a=f.authority(); var m=f.manifest();
        return f.service().sponsor(f.task(),m.caseId(),f.manifestSha(),AGENT,m.execution(),m.features(),
                a.actionId(),a.assignmentSha256(),a.initialIntentSha256());
    }
    private void assertSponsorNoMoney() {
        assertEquals(100,wallet(jdbc)); assertEquals(0,count("economy_task_charge"));
        assertEquals(0,count("economy_transaction")); assertEquals(1,count("economy_task_quote"));
    }
    @Test void sponsorshipNeedsTransactionAndSeparateAuthority() throws Exception {
        var f=sponsorshipFixture(false);
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,()->activate(f));
        assertThrows(TaskBenchmarkAdmission.NotAdmitted.class,()->sponsoredLock(f,()->activate(f)));
        assertFalse(sponsoredLock(f,()->f.service().settleSponsorship(f.task(),ACCEPTED)));
        assertNull(sponsoredLock(f,()->f.service().sponsorship(f.task())));
        assertEquals(0,count("economy_task_cost_sample")); assertSponsorNoMoney();
    }
    @Test void sponsorshipApprovalAloneNeverMeansFundedAndCannotHideMissingReservation() throws Exception {
        var f=sponsorshipFixture(true);
        assertFalse(sponsoredLock(f,()->f.service().settleSponsorship(f.task(),CANCELLED)));
        assertThrows(cn.jia.economy.task.TaskBillingException.class,()->sponsoredLock(f,()->billing.capture(f.task())));
        assertEquals("APPROVED",jdbc.queryForObject("SELECT state FROM economy_task_benchmark_sponsorship",String.class));
        assertEquals(0,count("economy_task_cost_sample")); assertSponsorNoMoney();
    }
    @Test void sponsorshipActivationReplayAndAcceptanceDoNotCreateMoneyOrReadySamples() throws Exception {
        var f=sponsorshipFixture(true); var first=sponsoredLock(f,()->activate(f));
        assertEquals(ACTIVE,first.state()); assertEquals(first,sponsoredLock(f,()->activate(f)));
        assertEquals(first,sponsoredLock(f,()->f.service().sponsorship(f.task())));
        assertTrue(sponsoredLock(f,()->f.service().settleSponsorship(f.task(),ACCEPTED)));
        assertTrue(sponsoredLock(f,()->f.service().settleSponsorship(f.task(),ACCEPTED)));
        assertThrows(IllegalStateException.class,()->sponsoredLock(f,()->f.service().settleSponsorship(f.task(),CANCELLED)));
        assertThrows(TaskBenchmarkAdmission.NotAdmitted.class,()->sponsoredLock(f,()->activate(f)));
        assertEquals("WAITING_EVIDENCE",jdbc.queryForObject("SELECT sample_state FROM economy_task_cost_sample",String.class));
        assertEquals("ACTIVE",jdbc.queryForObject("SELECT lifecycle_state FROM economy_task_cost_sample",String.class));
        assertEquals(1,count("economy_task_cost_sample")); assertSponsorNoMoney();
    }
    @Test void sponsorshipWithdrawalBlocksFirstActivationButNotRecordedCancellation() throws Exception {
        var f=sponsorshipFixture(true);
        jdbc.update("UPDATE economy_task_benchmark_sponsorship SET withdrawn=TRUE");
        assertThrows(TaskBenchmarkAdmission.NotAdmitted.class,()->sponsoredLock(f,()->activate(f)));
        assertEquals(0,count("economy_task_cost_sample"));
        jdbc.update("UPDATE economy_task_benchmark_sponsorship SET withdrawn=FALSE");
        sponsoredLock(f,()->activate(f));
        jdbc.update("UPDATE economy_task_benchmark_sponsorship SET withdrawn=TRUE");
        jdbc.update("UPDATE economy_task_benchmark_case SET retired=TRUE");
        assertEquals(ACTIVE,sponsoredLock(f,()->f.service().sponsorship(f.task())).state());
        assertTrue(sponsoredLock(f,()->f.service().settleSponsorship(f.task(),CANCELLED)));
        assertTrue(sponsoredLock(f,()->f.service().settleSponsorship(f.task(),CANCELLED)));
        assertThrows(IllegalStateException.class,()->sponsoredLock(f,()->f.service().settleSponsorship(f.task(),ACCEPTED)));
        assertSponsorNoMoney();
    }
    @Test void sponsorshipActivationAndTerminalRollBackWithTaskTransaction() throws Exception {
        var f=sponsorshipFixture(true);
        assertThrows(InjectedFailure.class,()->sponsoredLock(f,()->{activate(f);throw new InjectedFailure();}));
        assertEquals(0,count("economy_task_cost_sample"));
        assertEquals("APPROVED",jdbc.queryForObject("SELECT state FROM economy_task_benchmark_sponsorship",String.class));
        sponsoredLock(f,()->activate(f));
        assertThrows(InjectedFailure.class,()->sponsoredLock(f,()->{f.service().settleSponsorship(f.task(),ACCEPTED);throw new InjectedFailure();}));
        assertEquals(ACTIVE,sponsoredLock(f,()->f.service().sponsorship(f.task())).state()); assertSponsorNoMoney();
    }
    @Test void sponsorshipRejectsChangedActionInitialIntentAndForeignOwner() throws Exception {
        var f=sponsorshipFixture(true); var a=f.authority(); var m=f.manifest();
        for(int i=0;i<3;i++) { final int change=i;
            assertThrows(TaskBenchmarkAdmission.NotAdmitted.class,()->sponsoredLock(f,()->f.service().sponsor(
                    f.task(),m.caseId(),f.manifestSha(),AGENT,m.execution(),m.features(),
                    change==0?"ASSIGN_AND_START:other":a.actionId(),change==1?"0".repeat(64):a.assignmentSha256(),
                    change==2?"0".repeat(64):a.initialIntentSha256())));
        }
        var foreign=new TaskBillingService.Task(f.task().scope(),"another-owner",f.task().taskId());
        assertNull(sponsoredLock(f,()->f.service().sponsorship(foreign)));
        var changed=new TaskBillingService.MeasuredGroup("NATIVE_DISPATCH_GROUP_V1","0".repeat(64),"f".repeat(64),"r1","d".repeat(64),"cp");
        assertThrows(TaskBenchmarkAdmission.NotAdmitted.class,()->sponsoredLock(f,()->f.service().sponsor(f.task(),m.caseId(),f.manifestSha(),AGENT,changed,m.features(),a.actionId(),a.assignmentSha256(),a.initialIntentSha256())));
        assertEquals(0,count("economy_task_cost_sample")); assertSponsorNoMoney();
    }
    @Test void sponsorshipRejectsCorruptAuthorityAndMissingEnrollment() throws Exception {
        var f=sponsorshipFixture(true);
        jdbc.update("UPDATE economy_task_benchmark_sponsorship SET authority_sha=?","0".repeat(64));
        assertThrows(IllegalStateException.class,()->sponsoredLock(f,()->activate(f)));
        jdbc.update("UPDATE economy_task_benchmark_sponsorship SET authority_sha=SHA2(authority_json,256),state='ACTIVE'");
        assertThrows(IllegalStateException.class,()->sponsoredLock(f,()->activate(f)));
        assertThrows(IllegalStateException.class,()->sponsoredLock(f,()->f.service().settleSponsorship(f.task(),ACCEPTED)));
        assertEquals(0,count("economy_task_cost_sample")); assertSponsorNoMoney();
    }
    @Test void sponsorshipCannotRetrofitAnAlreadyEnrolledCase() throws Exception {
        var f=sponsorshipFixture(true); var m=f.manifest();
        sponsoredLock(f,()->f.service().enroll(f.task(),m.caseId(),f.manifestSha(),AGENT,m.execution(),m.features()));
        assertThrows(TaskBenchmarkAdmission.NotAdmitted.class,()->sponsoredLock(f,()->activate(f)));
        assertEquals("APPROVED",jdbc.queryForObject("SELECT state FROM economy_task_benchmark_sponsorship",String.class));
        assertEquals(1,count("economy_task_cost_sample")); assertSponsorNoMoney();
    }
    @Test void sponsorshipCannotSettleOrActivateAlongsideOrdinaryCharge() throws Exception {
        var f=sponsorshipFixture(true); sponsoredLock(f,()->activate(f));
        // Deliberate corrupt coexistence fixture, not a supported source of a charge.
        jdbc.update("INSERT INTO economy_task_charge(tenant_id,client_id,owner_jiacn,task_id,amount_wen,state,created_at,updated_at) VALUES(?,?,?,?,50,'HELD',1,1)",
                TENANT,CLIENT,OWNER,f.task().taskId());
        assertThrows(IllegalStateException.class,()->sponsoredLock(f,()->activate(f)));
        assertThrows(IllegalStateException.class,()->sponsoredLock(f,()->f.service().settleSponsorship(f.task(),CANCELLED)));
        assertEquals("ACTIVE",jdbc.queryForObject("SELECT state FROM economy_task_benchmark_sponsorship",String.class));
        assertEquals("HELD",charge()); assertEquals(100,wallet(jdbc)); assertEquals(0,count("economy_transaction"));
    }

    @Test void benchmarkCaseRequiresSeparateAuthorityAndNeverSeedsAQuoteOrTrainingTruth() throws Exception {benchmarkOfferFence("stop-first");}
    @Test void benchmarkCaseQuoteFirstKeepsItsFixedPriceAfterRevocation() throws Exception {benchmarkOfferFence("quote-first");}
    @Test void benchmarkCaseReviewRetirementCannotResurrectPreparation() throws Exception {benchmarkOfferFence("review");}
    @Test void benchmarkCaseManifestRetirementCannotResurrectPreparation() throws Exception {benchmarkOfferFence("manifest");}
    @Test void benchmarkCaseMemberProjectionInvalidatesTheWholeOffer() throws Exception {benchmarkOfferFence("usage");}
    private void benchmarkOfferFence(String scenario) throws Exception {
        new ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("db/economy-task-benchmark-case-v1.sql")).execute(jdbc.getDataSource());
        var cfg=recommendationSql.getConfiguration();
        var json=new ObjectMapper();var svc=proxy(new cn.jia.economy.service.impl.TaskBenchmarkAdmissionService(recommendationSql.getMapper(cn.jia.economy.mapper.TaskBenchmarkMapper.class),json),cn.jia.economy.task.TaskBenchmarkAdmission.class);
        var exec=new TaskBillingService.MeasuredGroup("NATIVE_DISPATCH_GROUP_V1","c".repeat(64),"f".repeat(64),"r1","d".repeat(64),"cp");
        var f=new TaskEstimateFeatures(TaskEstimateFeatures.VERSION,"fixture","a".repeat(64),"b".repeat(64),"c".repeat(64),new TaskEstimateFeatures.Identity("fixture","test",1024),java.util.Collections.nCopies(1024,1f),List.of(),null);
        var bench=new TaskBillingService.Task(BILLING_TASK.scope(),OWNER,"dedicated-benchmark");
        jdbc.update("INSERT INTO agent_task_meta(task_id,owner_jiacn,tenant_id,client_id) VALUES(?,?,?,?)",bench.taskId(),OWNER,TENANT,CLIENT);
        java.util.function.Function<java.util.function.Supplier<String>,String> lock=work->roots.executeWithLockedTaskRootInOwnerScope(TENANT,CLIENT,OWNER,bench.taskId(),root->work.get());
        int quoteCount=count("economy_task_quote");
        var manifest=new cn.jia.economy.task.TaskBenchmarkAdmission.Manifest("benchmark-case-v1",bench,"case",AGENT,"fixture approval, not a real cost grant","review the entire completed task",exec,f);
        String raw=json.writeValueAsString(manifest);String sha=jdbc.queryForObject("SELECT SHA2(?,256)",String.class,raw);
        var denied=cn.jia.economy.task.TaskBenchmarkAdmission.NotAdmitted.class;
        java.util.function.Supplier<String> enroll=()->svc.enroll(bench,"case",sha,AGENT,exec,f);
        assertThrows(denied,()->lock.apply(enroll));jdbc.update("INSERT INTO economy_task_benchmark_case(tenant_id,client_id,owner_jiacn,task_id,benchmark_case_id,manifest_json,manifest_sha) VALUES(?,?,?,?,?,?,?)",TENANT,CLIENT,OWNER,bench.taskId(),"case",raw,sha);
        assertThrows(denied,()->lock.apply(()->svc.enroll(bench,"case",sha,"other",exec,f)));
        assertThrows(denied,()->lock.apply(()->svc.enroll(new TaskBillingService.Task(bench.scope(),"other",bench.taskId()),"case",sha,AGENT,exec,f)));
        assertThrows(denied,()->lock.apply(()->{jdbc.update("INSERT INTO economy_usage_capture(tenant_id,client_id,owner_jiacn,task_id,agent_id,execution_id,run_id,capture_id) VALUES(?,?,?,?,?,'prior','run',?)",TENANT,CLIENT,OWNER,bench.taskId(),AGENT,"7".repeat(64));return enroll.get();}));
        assertEquals(0,count("economy_usage_capture"));
        assertThrows(InjectedFailure.class,()->lock.apply(()->{enroll.get();throw new InjectedFailure();}));assertEquals(0,count("economy_task_cost_sample"));
        assertEquals(sha,lock.apply(enroll));assertEquals(sha,lock.apply(enroll));assertEquals(1,count("economy_task_cost_sample"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM economy_task_cost_sample WHERE quote_id IS NULL AND benchmark_case_id='case' AND sample_state='WAITING_EVIDENCE' AND projection_revision IS NULL AND history_group_sha256=?",Integer.class,cn.jia.economy.task.TaskHistoryCatalog.Group.of(exec,f).digest()));
        new ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("db/economy-task-benchmark-closure-v1.sql")).execute(jdbc.getDataSource());
        cfg.addMapper(cn.jia.economy.mapper.TaskBenchmarkClosureMapper.class);
        var closure=proxy(new cn.jia.economy.service.impl.TaskBenchmarkClosureService(recommendationSql.getMapper(cn.jia.economy.mapper.TaskBenchmarkClosureMapper.class),recommendationSql.getMapper(cn.jia.economy.mapper.TaskMembershipReceiptMapper.class)),cn.jia.economy.task.TaskBenchmarkClosure.class);
        lock.apply(()->{for(int i=0;i<65;i++){
            var m=new cn.jia.agent.entity.PersonalWorkspaceExecutionEntity().setTaskId(bench.taskId()).setOwnerJiacn(OWNER).setExecutionId("bench-exec"+i).setRunId("run").setRequestHash("a".repeat(64));
            m.setTenantId(TENANT);m.setClientId(CLIENT);executionRows.registerTaskMember(m,cn.jia.agent.service.usage.NativeCaptureEvidence.captureIdentity(m.getExecutionId(),m.getRunId()));}
            executionRows.closeTaskMembers(TENANT,CLIENT,OWNER,bench.taskId(),cn.jia.agent.dao.TaskExecutionClosureDao.Outcome.ACCEPTED,"fixture-reviewed",2,123);return "ok";});
        String fact=jdbc.queryForObject("SELECT terminal_fact_id FROM agent_task_execution_set WHERE task_id=?",String.class,bench.taskId());
        java.util.function.Supplier<String> step=()->closure.reconcile(bench,"case",sha,fact).name();
        assertEquals("AWAITING_VERIFIED_TERMINAL",step.get());deliverTerminal();assertEquals("AWAITING_VERIFIED_TERMINAL",step.get());assertEquals(0,count("economy_task_benchmark_closure"));
        deliverTerminal();assertEquals("AWAITING_VERIFIED_TERMINAL",closure.reconcile(bench,"case",sha,"f".repeat(64)).name());
        assertThrows(denied,()->closure.reconcile(BILLING_TASK,"case",sha,fact));assertEquals("MORE",step.get());assertEquals(64,count("economy_task_cost_sample_member"));
        assertEquals("ACTIVE",jdbc.queryForObject("SELECT lifecycle_state FROM economy_task_cost_sample WHERE sample_id=?",String.class,sha));
        assertEquals("CLOSED",step.get());assertEquals("CLOSED",step.get());assertEquals(65,count("economy_task_cost_sample_member"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM economy_task_cost_sample WHERE sample_id=? AND lifecycle_state='ACCEPTED' AND sample_state='WAITING_EVIDENCE' AND evidence_revision=2",Integer.class,sha));
        new ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("db/economy-task-benchmark-review-v1.sql")).execute(jdbc.getDataSource());
        var evidence=proxy(new cn.jia.economy.service.impl.ConfirmedSampleEvidenceReader(recommendationSql.getMapper(cn.jia.economy.mapper.ConfirmedSampleMapper.class),json),cn.jia.economy.usage.BenchmarkSampleEvidence.class);
        var decoder=textFixture(r->new MeasuredTaskRepricing.Fact(new MeasuredTaskRepricing.Operation("fixture",r.captureId()),"MODEL_RESPONSE","INFERENCE",r.evidenceSha(),new MeasuredTaskRepricing.Tokens("fixture",Map.of(ModelTokenPrice.Category.INPUT,1L))));
        String digest=jdbc.queryForObject("SELECT set_digest FROM economy_task_membership_receipt WHERE fact_id=?",String.class,fact);
        java.util.function.Function<String,String> registerReview=membership->{var review=new cn.jia.economy.usage.BenchmarkSampleEvidence.Review("benchmark-review-v1","case",sha,fact,membership,"ACCEPTED","fixture reviewer","fixture reviewed output");
            String bytes=json.writeValueAsString(review);String key=jdbc.queryForObject("SELECT SHA2(?,256)",String.class,bytes);jdbc.update("INSERT INTO economy_task_benchmark_review VALUES(?,?,?,FALSE)",sha,key,bytes);return key;};
        java.util.function.Function<String,cn.jia.economy.usage.ConfirmedSampleEvidence.Measured> measured=key->evidence.measureReviewed(bench,"case",sha,fact,key,decoder);
        assertNull(measured.apply("0".repeat(64)));assertNull(measured.apply(registerReview.apply("0".repeat(64))));String key=registerReview.apply(digest);
        assertEquals("USAGE_INCOMPLETE",measured.apply(key).assessment().verdict().name());
        jdbc.update("INSERT INTO economy_usage_capture(tenant_id,client_id,owner_jiacn,task_id,agent_id,execution_id,run_id,capture_id,input_revision,projection_revision,coverage_state) SELECT ?,?,?,?,?,execution_id,run_id,capture_id,1,1,'COMPLETE' FROM economy_task_cost_sample_member",TENANT,CLIENT,OWNER,bench.taskId(),AGENT);
        jdbc.update("INSERT INTO economy_usage_capture_dispatch_group SELECT id,1,'NATIVE_DISPATCH_GROUP_V1',REPEAT('c',64),REPEAT('f',64),REPEAT('d',64),'cp',REPEAT('9',64) FROM economy_usage_capture");
        jdbc.update("INSERT INTO economy_usage_capture_record(capture_pk,record_id,revision_key,source_revision,kind,evidence_sha256,binding_sha256,evidence_json,is_associated_head,association_state) SELECT id,REPEAT('1',64),REPEAT('2',64),'1','RESPONSE',REPEAT('3',64),REPEAT('4',64),'{}',1,'APPLIED' FROM economy_usage_capture");
        var match=measured.apply(key);assertEquals("MATCHED",match.assessment().verdict().name());assertEquals(65,match.operations().size());
        var pin=new cn.jia.economy.usage.BenchmarkSampleEvidence.Pin("case",sha,fact,key);var group=cn.jia.economy.task.TaskHistoryCatalog.Group.of(exec,f);
        var selected=new cn.jia.economy.task.TaskHistoryCatalog.Revision(sha,match.assessment().evidenceRevision());var held=cn.jia.economy.pricing.TaskEstimateEvaluation.Split.HOLDOUT;
        java.util.function.Supplier<cn.jia.economy.task.TaskCalibrationObservations.Frozen> freeze=()->evidence.freezeReviewed(bench,group,selected,pin,"bench-family",held,decoder);
        var frozen=freeze.get();var body=json.readTree(frozen.json());assertEquals(65,body.path("operations").size());assertEquals(raw,body.path("manifest").asText());
        assertEquals(jdbc.queryForObject("SELECT review_json FROM economy_task_benchmark_review WHERE review_sha=?",String.class,key),body.path("review").asText());
        assertThrows(cn.jia.economy.task.TaskCalibrationObservations.NotEligible.class,()->evidence.freezeReviewed(bench,group,new cn.jia.economy.task.TaskHistoryCatalog.Revision(sha,selected.revision()+1),pin,"bench-family",held,decoder));
        new ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("db/economy-task-benchmark-use-v1.sql")).execute(jdbc.getDataSource());
        String publish=new org.springframework.core.io.ClassPathResource("db/economy-task-benchmark-materialize.sql").getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        var train=evidence.freezeReviewed(bench,group,selected,pin,"bench-family",cn.jia.economy.pricing.TaskEstimateEvaluation.Split.TRAIN,decoder);
        var study=new cn.jia.economy.service.impl.FrozenCalibrationStudy(json,null);
        var roster=study.freeze(new cn.jia.economy.service.impl.FrozenCalibrationStudy.TrainingBenchmarks(group.privateCalibrationGroup(bench),new cn.jia.economy.service.impl.FrozenCalibrationStudy.Pins("s","d","r","p"),List.of(new cn.jia.economy.service.impl.FrozenCalibrationStudy.Member(bench.taskId(),selected,train.sha256(),pin))));
        new ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("db/economy-task-benchmark-use-fence-v1.sql")).execute(jdbc.getDataSource());
        new ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("db/economy-task-benchmark-retirement-v1.sql")).execute(jdbc.getDataSource());
        new ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("db/economy-history-feature-projection-v1.sql")).execute(jdbc.getDataSource());
        var query=BILLING_TASK;var candidates=proxy(new cn.jia.economy.service.impl.TaskHistoryCatalogService(recommendationSql.getMapper(cn.jia.economy.mapper.ConfirmedSampleMapper.class),json,true,true),cn.jia.economy.task.TaskHistoryCatalog.class);
        var searchParams=new cn.jia.economy.pricing.TaskNeighborSearch.Parameters(cn.jia.economy.pricing.TaskNeighborSearch.trainingScales(List.of(f)),1,1,1,1,1,0.1,0.2,1,1,1,1);
        var gate=new cn.jia.economy.pricing.TaskEstimateCalibration.Gate(1,BigDecimal.ONE);
        var ratio=new cn.jia.economy.pricing.TaskEstimateEvaluation.Ratio(BigDecimal.ZERO,BigDecimal.ONE);
        var scores=new cn.jia.economy.pricing.TaskEstimateEvaluation.Scores(1,1,0,1,0,BigDecimal.ZERO,ratio,ratio);
        var fitted=new cn.jia.economy.pricing.TaskEstimateCalibration.Fit("fixture",searchParams,scores);
        var report=new cn.jia.economy.pricing.TaskEstimateCalibration.Report(group.privateCalibrationGroup(query),group.execution().routeVersion(),fitted,scores,true);
        var structured=new cn.jia.economy.task.TaskCalibrationCatalog.Receipt(cn.jia.economy.pricing.TaskNeighborSearch.VERSION,"1".repeat(64),"2".repeat(64),fixtureBookPin(fixtureModelReference()),"4".repeat(64),roster.sha256(),gate,report);
        var engine=new cn.jia.economy.service.impl.MeasuredHistoryEstimateEngine(candidates,(priceScope,v,routes,kinds)->{
            assertEquals(query.scope(),priceScope);assertEquals("r1",v);assertEquals(java.util.Set.of("MODEL_RESPONSE"),kinds);
            var ref=new ModelTokenPrice("fixture","model","tier","test-price","synthetic",Map.of(ModelTokenPrice.Category.INPUT,new BigDecimal("10000")),null,Map.of());
            return new MeasuredTaskRepricing.Reference(v,Map.of("MODEL_RESPONSE",Map.of("INFERENCE",new MeasuredTaskRepricing.TokenRate("fixture",ref))));});
        var terms=new cn.jia.economy.task.TaskBudgetEstimator.Terms(group.execution(),Map.of("MODEL_RESPONSE",Map.of("INFERENCE",new cn.jia.economy.pricing.TaskReferencePricing.TokenRoute("fixture","model","tier","fixture"))));
        String receipt=json.writeValueAsString(structured); // synthetic passing calibration, not evidence of production accuracy
        var estimator=new cn.jia.economy.service.impl.TaskBudgetEstimatorService(calibrationCatalog,candidates,engine);
        var unavailable=new cn.jia.economy.task.TaskBudgetEstimator.Unavailable(TaskQuoteBatches.Reason.NO_BASELINE);
        jdbc.update("INSERT INTO economy_task_estimator_evidence VALUES(?,?,?,?,SHA2(?,256),?)",TENANT,CLIENT,OWNER,group.digest(),receipt,receipt);
        jdbc.update("INSERT INTO economy_task_estimator_current VALUES(?,?,?,?,1,SHA2(?,256))",TENANT,CLIENT,OWNER,group.digest(),receipt);
        assertEquals(0,jdbc.update(publish,roster.json(),train.json(),1));
        assertEquals(unavailable,estimator.prepare(query,f,terms));
        jdbc.update("INSERT INTO economy_task_benchmark_use_grant VALUES(?,?,?,?,1,SHA2(?,256),?,?,?)",TENANT,CLIENT,OWNER,group.digest(),receipt,sha,train.sha256(),"test-purpose");
        assertEquals(0,jdbc.update(publish,roster.json(),frozen.json(),1));assertEquals(0,jdbc.update(publish,roster.json()+" ",train.json(),1));
        assertEquals(0,jdbc.update(publish,roster.json(),train.json(),2));assertEquals(1,jdbc.update(publish,roster.json(),train.json(),1));
        assertEquals(65,jdbc.queryForObject("SELECT JSON_LENGTH(operations) FROM economy_task_benchmark_use_projection",Integer.class));
        var offer=assertInstanceOf(cn.jia.economy.task.TaskBudgetEstimator.Available.class,estimator.prepare(query,f,terms));assertEquals(65,offer.preparation().estimate().wen());
        assertEquals(sha,offer.preparation().estimate().contributions().getFirst().sampleId());assertEquals(unavailable,estimator.prepare(bench,f,terms));
        assertEquals(unavailable,estimator.prepare(new TaskBillingService.Task(query.scope(),"foreign",query.taskId()),f,terms));
        locked(()->{estimator.lockSelected(query,List.of(offer));return null;});
        if(scenario.equals("quote-first")){
            var quoteLocked=new CountDownLatch(1);
            try(var worker=Executors.newSingleThreadExecutor()){
                var stop=worker.submit(()->{assertTrue(quoteLocked.await(8,TimeUnit.SECONDS));return independent.update("INSERT INTO economy_task_benchmark_use_stop VALUES(?,?,?,?,1,?)",TENANT,CLIENT,OWNER,group.digest(),sha);});
                var fixed=locked(()->{estimator.lockSelected(query,List.of(offer));quoteLocked.countDown();
                    long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);int waits;
                    do{waits=admin.queryForObject("SELECT COUNT(*) FROM performance_schema.data_lock_waits w JOIN performance_schema.data_locks l ON l.ENGINE=w.ENGINE AND l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID WHERE l.OBJECT_SCHEMA=? AND l.OBJECT_NAME='economy_task_benchmark_use_grant'",Integer.class,database);
                        if(waits==0)java.util.concurrent.locks.LockSupport.parkNanos(5_000_000);
                    }while(waits==0 && !stop.isDone() && System.nanoTime()<until);
                    assertTrue(waits>0,"revocation must wait for the existing quote transaction");
                    var binding=new TaskBillingService.Binding(1,f.requirementSha256(),f.inputSnapshotSha256(),AGENT,"bench-quote-first");
                    return billing.storeMeasuredBatch(query,PAYER,List.of(new TaskBillingService.MeasuredWrite(binding,offer.preparation()))).getFirst();});
                assertEquals(1,stop.get(5,TimeUnit.SECONDS));assertEquals(65L,fixed.amountWen());
                assertEquals(65L,jdbc.queryForObject("SELECT amount_wen FROM economy_task_quote WHERE quote_id=?",Long.class,fixed.quoteId()));}
        }else if(scenario.equals("review")){
            jdbc.update("UPDATE economy_task_benchmark_review SET withdrawn=TRUE WHERE review_sha=?",key);
            jdbc.update("UPDATE economy_task_benchmark_review SET withdrawn=FALSE WHERE review_sha=?",key);
        }else if(scenario.equals("manifest")){
            jdbc.update("UPDATE economy_task_benchmark_case SET retired=TRUE WHERE manifest_sha=?",sha);
            jdbc.update("UPDATE economy_task_benchmark_case SET retired=FALSE WHERE manifest_sha=?",sha);
        }else if(scenario.equals("usage")){
            var projection=proxy(new cn.jia.economy.service.impl.UsageCaptureProjectionStore(recommendationSql.getMapper(cn.jia.economy.mapper.EconomyUsageCaptureMapper.class)),cn.jia.economy.usage.UsageCaptureProjection.class);
            jdbc.update("UPDATE economy_usage_capture SET input_revision=2 WHERE execution_id='bench-exec64'");
            long pk=jdbc.queryForObject("SELECT id FROM economy_usage_capture WHERE execution_id='bench-exec64'",Long.class);
            assertTrue(projection.publish(new cn.jia.economy.usage.UsageCaptureProjection.Key(TENANT,CLIENT,pk),2,cn.jia.economy.usage.UsageCaptureProjection.Coverage.INCOMPLETE));
        }
        if(!scenario.equals("stop-first")){
            assertEquals(unavailable,estimator.prepare(query,f,terms));assertThrows(cn.jia.economy.task.TaskBudgetEstimator.Stale.class,()->locked(()->{estimator.lockSelected(query,List.of(offer));return null;}));
            assertEquals(quoteCount+(scenario.equals("quote-first")?1:0),count("economy_task_quote"));assertEquals(0,count("economy_task_charge"));assertEquals(0,count("economy_transaction"));return;
        }
        try(var writer=independent.getDataSource().getConnection();var worker=Executors.newSingleThreadExecutor()){
            writer.setAutoCommit(false);try{
                try(var ps=writer.prepareStatement("INSERT INTO economy_task_benchmark_use_stop VALUES(?,?,?,?,1,?)")){
                    int n=0;for(var value:List.of(TENANT,CLIENT,OWNER,group.digest(),sha))ps.setString(++n,value);assertEquals(1,ps.executeUpdate());}
                var pending=worker.submit(()->assertThrows(cn.jia.economy.task.TaskBudgetEstimator.Stale.class,()->locked(()->{estimator.lockSelected(query,List.of(offer));return null;})));
                boolean waited=false;long start=System.nanoTime();while(!pending.isDone()&&System.nanoTime()-start<TimeUnit.SECONDS.toNanos(5)){
                    if(admin.queryForObject("SELECT COUNT(*) FROM performance_schema.data_lock_waits w JOIN performance_schema.data_locks l ON l.ENGINE=w.ENGINE AND l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID WHERE l.OBJECT_SCHEMA=? AND l.OBJECT_NAME='economy_task_benchmark_use_grant'",Integer.class,database)>0){waited=true;break;}Thread.sleep(5);}
                assertTrue(waited,"quote must wait for the transaction revoking its grant");writer.commit();pending.get(5,TimeUnit.SECONDS);
            }finally{writer.rollback();}}
        assertEquals(unavailable,estimator.prepare(query,f,terms));
        assertEquals(0,jdbc.update(publish,roster.json(),train.json(),1));
        assertNull(sampleEvidence.measure(bench,sha,decoder)); // cannot masquerade as confirmed history
        jdbc.update("UPDATE economy_usage_capture SET input_revision=2 WHERE execution_id='bench-exec64'");var stale=measured.apply(key);
        assertEquals("USAGE_INCOMPLETE",stale.assessment().verdict().name());assertTrue(stale.operations().isEmpty());assertThrows(cn.jia.economy.task.TaskCalibrationObservations.NotEligible.class,freeze::get);
        jdbc.update("UPDATE economy_task_benchmark_review SET withdrawn=TRUE WHERE review_sha=?",key);assertNull(measured.apply(key));
        jdbc.update("UPDATE economy_task_benchmark_case SET retired=TRUE");assertThrows(denied,step::get);assertThrows(denied,()->lock.apply(enroll));
        assertEquals(quoteCount,count("economy_task_quote"));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM economy_task_quote WHERE task_id=?",Integer.class,bench.taskId()));assertEquals(0,count("economy_task_charge"));assertEquals(0,count("economy_transaction"));
    }
    @Test void disablingSharedWritesKeepsOutstandingReferenceConfirmable(){
        var f=measuredOffer();var p=f.preparation();var writes=new java.util.ArrayList<TaskBillingService.MeasuredWrite>();
        for(String a:List.of("selected","alternate"))writes.add(new TaskBillingService.MeasuredWrite(new TaskBillingService.Binding(1,f.binding().requirementSha256(),f.binding().inputSnapshotDigest(),a,"before-switch"),p));
        var old=locked(()->billing.storeMeasuredBatch(BILLING_TASK,PAYER,writes));assertEquals(1,count("economy_task_quote_preparation"));
        var after=writes.stream().map(w->new TaskBillingService.MeasuredWrite(new TaskBillingService.Binding(1,w.binding().requirementSha256(),w.binding().inputSnapshotDigest(),w.binding().agentId(),"after-switch"),p)).toList();
        var next=locked(()->inlineBilling.storeMeasuredBatch(BILLING_TASK,PAYER,after));assertEquals(1,count("economy_task_quote_preparation"));
        assertEquals("OBJECT",jdbc.queryForObject("SELECT JSON_TYPE(snapshot_json->'$.preparation') FROM economy_task_quote WHERE quote_id=?",String.class,next.getFirst().quoteId()));
        assertEquals("STRING",jdbc.queryForObject("SELECT JSON_TYPE(snapshot_json->'$.preparation') FROM economy_task_quote WHERE quote_id=?",String.class,old.getFirst().quoteId()));
        var held=locked(()->inlineBilling.reserve(BILLING_TASK,old.getFirst().quoteId(),writes.getFirst().binding(),PAYER));
        assertEquals(held,locked(()->inlineBilling.reservation(BILLING_TASK,old.getFirst().quoteId(),PAYER,"before-switch")));
        assertEquals(1,count("economy_transaction"));assertEquals(1,count("economy_task_cost_sample"));
    }
    @Test void historyPagesMeasureInlineQuotes(){profileHistoryPages(false);}
    @Test void historyPagesMeasureSharedQuotes(){profileHistoryPages(true);}
    private void profileHistoryPages(boolean shared){profileHistoryPages(shared,257);}
    @Test @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="cyf.measuredBatchQuoteLoad",matches="true")
    void largeHistoryInlinePhaseCosts(){profileHistoryPages(false,1025);}
    @Test @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="cyf.measuredBatchQuoteLoad",matches="true")
    void largeHistorySharedPhaseCosts(){profileHistoryPages(true,1025);}
    @Test @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="cyf.measuredBatchQuoteLoad",matches="true")
    void denseHistoryInlinePhaseCosts(){profileHistoryPages(false,1025,true);}
    @Test @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="cyf.measuredBatchQuoteLoad",matches="true")
    void denseHistorySharedPhaseCosts(){profileHistoryPages(true,1025,true);}
    @Test @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="cyf.pooledHistoryComparison",matches="true")
    void denseHistoryPooledInlinePhaseCosts(){profileHistoryPages(false,1025,true);}
    @Test @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="cyf.pooledHistoryComparison",matches="true")
    void denseHistoryPooledSharedPhaseCosts(){profileHistoryPages(true,1025,true);}
    @Test void estimateReleasesHistoryConnectionBeforePriceFreezeAndRejectsTaskTransactions(){
        var fixture=measuredOffer();var features=fixture.features();var measured=fixture.preparation().group();
        var group=cn.jia.economy.task.TaskHistoryCatalog.Group.of(measured,features);
        var pin=new cn.jia.economy.task.TaskCalibrationCatalog.Pin(group.digest(),1,"a".repeat(64));
        var params=new TaskNeighborSearch.Parameters(TaskNeighborSearch.trainingScales(List.of(features)),1,1,1,1,1,0.1,0.2,1,1,10,1);
        var terms=new cn.jia.economy.task.TaskBudgetEstimator.Terms(measured,Map.of("draw",Map.of("frame",new cn.jia.economy.pricing.TaskReferencePricing.UnitRoute(new TaskQuoteCalculator.ToolKey("test","draw","frame")))));
        var cal=mock(cn.jia.economy.task.TaskCalibrationCatalog.class);var hist=mock(cn.jia.economy.task.TaskHistoryCatalog.class);
        when(cal.resolve(BILLING_TASK,group)).thenAnswer(call->{assertEquals(1,jdbc.queryForObject("SELECT 1",Integer.class));return new cn.jia.economy.task.TaskCalibrationCatalog.Active(pin,params,fixtureBookPin(fixtureReference()));});
        var rev=new cn.jia.economy.task.TaskHistoryCatalog.Revision("sample",1);
        when(hist.candidatePage(BILLING_TASK,group,pin,"")).thenAnswer(call->{assertEquals(1,jdbc.queryForObject("SELECT 1",Integer.class));return List.of(new cn.jia.economy.task.TaskHistoryCatalog.Entry(rev,"history",features));});
        var fact=new MeasuredTaskRepricing.Fact(new MeasuredTaskRepricing.Operation("fixture","op"),"draw","frame","proof",new MeasuredTaskRepricing.Units(new TaskQuoteCalculator.ToolKey("test","draw","frame"),BigDecimal.ONE));
        when(hist.candidateQuantities(BILLING_TASK,group,pin,rev)).thenReturn(List.of(fact));
        cn.jia.economy.pricing.TaskReferencePricing prices=(scope,version,routes,kinds)->{
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.hasResource(jdbc.getDataSource()));
            return new TransactionTemplate(manager).execute(tx->{assertEquals(1,jdbc.queryForObject("SELECT 1",Integer.class));return fixtureReference();});};
        var engine=proxy(new cn.jia.economy.service.impl.MeasuredHistoryEstimateEngine(hist,prices),cn.jia.economy.service.impl.MeasuredHistoryEstimateEngine.class);
        var estimator=proxy(new cn.jia.economy.service.impl.TaskBudgetEstimatorService(cal,hist,engine),cn.jia.economy.task.TaskBudgetEstimator.class);
        assertEquals(25,assertInstanceOf(cn.jia.economy.task.TaskBudgetEstimator.Available.class,estimator.prepare(BILLING_TASK,features,terms)).preparation().estimate().wen());
        locked(()->{assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,()->estimator.prepare(BILLING_TASK,features,terms));
            assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,()->engine.estimate(BILLING_TASK,measured,features,cal.resolve(BILLING_TASK,group),terms.routes()));return null;});
    }
    private boolean attachedProbe;
    private record AttachedQuote(TaskEstimateFeatures reference,MeasuredNeighborEstimate.Estimate expected){}
    @Test @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="cyf.pooledHistoryComparison",matches="true")
    void denseHistoryPooledAttachedQuote(){attachedProbe=true;profileHistoryPages(true,1025,true);}
    private void profileHistoryPages(boolean shared,int n){profileHistoryPages(shared,n,false);}
    private static String denseDigest(String role,int i){
        try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(("synthetic-dense-v2:"+role+":"+i).getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new AssertionError(e);}
    }
    private void profileHistoryPages(boolean shared,int n,boolean dense){
        // Synthetic READY rows for deterministic read cost only, never accuracy/quality evidence.
        var f=measuredOffer();var prep=f.preparation();var group=cn.jia.economy.task.TaskHistoryCatalog.Group.of(prep.group(),prep.features());
        var reader=BILLING_TASK;var writer=shared?billing:inlineBilling;
        var ids=new java.util.ArrayList<String>();new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(status->{
            for(int i=0;i<n;i++){
                String sid=String.format(java.util.Locale.ROOT,"%064x",i+1);ids.add(sid);
                var task=new TaskBillingService.Task(reader.scope(),OWNER,"history-"+i);
                var original=prep.features();var vector=new java.util.ArrayList<Float>();
                if(dense){var random=new java.util.Random(i+42L);vector.add(1f);for(int j=1;j<1024;j++)vector.add((random.nextFloat()-0.5f)*0.01f);}
                var files=!dense || i%4==0?original.files():List.of(new TaskEstimateFeatures.File(i%2==0?"reference":"input",i%3==0?"text/plain":"image/png",1024L*(i+1),1+i%7));
                if(attachedProbe && i==105)files=List.of(new TaskEstimateFeatures.File("INPUT","text/plain",7,1));
                Integer deliverables=dense && i%3!=0?Integer.valueOf(1+i%5):original.requestedDeliverables();
                var source=dense?new TaskEstimateFeatures(original.featureVersion(),original.templateVersion(),denseDigest("requirement",i),denseDigest("inputs",i),denseDigest("text",i),original.embeddingIdentity(),vector,files,deliverables):original;
                var storedPrep=dense?new TaskBillingService.MeasuredPreparation(source,prep.group(),prep.estimatorVersion(),prep.calibrationVersion(),prep.qualityStatus(),prep.estimate()):prep;
                var writes=List.of("a","b").stream().map(a->new TaskBillingService.MeasuredWrite(new TaskBillingService.Binding(1,source.requirementSha256(),source.inputSnapshotSha256(),a,"batch-"+task.taskId()),storedPrep)).toList();
                var stored=writer.storeMeasuredBatch(task,PAYER,writes);
                jdbc.update("INSERT INTO economy_task_cost_sample(sample_id,tenant_id,client_id,owner_jiacn,task_id,quote_id,sample_definition_version,initial_requirement_sha256,initial_input_snapshot_sha256,initial_feature_sha256,lifecycle_state,evidence_revision,projection_revision,sample_state,history_group_sha256) VALUES(?,?,?,?,?,?,'TASK_LIFECYCLE_V1',?,?,?,'ACCEPTED',1,?,?,?)",sid,TENANT,CLIENT,OWNER,task.taskId(),stored.getFirst().quoteId(),source.requirementSha256(),source.inputSnapshotSha256(),source.digest(),dense?null:1,dense?"WAITING_EVIDENCE":"READY",group.digest());
            }
        });assertEquals(shared?n:0,count("economy_task_quote_preparation"));
        if(dense){
            String oldRows="SELECT quote_id,SHA2(snapshot_json,256) AS snapshot_sha,SHA2(CAST(JSON_EXTRACT(snapshot_json,'$.preparation.features') AS CHAR),256) AS features_sha FROM economy_task_quote_resolved ORDER BY quote_id";
            var prior=jdbc.queryForList(oldRows);assertEquals(2*n+2,prior.size());assertTrue(prior.stream().allMatch(r->r.get("snapshot_sha")!=null));assertEquals(List.of(quoteId),prior.stream().filter(r->r.get("features_sha")==null).map(r->r.get("quote_id")).toList());
            long start=System.nanoTime();applyHistoryFeatures();System.out.printf(java.util.Locale.ROOT,"QUOTE_FEATURE_UPGRADE rows=%d shared=%s elapsedMs=%.3f%n",prior.size(),shared,(System.nanoTime()-start)/1e6);
            assertEquals(prior,jdbc.queryForList(oldRows));
        }
        if(n>257){long build=dense?publishDenseFeatures(reader,ids,shared):0;
            profileEstimatorPhases(reader,group,prep.features(),ids,shared,dense);
            if(dense){profileFeatureRead(reader,group,ids,shared);profileProjection(reader,group,ids,shared,prep.features(),build);}return;}
        var probe=recShapes();assertEquals(64,historyCatalog.page(reader,group,"").size());var matches=new java.util.ArrayList<String>();
        recShapes().forEach((id,v)->{var old=probe.getOrDefault(id,new Shape(0,0,0,0));if(v.calls()-old.calls()==1 && v.sent()-old.sent()==64)matches.add(id);});
        assertEquals(1,matches.size());var pageDigest=matches.getFirst();var cfg=recommendationSql.getConfiguration();
        var stmt=cfg.getMappedStatement(cn.jia.economy.mapper.ConfirmedSampleMapper.class.getName()+".history");
        var params=Map.of("t",reader,"group",group.digest(),"after",ids.get(127),"limit",64);var bound=stmt.getBoundSql(params);
        try(var conn=jdbc.getDataSource().getConnection();var ps=conn.prepareStatement("EXPLAIN FORMAT=JSON "+bound.getSql())){
            cfg.newParameterHandler(stmt,params,bound).setParameters(ps);try(var rs=ps.executeQuery()){assertTrue(rs.next());reportPlan(pageDigest,shared?"sharedHistoryNext":"inlineHistoryNext",new ObjectMapper().readTree(rs.getString(1)));}
        }catch(java.sql.SQLException sql){throw new AssertionError("history page EXPLAIN failed",sql);}
        var auxiliary=new java.util.TreeMap<String,Long>();var windows=new java.util.ArrayList<Long>();
        var ticks=new java.util.ArrayList<Long>();long examined=0,statements=0,sent=0;int samples=0;
        for(int run=0;run<=5;run++){
            int pos=0;long walk=0;String cursor="";var before=recShapes();
            while(true){long start=System.nanoTime();var page=historyCatalog.page(reader,group,cursor);long elapsed=System.nanoTime()-start;
                walk+=elapsed;if(run>0 && page.size()==64)ticks.add(elapsed);if(page.isEmpty())break;
                assertEquals(Math.min(64,n-pos),page.size());for(var row:page){assertEquals(ids.get(pos),row.source().sampleId());assertEquals("history-"+pos,row.sourceTaskId());pos++;}
                cursor=page.getLast().source().sampleId();
            }assertEquals(n,pos);if(run==0)continue;windows.add(walk);
            for(var e:recShapes().entrySet()){var v=e.getValue();var prev=before.getOrDefault(e.getKey(),new Shape(0,0,0,0));long calls=v.calls()-prev.calls();if(calls==0)continue;
                if(!pageDigest.equals(e.getKey())){auxiliary.merge(e.getKey(),calls,Long::sum);continue;}
                statements+=calls;examined+=v.examined()-prev.examined();sent+=v.sent()-prev.sent();}samples+=pos;
        }
        for(var e:auxiliary.entrySet()){
            // DIGEST_TEXT is server-normalized; emit only category, SHA and count. Never account another table read as connection setup.
            var shape=admin.queryForObject("SELECT DIGEST_TEXT FROM performance_schema.events_statements_summary_by_digest WHERE SCHEMA_NAME=? AND DIGEST=?",String.class,database,e.getKey()).toUpperCase(java.util.Locale.ROOT);
            assertFalse(shape.matches("(?s).*\\b(FROM|JOIN|UNION|CALL)\\b.*"),"Unexpected additional table SELECT digest="+e.getKey());
            System.out.printf("QUOTE_HISTORY_NO_TABLE_SELECT shared=%s digest=%s calls=%d%n",shared,e.getKey(),e.getValue());
        }
        windows.sort(Long::compare);System.out.printf(java.util.Locale.ROOT,"QUOTE_HISTORY_WALK shared=%s warmN=5 medianMs=%.3f maxMs=%.3f%n",shared,windows.get(2)/1e6,windows.getLast()/1e6);
        assertEquals(30,statements,"one query per keyset page, including terminal empty page");assertEquals(samples,sent);
        ticks.sort(Long::compare);System.out.printf(java.util.Locale.ROOT,"QUOTE_HISTORY_PAGES shared=%s histories=%d fullPages=%d p50ms=%.3f p95ms=%.3f statements=%d rowsExamined=%d rowsSent=%d%n",shared,n,ticks.size(),ticks.get(9)/1e6,ticks.get(18)/1e6,statements,examined,sent);
        // A READY sample still exists. Lost or wrongly scoped source quote must not silently shorten its page.
        jdbc.update("UPDATE economy_task_cost_sample SET quote_id='missing' WHERE sample_id=?",ids.get(80));
        assertThrows(IllegalStateException.class,()->historyCatalog.page(reader,group,ids.get(63)));
    }
    // Isolated synthetic fixture only. No application instrumentation or feature payload logging.
    private void profileFeatureRead(TaskBillingService.Task reader,cn.jia.economy.task.TaskHistoryCatalog.Group group,List<String> ids,boolean shared){
        var mapper=recommendationSql.getMapper(cn.jia.economy.mapper.ConfirmedSampleMapper.class);
        var json=new ObjectMapper();String groupSha=group.digest();
        for(int run=0;run<4;run++){
            long fetch=0,decode=0,verify=0,chars=0;int pos=0,pages=0,withFiles=0,knownDeliverables=0;String after="";
            while(true){long t=System.nanoTime();var page=mapper.history(reader,groupSha,after,64);fetch+=System.nanoTime()-t;pages++;
                if(page.isEmpty())break;
                for(var row:page){assertEquals(ids.get(pos++),row.id());chars+=row.featuresJson().length();
                    t=System.nanoTime();var feature=json.readValue(row.featuresJson(),TaskEstimateFeatures.class);decode+=System.nanoTime()-t;
                    t=System.nanoTime();String sha=feature.digest();var inferred=cn.jia.economy.task.TaskHistoryCatalog.Group.of(group.execution(),feature);verify+=System.nanoTime()-t;
                    assertEquals(row.featureSha(),sha);assertEquals(group,inferred);
                    int index=pos-1;assertEquals(denseDigest("requirement",index),feature.requirementSha256());assertEquals(denseDigest("inputs",index),feature.inputSnapshotSha256());assertEquals(denseDigest("text",index),feature.textSha256());
                    if(!feature.files().isEmpty())withFiles++;if(feature.requestedDeliverables()!=null)knownDeliverables++;
                }after=page.getLast().id();
            }
            assertEquals(ids.size(),pos);assertEquals((ids.size()+63)/64+1,pages);
            assertEquals(pos-(pos+3)/4,withFiles);assertEquals(pos-(pos+2)/3,knownDeliverables);
            if(run==0)System.out.printf("DENSE_METADATA_V2 shared=%s rows=%d withFiles=%d knownDeliverables=%d%n",shared,pos,withFiles,knownDeliverables);
            System.out.printf(java.util.Locale.ROOT,"QUOTE_FEATURE_READ shared=%s run=%d rows=%d pages=%d chars=%d mapperFetchMs=%.3f jsonDecodeMs=%.3f featureVerifyMs=%.3f%n",shared,run,pos,pages,chars,fetch/1e6,decode/1e6,verify/1e6);
        }
    }
    private long publishDenseFeatures(TaskBillingService.Task reader,List<String> ids,boolean shared){
        new ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("db/economy-history-feature-projection-v1.sql")).execute(jdbc.getDataSource());
        // Only the measured operation is synthetic: every READY revision and vector is sealed by the real Economy writer, one transaction per sample.
        var evidence=mock(cn.jia.economy.usage.ConfirmedSampleEvidence.class);var json=new ObjectMapper();
        var decoder=textFixture(r->{throw new AssertionError("Dense fixture supplies a measured operation");});
        when(evidence.measure(any(),anyString(),same(decoder))).thenAnswer(call->{
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            TaskBillingService.Task task=call.getArgument(0);String id=call.getArgument(1);int idx=Integer.parseInt(task.taskId().substring("history-".length()));assertEquals(ids.get(idx),id);
            assertEquals(reader.scope(),task.scope());assertEquals(reader.ownerJiacn(),task.ownerJiacn());
            var a=new cn.jia.economy.usage.ConfirmedSampleEvidence.Assessment(id,1,id,1,cn.jia.economy.usage.ConfirmedSampleEvidence.Verdict.MATCHED);
            var fact=new MeasuredTaskRepricing.Fact(new MeasuredTaskRepricing.Operation("fixture",id),"draw","frame",id,new MeasuredTaskRepricing.Units(new TaskQuoteCalculator.ToolKey("test","draw","frame"),BigDecimal.ONE));
            return new cn.jia.economy.usage.ConfirmedSampleEvidence.Measured(a,List.of(new cn.jia.economy.usage.ConfirmedSampleEvidence.Operation(1,id,1,id,"1",fact)));
        });
        var writer=proxy(new cn.jia.economy.service.impl.ConfirmedSampleProjectionService(evidence,recommendationSql.getMapper(cn.jia.economy.mapper.ConfirmedSampleMapper.class),json,manager,true),cn.jia.economy.usage.ConfirmedSampleProjection.class);
        var samples=new java.util.ArrayList<Long>();long start=System.nanoTime();
        for(int i=0;i<ids.size();i++){long t=System.nanoTime();assertTrue(writer.refresh(new TaskBillingService.Task(reader.scope(),reader.ownerJiacn(),"history-"+i),ids.get(i),decoder));samples.add(System.nanoTime()-t);}
        long elapsed=System.nanoTime()-start;samples.sort(Long::compare);assertEquals(ids.size(),count("economy_history_feature_projection"));assertEquals(ids.size(),count("economy_task_cost_sample_revision"));
        System.out.printf(java.util.Locale.ROOT,"QUOTE_READY_PUBLISH shared=%s rows=%d totalMs=%.3f p50ms=%.3f p95ms=%.3f maxms=%.3f%n",shared,ids.size(),elapsed/1e6,samples.get((ids.size()-1)/2)/1e6,samples.get((95*ids.size()+99)/100-1)/1e6,samples.getLast()/1e6);return elapsed;
    }
    // Diagnostic only: keep the SQL and MyBatis parameter binding identical; do not bypass the catalog for quotes.
    private List<cn.jia.economy.mapper.ConfirmedSampleMapper.CompactRow> jdbcCompactPage(TaskBillingService.Task reader,String group,String after,String known,long[] ns){
        long start=System.nanoTime();var cfg=recommendationSql.getConfiguration();var stmt=cfg.getMappedStatement(cn.jia.economy.mapper.ConfirmedSampleMapper.class.getName()+".compactHistory");
        var args=Map.of("t",reader,"group",group,"after",after,"limit",64,"knownInline",known);var bound=stmt.getBoundSql(args);
        var result=new java.util.ArrayList<cn.jia.economy.mapper.ConfirmedSampleMapper.CompactRow>();
        try(var conn=jdbc.getDataSource().getConnection();var ps=conn.prepareStatement(bound.getSql())){
            cfg.newParameterHandler(stmt,args,bound).setParameters(ps);long t=System.nanoTime();ns[0]+=t-start;
            try(var rs=ps.executeQuery()){long executed=System.nanoTime();ns[1]+=executed-t;
                while(rs.next()){int valid=rs.getInt(7);Integer value=rs.wasNull()?null:Integer.valueOf(valid);
                    result.add(new cn.jia.economy.mapper.ConfirmedSampleMapper.CompactRow(rs.getString(1),rs.getLong(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getBytes(6),value,rs.getString(8)));}
                ns[2]+=System.nanoTime()-executed;
            }
        }catch(java.sql.SQLException err){throw new AssertionError(err);}ns[3]+=System.nanoTime()-start;return result;
    }
    private static void fixtureField(java.io.DataOutputStream out,String value)throws java.io.IOException{
        byte[] b=value.getBytes(StandardCharsets.UTF_8);out.writeInt(b.length);out.write(b);
    }
    // Independent canonical V1 bytes for the digest-only control. No retained payload after the current row.
    private static byte[] fixtureCommitment(TaskEstimateFeatures f,byte[] vector){
        var bytes=new java.io.ByteArrayOutputStream(4608);
        try(var out=new java.io.DataOutputStream(bytes)){
            for(var field:List.of(f.featureVersion(),f.templateVersion(),f.requirementSha256(),f.inputSnapshotSha256(),f.textSha256(),f.embeddingIdentity().providerConfigVersion(),f.embeddingIdentity().model()))fixtureField(out,field);
            out.writeInt(f.embeddingIdentity().dimensions());out.write(vector);out.writeInt(f.files().size());
            for(var file:f.files()){fixtureField(out,file.purpose());fixtureField(out,file.contentMimeType());out.writeLong(file.byteLength());out.writeInt(file.version());}
            out.writeBoolean(f.requestedDeliverables()!=null);if(f.requestedDeliverables()!=null)out.writeInt(f.requestedDeliverables());
        }catch(java.io.IOException err){throw new AssertionError(err);}return bytes.toByteArray();
    }
    // A serial diagnostic of the actual mapper/JSON/restore operations, not inferred CPU or concurrent HTTP time.
    private void profileCompactStages(TaskBillingService.Task reader,cn.jia.economy.task.TaskHistoryCatalog.Group group,List<String> ids,boolean shared){
        var db=recommendationSql.getMapper(cn.jia.economy.mapper.ConfirmedSampleMapper.class);var json=new ObjectMapper();String groupSha=group.digest();
        var known=new java.util.HashMap<String,String>();
        for(int run=0;run<4;run++){
            long[] direct=new long[4];long wireBuild=0,wireHash=0;long mapper=0,decode=0,restore=0,construct=0,commitment=0;int pos=0,pages=0;String after="";
            while(true){String hint=known.getOrDefault(after,"");var plain=run%2==0?jdbcCompactPage(reader,groupSha,after,hint,direct):null;
                long t=System.nanoTime();var rows=db.compactHistory(reader,groupSha,after,64,hint);mapper+=System.nanoTime()-t;pages++;
                if(plain==null)plain=jdbcCompactPage(reader,groupSha,after,hint,direct);assertEquals(rows.size(),plain.size());int at=0;
                var learned=new java.util.StringJoiner(",");
                for(var row:rows){var raw=plain.get(at++);assertEquals(row.id(),raw.id());assertEquals(row.taskId(),raw.taskId());assertEquals(row.revision(),raw.revision());assertEquals(row.metadata(),raw.metadata());assertArrayEquals(row.vector(),raw.vector());assertEquals(row.featureSha(),raw.featureSha());assertEquals(row.sourceValid(),raw.sourceValid());assertEquals(row.inlineSha(),raw.inlineSha());assertEquals(ids.get(pos++),row.id());assertEquals(Integer.valueOf(1),row.sourceValid());
                    t=System.nanoTime();var meta=json.readValue(row.metadata(),cn.jia.economy.service.impl.EconomyHistoryFeatureBytes.Metadata.class);decode+=System.nanoTime()-t;
                    t=System.nanoTime();var feature=cn.jia.economy.service.impl.EconomyHistoryFeatureBytes.restore(meta,row.vector(),row.featureSha());restore+=System.nanoTime()-t;
                    // Independent timed replay of the same two core operations. Timers are not nested in restore.
                    // Compare to the real production restore above; never substitute a cheaper checksum.
                    t=System.nanoTime();var unpacked=new TaskEstimateFeatures(meta.featureVersion(),meta.templateVersion(),meta.requirementSha256(),meta.inputSnapshotSha256(),meta.textSha256(),meta.embeddingIdentity(),TaskEstimateFeatures.vectorFromBytes(row.vector()),meta.files(),meta.requestedDeliverables());construct+=System.nanoTime()-t;
                    t=System.nanoTime();String sha=unpacked.digest();commitment+=System.nanoTime()-t;assertEquals(row.featureSha(),sha);
                    t=System.nanoTime();var canonical=fixtureCommitment(feature,row.vector());wireBuild+=System.nanoTime()-t;
                    try{t=System.nanoTime();var whole=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(canonical));wireHash+=System.nanoTime()-t;assertEquals(row.featureSha(),whole);}catch(java.security.NoSuchAlgorithmException err){throw new AssertionError(err);}
                    assertEquals(group,cn.jia.economy.task.TaskHistoryCatalog.Group.of(group.execution(),feature));if(row.inlineSha()!=null)learned.add(row.inlineSha());
                }
                if(learned.length()>0)known.put(after,learned.toString());if(rows.size()<64)break;after=rows.getLast().id();
            }
            assertEquals(ids.size(),pos);assertEquals(17,pages);
            System.out.printf(java.util.Locale.ROOT,"DENSE_CONTROL shared=%s run=%d prepBindMs=%.3f executeMs=%.3f extractRowsMs=%.3f jdbcTotalMs=%.3f encodeControlMs=%.3f hashControlMs=%.3f%n",shared,run,direct[0]/1e6,direct[1]/1e6,direct[2]/1e6,direct[3]/1e6,wireBuild/1e6,wireHash/1e6);
            System.out.printf(java.util.Locale.ROOT,"DENSE_COMPACT_STAGES shared=%s run=%d rows=%d mapperMs=%.3f metadataDecodeMs=%.3f vectorRestoreAndDigestMs=%.3f replayConstructValidateMs=%.3f replayCommitmentMs=%.3f%n",shared,run,pos,mapper/1e6,decode/1e6,restore/1e6,construct/1e6,commitment/1e6);
        }
    }
    private final QuoteProfile denseCalls=new QuoteProfile();
    private String compactStatementDigest;
    private void profileProjection(TaskBillingService.Task reader,cn.jia.economy.task.TaskHistoryCatalog.Group group,List<String> ids,boolean shared,TaskEstimateFeatures query,long build){
        var json=new ObjectMapper();var groupSha=group.digest();var original=recommendationSql.getMapper(cn.jia.economy.mapper.ConfirmedSampleMapper.class);
        var memoHits=new java.util.concurrent.atomic.AtomicLong();
        int[] miss={0};var mapperClass=cn.jia.economy.mapper.ConfirmedSampleMapper.class;
        var counted=mapperClass.cast(java.lang.reflect.Proxy.newProxyInstance(mapperClass.getClassLoader(),new Class<?>[]{mapperClass},(proxy,method,args)->{
            if(method.getName().equals("compactHistory") && !((String)args[4]).isEmpty())memoHits.incrementAndGet();
            if(method.getName().equals("history"))miss[0]++;
            try{return method.invoke(original,args);}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}
        }));
        var timedMapper=denseCalls.wrap(mapperClass,counted,java.util.Set.of("compactHistory"));
        var registry=io.micrometer.observation.ObservationRegistry.create();
        var events=new java.util.concurrent.ConcurrentHashMap<String,Integer>();
        registry.observationConfig().observationHandler(new io.micrometer.observation.ObservationHandler<io.micrometer.observation.Observation.Context>(){
            public boolean supportsContext(io.micrometer.observation.Observation.Context c){return true;}
            public void onStart(io.micrometer.observation.Observation.Context c){c.put("denseStartNs",System.nanoTime());}
            public void onStop(io.micrometer.observation.Observation.Context c){
                long start=c.get("denseStartNs");denseCalls.nanos.computeIfAbsent(c.getName(),k->new java.util.concurrent.atomic.LongAdder()).add(System.nanoTime()-start);
                var labels=new java.util.HashMap<String,String>();c.getLowCardinalityKeyValues().forEach(k->labels.put(k.getKey(),k.getValue()));
                assertEquals(java.util.Set.of("outcome"),labels.keySet());assertFalse(c.getHighCardinalityKeyValues().iterator().hasNext());
                String k=c.getName()+":"+labels.get("outcome");events.merge(k,1,Integer::sum);
            }
        });
        var projection=proxy(new cn.jia.economy.service.impl.TaskHistoryCatalogService(timedMapper,json,false,true,java.util.Optional.of(registry),true),cn.jia.economy.task.TaskHistoryCatalog.class);
        java.util.function.Function<String,List<cn.jia.economy.task.TaskHistoryCatalog.Entry>> entries=after->projection.page(reader,group,after);
        java.util.function.Function<String,List<String>> page=after->entries.apply(after).stream().map(e->e.source().sampleId()).toList();
        var beforeSql=recShapes();assertEquals(64,page.apply("").size());var matches=new java.util.ArrayList<String>();
        recShapes().forEach((k,v)->{var old=beforeSql.getOrDefault(k,new Shape(0,0,0,0));if(v.calls()-old.calls()==1 && v.sent()-old.sent()==64)matches.add(k);});
        assertEquals(1,matches.size());compactStatementDigest=matches.getFirst();
        for(int run=0;run<4;run++){long t=System.nanoTime();String after="";var seen=new java.util.ArrayList<String>();while(true){var chunk=page.apply(after);if(chunk.isEmpty())break;seen.addAll(chunk);after=chunk.getLast();}long ns=System.nanoTime()-t;assertEquals(ids,seen);
            System.out.printf(java.util.Locale.ROOT,"QUOTE_PROBE shared=%s run=%d rows=%d buildMs=%.3f walkMs=%.3f%n",shared,run,seen.size(),build/1e6,ns/1e6);}
        var scal=TaskNeighborSearch.trainingScales(List.of(query));var params=new TaskNeighborSearch.Parameters(scal,1,1,1,1,1,0.1,0.2,3,1,1,1);
        var pin=new cn.jia.economy.task.TaskCalibrationCatalog.Pin(groupSha,1,"0".repeat(64));
        cn.jia.economy.pricing.TaskReferencePricing reference=(scope,v,routes,kinds)->fixtureReference();
        var engine=new cn.jia.economy.service.impl.MeasuredHistoryEstimateEngine(projection,reference,java.util.Optional.of(registry));
        var originalEngine=new cn.jia.economy.service.impl.MeasuredHistoryEstimateEngine(historyCatalog,reference);
        var priced=new cn.jia.economy.task.TaskCalibrationCatalog.Active(pin,params,fixtureBookPin(fixtureReference()));
        var control=originalEngine.estimate(reader,group.execution(),query,priced,fixtureDrawRoutes());assertEquals(3,control.contributions().size());assertEquals(25,control.wen());
        // Isolate a different logical request with an attached file and explicit deliverable count. The vector is
        // deliberately identical to one history for an exact-neighbour control; not independent holdout truth.
        int anchor=721;var observed=historyCatalog.page(reader,group,ids.get(anchor-1)).getFirst();
        var shape=observed.features();assertEquals(ids.get(anchor),observed.source().sampleId());assertEquals(1,shape.files().size());assertNotNull(shape.requestedDeliverables());
        var rich=new TaskEstimateFeatures(query.featureVersion(),query.templateVersion(),denseDigest("new-req",1),denseDigest("new-input",1),denseDigest("new-text",1),query.embeddingIdentity(),shape.vector(),shape.files(),shape.requestedDeliverables());
        // Training-only synthetic range, not a deployment calibration; no change to the HTTP fixture's admission.
        var train=historyCatalog.page(reader,group,"").stream().map(cn.jia.economy.task.TaskHistoryCatalog.Entry::features).toList();
        var richParams=new TaskNeighborSearch.Parameters(TaskNeighborSearch.trainingScales(train),1,1,1,1,1,0.1,0.2,3,3,10,1);
        var richPriced=new cn.jia.economy.task.TaskCalibrationCatalog.Active(pin,richParams,priced.referenceBookSha256());
        var richControl=originalEngine.estimate(reader,group.execution(),rich,richPriced,fixtureDrawRoutes());
        assertEquals(ids.get(anchor),richControl.contributions().getFirst().sampleId());assertEquals(3,richControl.contributions().size());assertEquals(25,richControl.wen());
        assertEquals(richControl,engine.estimate(reader,group.execution(),rich,richPriced,fixtureDrawRoutes()));
        profileCompactStages(reader,group,ids,shared);
        long payload=jdbc.queryForObject("SELECT SUM(OCTET_LENGTH(feature_meta)+OCTET_LENGTH(vector_bytes)) FROM economy_history_feature_projection",Long.class);
        for(int i=0;i<6;i++){
            long start=System.nanoTime();var actual=engine.estimate(reader,group.execution(),query,priced,fixtureDrawRoutes());long elapsed=System.nanoTime()-start;
            assertEquals(control,actual); // ordered sample ids, revisions, exact weights, prices and currency rounding
            System.out.printf(java.util.Locale.ROOT,"QUOTE_PROBE_ESTIMATE shared=%s run=%d rows=%d selected=%d payloadBytes=%d estimateMs=%.3f%n",shared,i,ids.size(),actual.contributions().size(),payload,elapsed/1e6);
        }
        // Same signed HTTP harness as the one-history lifecycle test: no stub of prepare, selection locks or persist.
        // The receipt, prospective device evidence and encoder are deterministic fixtures, not field-quality evidence.
        var requester=new TaskBillingService.Task(reader.scope(),reader.ownerJiacn(),shared?"dense-http-shared":"dense-http-inline");
        var gate=new TaskEstimateCalibration.Gate(1,BigDecimal.ONE);var zero=new TaskEstimateEvaluation.Ratio(BigDecimal.ZERO,BigDecimal.ONE);
        var scores=new TaskEstimateEvaluation.Scores(1,1,0,1,0,BigDecimal.ZERO,zero,zero);
        AttachedQuote attachment=null;
        if(attachedProbe){var row=historyCatalog.page(reader,group,ids.get(104)).getFirst();var feature=row.features();
            assertEquals(List.of(new TaskEstimateFeatures.File("INPUT","text/plain",7,1)),feature.files());assertNull(feature.requestedDeliverables());
            var expected=originalEngine.estimate(reader,group.execution(),feature,richPriced,fixtureDrawRoutes());assertEquals(ids.get(105),expected.contributions().getFirst().sampleId());
            assertNotEquals(control.contributions().getFirst().sampleId(),ids.get(105));attachment=new AttachedQuote(feature,expected);
        }
        var fit=new TaskEstimateCalibration.Fit("dense-fixture",attachedProbe?richParams:params,scores);
        var report=new TaskEstimateCalibration.Report(group.privateCalibrationGroup(requester),group.execution().routeVersion(),fit,scores,true);
        var terms=new cn.jia.economy.task.TaskBudgetEstimator.Terms(group.execution(),Map.of("draw",Map.of("frame",new cn.jia.economy.pricing.TaskReferencePricing.UnitRoute(new TaskQuoteCalculator.ToolKey("test","draw","frame")))));
        var study=new cn.jia.economy.service.impl.FrozenCalibrationStudy(json,new cn.jia.economy.service.impl.TaskHistoryCatalogService(original,json));
        var book=study.freezeContractReference(requester,terms,reference);
        var receipt=new cn.jia.economy.task.TaskCalibrationCatalog.Receipt(TaskNeighborSearch.VERSION,"1".repeat(64),"2".repeat(64),book.sha256(),"4".repeat(64),"5".repeat(64),gate,report);
        String encoded=json.writeValueAsString(receipt);jdbc.update("INSERT INTO economy_task_estimator_evidence VALUES(?,?,?,?,SHA2(?,256),?)",TENANT,CLIENT,OWNER,groupSha,encoded,encoded);
        jdbc.update("INSERT INTO economy_task_estimator_current VALUES(?,?,?,?,1,SHA2(?,256))",TENANT,CLIENT,OWNER,groupSha,encoded);
        var estimator=proxy(new cn.jia.economy.service.impl.TaskBudgetEstimatorService(calibrationCatalog,projection,engine),cn.jia.economy.task.TaskBudgetEstimator.class);
        System.out.printf("QUOTE_DENSE_HTTP_START shared=%s histories=%d%n",shared,ids.size());
        var probe=attachment;assertDoesNotThrow(()->{verifyHistoryQuoteHttp(study,group,book,requester,estimator,terms,control.wen(),probe);});
        System.out.printf("QUOTE_DENSE_HTTP_END shared=%s histories=%d%n",shared,ids.size());
        assertEquals(0,miss[0]);int at=80;String sid=ids.get(at);String cursor=ids.get(at-1);
        // Separate transactions simulate fault injection; restoring exact row bytes permits a clean control read.
        String quote=jdbc.queryForObject("SELECT q.snapshot_json FROM economy_task_quote q JOIN economy_history_feature_projection x ON x.quote_id=q.quote_id WHERE x.sample_id=?",String.class,sid);
        String qid=jdbc.queryForObject("SELECT quote_id FROM economy_history_feature_projection WHERE sample_id=?",String.class,sid);
        jdbc.update("UPDATE economy_task_quote SET snapshot_json=CONCAT(snapshot_json,' ') WHERE quote_id=?",qid);
        assertEquals(control,engine.estimate(reader,group.execution(),query,priced,fixtureDrawRoutes()));assertEquals(1,miss[0]);jdbc.update("UPDATE economy_task_quote SET snapshot_json=? WHERE quote_id=?",quote,qid);
        assertEquals(!shared,memoHits.get()>0);
        if(!shared){
            assertEquals(sid,entries.apply(cursor).getFirst().source().sampleId()); // exact page has now remembered the inline shape
            // Same quote ID, changed authoritative bytes and matching but wrong derived classification. Old classification cannot apply.
            String oldSha=jdbc.queryForObject("SELECT envelope_sha FROM economy_history_feature_projection WHERE sample_id=?",String.class,sid);
            jdbc.update("UPDATE economy_task_quote SET snapshot_json=JSON_SET(snapshot_json,'$.preparation',?) WHERE quote_id=?","e".repeat(64),qid);
            String newSha=jdbc.queryForObject("SELECT SHA2(snapshot_json,256) FROM economy_task_quote WHERE quote_id=?",String.class,qid);
            jdbc.update("UPDATE economy_history_feature_projection SET envelope_sha=?,resolved_sha=? WHERE sample_id=?",newSha,newSha,sid);
            assertThrows(IllegalStateException.class,()->entries.apply(cursor));assertThrows(IllegalStateException.class,()->engine.estimate(reader,group.execution(),query,priced,fixtureDrawRoutes()));
            jdbc.update("UPDATE economy_task_quote SET snapshot_json=? WHERE quote_id=?",quote,qid);
            jdbc.update("UPDATE economy_history_feature_projection SET envelope_sha=?,resolved_sha=? WHERE sample_id=?",oldSha,oldSha,sid);
        }
        if(shared){String rec=jdbc.queryForObject("SELECT recommendation_id FROM economy_history_feature_projection WHERE sample_id=?",String.class,sid);
            jdbc.update("UPDATE economy_history_feature_projection SET recommendation_id='wrong-binding' WHERE sample_id=?",sid);assertEquals(sid,entries.apply(cursor).getFirst().source().sampleId());
            jdbc.update("UPDATE economy_history_feature_projection SET recommendation_id=? WHERE sample_id=?",rec,sid);}
        if(shared){
            // A valid compact envelope loses its payer-scoped preparation, without touching either immutable body.
            // Even if two derived columns now misclassify the intact envelope as inline, authority cannot be revived.
            String prep=jdbc.queryForObject("SELECT preparation_sha FROM economy_history_feature_projection WHERE sample_id=?",String.class,sid);
            String resolved=jdbc.queryForObject("SELECT resolved_sha FROM economy_history_feature_projection WHERE sample_id=?",String.class,sid);
            jdbc.update("UPDATE economy_task_quote SET payer_user_id='probe-unbound-payer' WHERE quote_id=?",qid);
            assertNull(jdbc.queryForObject("SELECT snapshot_json FROM economy_task_quote_resolved WHERE quote_id=?",String.class,qid));
            jdbc.update("UPDATE economy_history_feature_projection SET preparation_sha=NULL,resolved_sha=envelope_sha WHERE sample_id=?",sid);
            assertThrows(IllegalStateException.class,()->entries.apply(cursor));
            assertThrows(IllegalStateException.class,()->engine.estimate(reader,group.execution(),query,priced,fixtureDrawRoutes()));
            jdbc.update("UPDATE economy_history_feature_projection SET preparation_sha=?,resolved_sha=? WHERE sample_id=?",prep,resolved,sid);
            jdbc.update("UPDATE economy_task_quote SET payer_user_id=? WHERE quote_id=?",PAYER,qid);
        }
        assertEquals(sid,entries.apply(cursor).getFirst().source().sampleId());
        byte[] vector=jdbc.queryForObject("SELECT vector_bytes FROM economy_history_feature_projection WHERE sample_id=?",byte[].class,sid);
        for(byte[] broken:List.of(new byte[4095],new byte[4096],java.util.Arrays.copyOf(vector,4097),vector.clone())){
            if(broken.length==vector.length && java.util.Arrays.equals(broken,vector))broken[3]^=1;
            jdbc.update("UPDATE economy_history_feature_projection SET vector_bytes=? WHERE sample_id=?",broken,sid);
            assertEquals(control,engine.estimate(reader,group.execution(),query,priced,fixtureDrawRoutes()));
        }
        jdbc.update("UPDATE economy_history_feature_projection SET vector_bytes=? WHERE sample_id=?",vector,sid);
        jdbc.update("UPDATE economy_task_cost_sample SET evidence_revision=2 WHERE sample_id=?",sid);assertFalse(page.apply(ids.get(at-1)).contains(sid));
        jdbc.update("UPDATE economy_task_cost_sample SET evidence_revision=1,quote_id='missing' WHERE sample_id=?",sid);assertThrows(IllegalStateException.class,()->page.apply(ids.get(at-1)));assertThrows(IllegalStateException.class,()->engine.estimate(reader,group.execution(),query,priced,fixtureDrawRoutes()));
        String metric="cyf.task.quote.history.projection.page:";
        assertEquals(4,events.get(metric+"invalid_features"));assertEquals(2,events.get(metric+"missing"));
        assertTrue(events.get(metric+"hit")>100);assertTrue(events.get(metric+"empty")>0);
        assertEquals(shared?4:3,events.get(metric+"source_mismatch"));assertEquals(2,events.get("cyf.task.quote.estimate:error"));
        assertTrue(events.get("cyf.task.quote.estimate:ok")>=60);
        assertEquals(java.util.Set.of(metric+"invalid_features",metric+"missing",metric+"hit",metric+"empty",metric+"source_mismatch","cyf.task.quote.estimate:error","cyf.task.quote.estimate:ok"),events.keySet());
    }
    private void applyHistoryFeatures(){new ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("db/economy-task-quote-features-v2.sql")).execute(jdbc.getDataSource());}
    private void profileEstimatorPhases(TaskBillingService.Task reader,cn.jia.economy.task.TaskHistoryCatalog.Group group,TaskEstimateFeatures features,List<String> ids,boolean shared,boolean dense){
        // Synthetic READY projection of one measured frame per task. Identical vectors force stable tie selection across the entire group.
        String ops="""
            [{"memberRevision":1,"captureId":"fixture","captureRevision":1,"recordId":"fixture","sourceRevision":"1","evidenceSha256":"test-proof","operation":{"namespace":"fixture","externalId":"operation"},"kind":"draw","component":"frame","basis":"UNIT","quantity":{"unit":{"channel":"test","tool":"draw","unit":"frame"},"decimalCount":"1"}}]
            """;
        if(!dense)assertEquals(ids.size(),jdbc.update("INSERT INTO economy_task_cost_sample_revision(sample_id,sample_revision,sample_state,reason_code,member_count,operation_refs) SELECT sample_id,1,'READY','FIXTURE',1,? FROM economy_task_cost_sample WHERE history_group_sha256=?",ops,group.digest()));
        var scal=cn.jia.economy.pricing.TaskNeighborSearch.trainingScales(List.of(features));var params=new cn.jia.economy.pricing.TaskNeighborSearch.Parameters(scal,1,1,1,1,1,0.1,0.2,3,1,1,1);
        var pin=new cn.jia.economy.task.TaskCalibrationCatalog.Pin(group.digest(),1,"0".repeat(64));long[] phase=new long[4];var priced=new cn.jia.economy.task.TaskCalibrationCatalog.Active(pin,params,fixtureBookPin(fixtureReference()));
        var type=cn.jia.economy.task.TaskHistoryCatalog.class;
        var timed=type.cast(java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(proxy,method,args)->{
            int slot=switch(method.getName()){case "candidatePage"->0;case "candidateQuantities"->1;default->throw new AssertionError(method.getName());};long t=System.nanoTime();
            try{return method.invoke(historyCatalog,args);}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}finally{phase[slot]+=System.nanoTime()-t;phase[slot+2]++;}
        }));
        var engine=new cn.jia.economy.service.impl.MeasuredHistoryEstimateEngine(timed,(scope,v,routes,kinds)->{assertEquals(java.util.Set.of("draw"),kinds);return fixtureReference();});
        var beforeProbe=recShapes();assertEquals(64,historyCatalog.page(reader,group,"").size());var digests=new java.util.ArrayList<String>();
        recShapes().forEach((key,now)->{var old=beforeProbe.getOrDefault(key,new Shape(0,0,0,0));if(now.calls()-old.calls()==1 && now.sent()-old.sent()==64)digests.add(key);});assertEquals(1,digests.size());
        List<String> firstSelection=null;
        for(int i=0;i<=5;i++){
            var prev=recShapes().get(digests.getFirst());java.util.Arrays.fill(phase,0);long start=System.nanoTime();var result=engine.estimate(reader,group.execution(),features,priced,fixtureDrawRoutes());long estimate=System.nanoTime()-start;
            assertEquals(25,result.wen());var chosen=result.contributions().stream().map(x->x.sampleId()).toList();assertEquals(3,new java.util.HashSet<>(chosen).size());assertTrue(ids.containsAll(chosen));if(!dense)assertEquals(ids.subList(0,3),chosen);if(firstSelection==null)firstSelection=chosen;else assertEquals(firstSelection,chosen);
            var now=recShapes().get(digests.getFirst());assertEquals(17,now.calls()-prev.calls());assertEquals(ids.size(),now.sent()-prev.sent());assertEquals(17,phase[2]);assertEquals(3,phase[3]);
            long[] lock=new long[1];long begin=System.nanoTime();locked(()->{long t=System.nanoTime();for(var c:result.contributions())assertTrue(historyCatalog.lockCandidate(reader,group,pin,new cn.jia.economy.task.TaskHistoryCatalog.Revision(c.sampleId(),1)));lock[0]=System.nanoTime()-t;return null;});long transaction=System.nanoTime()-begin;
            System.out.printf(java.util.Locale.ROOT,"QUOTE_HISTORY_PHASE shared=%s dense=%s warmup=%s histories=%d pages=%d selected=%d estimateMs=%.3f pageReadDecodeMs=%.3f quantitiesMs=%.3f selectAndRepriceMs=%.3f selectedLockMs=%.3f rootTxnMs=%.3f serverPageMs=%.3f rowsExamined=%d%n",shared,dense,i==0,ids.size(),phase[2],phase[3],estimate/1e6,phase[0]/1e6,phase[1]/1e6,(estimate-phase[0]-phase[1])/1e6,lock[0]/1e6,transaction/1e6,(now.picos()-prev.picos())/1e9,now.examined()-prev.examined());
        }
    }
    @Test void scopedQuotePreparationReadsExactBytesAndRejectsForeignOrDamagedReferences() throws Exception {
        var fixture=measuredOffer();String inline=jdbc.queryForObject("SELECT snapshot_json FROM economy_task_quote WHERE quote_id=?",String.class,fixture.offer().quoteId());
        assertEquals(inline,jdbc.queryForObject("SELECT snapshot_json FROM economy_task_quote_resolved WHERE quote_id=?",String.class,fixture.offer().quoteId()));
        String preparation=new ObjectMapper().writeValueAsString(fixture.preparation());
        assertTrue(inline.contains("\"preparation\":"+preparation));
        String sha=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(preparation.getBytes(StandardCharsets.UTF_8)));
        String ref=inline.replace("\"preparation\":"+preparation,"\"preparation\":\""+sha+"\"");String id="tq_m1_scopeprobe";
        jdbc.update("INSERT INTO economy_task_quote(quote_id,binding_sha256,tenant_id,client_id,owner_jiacn,task_id,payer_user_id,amount_wen,snapshot_json,created_at) SELECT ?,?,tenant_id,client_id,owner_jiacn,task_id,payer_user_id,amount_wen,?,created_at FROM economy_task_quote WHERE quote_id=?",id,"7".repeat(64),ref,fixture.offer().quoteId());
        var scope=List.of(TENANT,CLIENT,OWNER,TASK,PAYER,fixture.binding().recommendationId());
        String insert="INSERT INTO economy_task_quote_preparation(tenant_id,client_id,owner_jiacn,task_id,payer_user_id,recommendation_id,preparation_sha,preparation_json) VALUES(?,?,?,?,?,?,?,?)";
        String query="SELECT snapshot_json FROM economy_task_quote_resolved WHERE quote_id=?";
        for(int i=0;i<scope.size();i++){var args=new java.util.ArrayList<String>(scope);args.set(i,args.get(i)+"-wrong");args.add(sha);args.add(preparation);jdbc.update(insert,args.toArray());}
        assertTrue(jdbc.queryForObject(query,String.class,id)==null,"Unresolved compact quote must not expose a snapshot");
        String features="SELECT JSON_EXTRACT(snapshot_json,'$.preparation.features') FROM economy_task_quote_resolved WHERE quote_id=?";assertNull(jdbc.queryForObject(features,String.class,id));
        var args=new java.util.ArrayList<String>(scope);args.add(sha);args.add(preparation);jdbc.update(insert,args.toArray());
        assertEquals(inline,jdbc.queryForObject(query,String.class,id));
        var catalog=new EconomySchemaInitializer(jdbc);
        catalog.afterPropertiesSet();
        assertEquals(inline,jdbc.queryForObject(query,String.class,id));
        assertEquals(jdbc.queryForObject(features,String.class,fixture.offer().quoteId()),jdbc.queryForObject(features,String.class,id));
        // A single matching token in a nested object is not proof that the canonical replacement is top-level.
        String misplaced=ref.replace("\"preparation\":\""+sha+"\"","\"preparation\" : \""+sha+"\",\"decoy\":{\"preparation\":\""+sha+"\"}");assertNotEquals(ref,misplaced);
        jdbc.update("UPDATE economy_task_quote SET snapshot_json=? WHERE quote_id=?",misplaced,id);assertNull(jdbc.queryForObject(features,String.class,id));assertNull(jdbc.queryForObject(query,String.class,id));
        // DML cannot invalidate an accepted reference. An independently damaged fixture is never read.
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE economy_task_quote_preparation SET preparation_json='{}' WHERE preparation_sha=?",sha));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("DELETE FROM economy_task_quote_preparation WHERE preparation_sha=?",sha));
        String damaged="6".repeat(64);args.set(6,damaged);args.set(7,"{}");jdbc.update(insert,args.toArray());
        jdbc.update("UPDATE economy_task_quote SET snapshot_json=? WHERE quote_id=?",ref.replace(sha,damaged),id);
        assertTrue(jdbc.queryForObject(query,String.class,id)==null,"Unresolved compact quote must not expose a snapshot");
        assertNull(jdbc.queryForObject(features,String.class,id));
    }
    @Test void quoteTriggerMaintenanceRejectsIncompleteOrForeignGuards() {
        var catalog=new EconomySchemaInitializer(jdbc);
        String table="economy_task_quote_preparation", guard="trg_economy_quote_preparation_no_delete";
        String body=" FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Frozen quote preparation is immutable'";
        jdbc.execute("CREATE TRIGGER trg_economy_unexpected_guard BEFORE DELETE ON "+table+body);
        assertTrue(assertThrows(IllegalStateException.class,catalog::afterPropertiesSet).getMessage().contains("trg_economy_unexpected_guard"));
        jdbc.execute("DROP TRIGGER trg_economy_unexpected_guard");
        jdbc.execute("DROP TRIGGER "+guard);
        String rejected="Quote preparation trigger "+guard+" is missing or incompatible";
        assertEquals(rejected,assertThrows(IllegalStateException.class,catalog::afterPropertiesSet).getMessage());
        for(String ddl:List.of("BEFORE DELETE ON economy_task_quote"+body,"BEFORE UPDATE ON "+table+body,
                "BEFORE DELETE ON "+table+" FOR EACH ROW SET @ineffective_guard=1")) {
            jdbc.execute("CREATE TRIGGER "+guard+" "+ddl);
            assertEquals(rejected,assertThrows(IllegalStateException.class,catalog::afterPropertiesSet).getMessage());
            jdbc.execute("DROP TRIGGER "+guard);
        }
        jdbc.execute("CREATE TRIGGER "+guard+" BEFORE DELETE ON "+table+body);
        catalog.afterPropertiesSet();
    }
    @Test void measuredBatchRollsBackEarlierChunksAndPreservesMixedSnapshots() throws Exception {
        var f=measuredOffer(); var p=f.preparation(); int before=count("economy_task_quote");
        var g=p.group();var group=new TaskBillingService.MeasuredGroup(g.dispatchGroupVersion(),"e".repeat(64),g.localRouteSha256(),g.routeVersion(),g.toolConfigSha256(),g.capturePolicyVersion());
        var price=new MeasuredNeighborEstimate.Estimate(p.estimate().contributions(),new BigDecimal("0.50"),BigDecimal.ONE,50L);
        var second=new TaskBillingService.MeasuredPreparation(p.features(),group,p.estimatorVersion(),p.calibrationVersion(),p.qualityStatus(),price);
        var writes=new java.util.ArrayList<TaskBillingService.MeasuredWrite>();
        for(int i=0;i<40;i++)writes.add(new TaskBillingService.MeasuredWrite(new TaskBillingService.Binding(1,f.binding().requirementSha256(),f.binding().inputSnapshotDigest(),"multi-"+i,"mixed"),i%2==0?p:second,i==0?null:String.format("%064x",i)));
        var observed=new java.util.ArrayList<String>();
        quoteWrites.observationConfig().observationHandler(new io.micrometer.observation.ObservationHandler<io.micrometer.observation.Observation.Context>(){
            public boolean supportsContext(io.micrometer.observation.Observation.Context c){return true;}
            public void onStop(io.micrometer.observation.Observation.Context c){
                assertEquals("cyf.task.quote.persist",c.getName());var tags=new java.util.HashMap<String,String>();
                c.getLowCardinalityKeyValues().forEach(k->tags.put(k.getKey(),k.getValue()));assertEquals(java.util.Set.of("outcome"),tags.keySet());
                assertFalse(c.getHighCardinalityKeyValues().iterator().hasNext());observed.add(tags.get("outcome"));
            }
        });
        assertTrue(locked(()->billing.storeMeasuredBatch(BILLING_TASK,PAYER,List.of())).isEmpty());assertTrue(observed.isEmpty());
        writes.add(writes.getFirst()); // database unique binding fails after at least one successful INSERT
        assertThrows(org.springframework.dao.DuplicateKeyException.class,()->locked(()->billing.storeMeasuredBatch(BILLING_TASK,PAYER,writes)));
        assertEquals(before,count("economy_task_quote"));assertEquals(0,count("economy_task_quote_preparation"));writes.removeLast();
        var receipts=locked(()->billing.storeMeasuredBatch(BILLING_TASK,PAYER,writes));assertEquals(40,receipts.size());
        assertEquals(List.of("error","written"),observed); // one span per attempted write, not per chunk/candidate
        var json=new ObjectMapper();for(int i=0;i<40;i++){
            var q=receipts.get(i);assertEquals(i%2==0?25L:50L,q.amountWen());var expected=writes.get(i);
            var raw=jdbc.queryForObject("SELECT snapshot_json FROM economy_task_quote_resolved WHERE quote_id=?",String.class,q.quoteId());
            var tree=json.readTree(raw);assertEquals(json.readTree(json.writeValueAsString(expected.prepared())),tree.path("preparation"));
            assertEquals(json.readTree(json.writeValueAsString(expected.binding())),tree.path("binding"));
            if(i==0)assertFalse(tree.has("initialIntentSha256"));else assertEquals(expected.initialIntentSha256(),tree.path("initialIntentSha256").asText());
        }
        profileFrozenPayload(TASK,"mixed",40,2);
        assertEquals(before+40,count("economy_task_quote"));assertEquals(0,count("economy_transaction"));
        var selected=writes.get(1);String target=selected.binding().agentId();assertNull(locked(()->billing.heldInitialIntent(BILLING_TASK,target)));
        locked(()->billing.reserve(BILLING_TASK,receipts.get(1).quoteId(),selected.binding(),PAYER));
        assertEquals(selected.initialIntentSha256(),locked(()->billing.heldInitialIntent(BILLING_TASK,target)));
        assertEquals(selected.prepared().group(),locked(()->billing.heldInitialCommitment(BILLING_TASK,target)).group());
        assertNull(locked(()->billing.heldInitialIntent(BILLING_TASK,writes.get(2).binding().agentId())));
        assertNull(locked(()->billing.heldInitialIntent(new TaskBillingService.Task(BILLING_TASK.scope(),OWNER+"-other",TASK),target)));
        locked(()->billing.release(BILLING_TASK,true));assertNull(locked(()->billing.heldInitialIntent(BILLING_TASK,target)));
    }
    private static void assertFixedQuote(tools.jackson.databind.JsonNode row){
        assertEquals("AVAILABLE",row.path("state").asText());assertEquals("1",row.at("/amount/amountMinor").asText());
        assertTrue(row.path("quoteId").asText().startsWith("tq_f1_"));assertEquals("FIXED_FALLBACK",row.at("/estimateSummary/method").asText());
    }
    @Test void fixedFallbackReservesAndCapturesOneWithoutSeedingMeasuredEvidence(){
        var binding=new TaskBillingService.Binding(1,"a".repeat(64),"b".repeat(64),AGENT,"fixed-batch");
        var q=locked(()->billing.storeFixedFallback(BILLING_TASK,binding,PAYER,TaskQuoteBatches.Reason.BILLING_ROUTE_MISSING));
        var held=locked(()->billing.reserve(BILLING_TASK,q.quoteId(),binding,PAYER));assertEquals(1,held.amountWen());
        assertEquals(held,locked(()->billing.reserve(BILLING_TASK,q.quoteId(),binding,PAYER)));
        assertEquals(1,locked(()->billing.capture(BILLING_TASK)).amountWen());assertEquals(0,count("economy_task_cost_sample"));
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

    private void confirmMeasured(){confirmMeasured(measuredOffer());}
    private void confirmMeasured(PreparedFixture prepared){
        if(conflictOnDiscussionRetry){ // Fixture only: exercise full confirmation/history/calibration before enabling the compact writer.
            String raw=prepared.offer().snapshotJson();String body=new ObjectMapper().writeValueAsString(prepared.preparation());
            String sha=jdbc.queryForObject("SELECT SHA2(?,256)",String.class,body);
            jdbc.update("INSERT INTO economy_task_quote_preparation(tenant_id,client_id,owner_jiacn,task_id,payer_user_id,recommendation_id,preparation_sha,preparation_json) VALUES(?,?,?,?,?,?,?,?)",TENANT,CLIENT,OWNER,TASK,PAYER,prepared.binding().recommendationId(),sha,body);
            jdbc.update("UPDATE economy_task_quote SET snapshot_json=? WHERE quote_id=?",raw.replace("\"preparation\":"+body,"\"preparation\":\""+sha+"\""),prepared.offer().quoteId());
        }
        assignment.assignForDeliberation(SCOPE,TASK,"assign-1",AGENT,0,1,
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
    private boolean cancelQuotedNativeResult;
    /** One task from a synthetic calibrated quote through the real loopback-backed Runtime
     * result commit to HTTP cancellation/refund. Not production cost or delivery acceptance. */
    @Test @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="cyf.ownedPickupCodex",matches=".+")
    void quotedNativeResultCanCancelAndRefundTheSameTaskExactlyOnce() throws Exception {
        assertNotNull(System.getProperty("cyf.ownedPickupCodex"), "Requires the owned Runtime integration fixture");
        cancelQuotedNativeResult = true;
        conflictOnDiscussionRetry = true;
        verifyCurrentMemberEligibility();
    }
    private boolean conflictOnDiscussionRetry;
    @Test void sameDeviceDiscussionFinalStillDoesNotProveMeteredUsage() throws Exception {
        verifyCurrentMemberEligibility();
    }
    @Test void confirmedEligibilityComparesEveryCurrentMemberButNeverTreatsReferenceRouteAsActualIdentity() throws Exception {
        conflictOnDiscussionRetry = true;
        verifyCurrentMemberEligibility();
    }
    private void verifyCurrentMemberEligibility() throws Exception {
        new ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("db/economy-history-feature-projection-v1.sql")).execute(jdbc.getDataSource());
        var db=recommendationSql.getMapper(cn.jia.economy.mapper.ConfirmedSampleMapper.class);var json=new ObjectMapper();
        sampleProjection=proxy(new cn.jia.economy.service.impl.ConfirmedSampleProjectionService(sampleEvidence,db,json,manager,true),cn.jia.economy.usage.ConfirmedSampleProjection.class);
        historyCatalog=proxy(new cn.jia.economy.service.impl.TaskHistoryCatalogService(db,json,false,true),cn.jia.economy.task.TaskHistoryCatalog.class);
        var observations=proxy(new cn.jia.economy.service.impl.TaskHistoryCatalogService(recommendationSql.getMapper(cn.jia.economy.mapper.ConfirmedSampleMapper.class),new ObjectMapper()),cn.jia.economy.task.TaskCalibrationObservations.class);
        var study=new cn.jia.economy.service.impl.FrozenCalibrationStudy(new ObjectMapper(),observations);
        var offer=measuredOffer();var offeredGroup=cn.jia.economy.task.TaskHistoryCatalog.Group.of(offer.preparation().group(),offer.features());
        var unit=new TaskQuoteCalculator.ToolKey("test","draw","frame");
        var allTerms=new cn.jia.economy.task.TaskBudgetEstimator.Terms(offeredGroup.execution(),Map.of("draw",Map.of("frame",new cn.jia.economy.pricing.TaskReferencePricing.UnitRoute(unit)),
                "MODEL_RESPONSE",Map.of("INFERENCE",new cn.jia.economy.pricing.TaskReferencePricing.TokenRoute("fixture","model","tier","fixture"))));
        var book=study.freezeContractReference(BILLING_TASK,allTerms,(scope,version,routes,kinds)->{assertEquals(BILLING_TASK.scope(),scope);assertEquals("r1",version);
            assertEquals(allTerms.routes(),routes);assertEquals(java.util.Set.of("draw","MODEL_RESPONSE"),kinds);return fixtureReference();});
        // Same contract version and same rounded forecast, even identical model rates for all actual operations.
        // Only the tool rate used by the original neighbour differs in the 25th decimal place.
        var changed=study.freezeReference(new MeasuredTaskRepricing.Reference("r1",Map.of("MODEL_RESPONSE",fixtureReference().byKind().get("MODEL_RESPONSE"),
                "draw",Map.of("frame",new MeasuredTaskRepricing.UnitRate(unit,new TaskQuoteCalculator.ToolPrice("r1","fixture",new BigDecimal("0.2500000000000000000000001")))))));

        var before=new cn.jia.economy.service.impl.FrozenCalibrationStudy.OfferedHoldout(BILLING_TASK,"frozen-family",offeredGroup.digest(),offer.features().digest(),offer.offer().quoteId(),offer.binding().agentId(),offer.offer().amountWen(),book.sha256());
        var forecast=study.freeze(before);var artifacts=Map.of(book.sha256(),book.json(),forecast.sha256(),forecast.json());
        var drift=study.freeze(new cn.jia.economy.service.impl.FrozenCalibrationStudy.OfferedHoldout(before.original(),before.family(),before.groupSha(),before.featureSha(),before.quoteId(),before.agentId(),before.wen(),changed.sha256()));
        var driftObjects=Map.of(changed.sha256(),changed.json(),drift.sha256(),drift.json());
        assertEquals(0,count("economy_task_charge"));assertEquals(0,count("economy_task_cost_sample"));
        confirmMeasured(offer);String sid=jdbc.queryForObject("SELECT sample_id FROM economy_task_cost_sample",String.class);
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
        assertEquals(0,count("economy_task_cost_sample_revision"));assertEquals(0,count("economy_history_feature_projection"));
        jdbc.execute("CREATE TRIGGER fixture_reject_projection BEFORE INSERT ON economy_history_feature_projection FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Fixture write failure'");
        try{assertThrows(org.springframework.dao.DataAccessException.class,()->sampleProjection.refresh(BILLING_TASK,sid,fixtureDecoder));
            assertEquals(0,count("economy_task_cost_sample_revision"));assertEquals(0,count("economy_history_feature_projection"));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM economy_task_cost_sample WHERE sample_id=? AND projection_pending=1",Integer.class,sid));
        }finally{jdbc.execute("DROP TRIGGER fixture_reject_projection");}
        assertTrue(sampleProjection.refresh(BILLING_TASK,sid,fixtureDecoder));assertFalse(sampleProjection.refresh(BILLING_TASK,sid,fixtureDecoder));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM economy_history_feature_projection x JOIN economy_task_cost_sample s ON x.sample_id=s.sample_id AND x.sample_revision=s.evidence_revision WHERE s.sample_state='READY' AND OCTET_LENGTH(x.vector_bytes)=4096",Integer.class));
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
        var report=new cn.jia.economy.pricing.TaskEstimateCalibration.Report(group.privateCalibrationGroup(query),group.execution().routeVersion(),fitted,scores,true);
        var receipt=new cn.jia.economy.task.TaskCalibrationCatalog.Receipt(cn.jia.economy.pricing.TaskNeighborSearch.VERSION,"1".repeat(64),"2".repeat(64),fixtureBookPin(fixtureModelReference()),"4".repeat(64),"5".repeat(64),gate,report);
        var receiptJson=new ObjectMapper().writeValueAsString(receipt);
        jdbc.update("INSERT INTO economy_task_estimator_evidence VALUES(?,?,?,?,SHA2(?,256),?)",TENANT,CLIENT,OWNER,group.digest(),receiptJson,receiptJson);
        jdbc.update("INSERT INTO economy_task_estimator_current VALUES(?,?,?,?,1,SHA2(?,256))",TENANT,CLIENT,OWNER,group.digest(),receiptJson);
        var active=calibrationCatalog.resolve(query,group);assertEquals(searchParams,active.parameters());assertTrue(locked(()->calibrationCatalog.lockCurrent(query,active.pin())));
        assertNull(calibrationCatalog.resolve(new TaskBillingService.Task(query.scope(),"different-owner","offer"),group));
        // Even an integrity-valid copy of the receipt into another owner's head cannot activate that model there.
        jdbc.update("INSERT INTO economy_task_estimator_evidence VALUES(?,?,?,?,SHA2(?,256),?)",TENANT,CLIENT,"copied-owner",group.digest(),receiptJson,receiptJson);
        jdbc.update("INSERT INTO economy_task_estimator_current VALUES(?,?,?,?,1,SHA2(?,256))",TENANT,CLIENT,"copied-owner",group.digest(),receiptJson);
        assertThrows(IllegalStateException.class,()->calibrationCatalog.resolve(new TaskBillingService.Task(query.scope(),"copied-owner","offer"),group));
        jdbc.update("DELETE FROM economy_task_estimator_current WHERE tenant_id=? AND client_id=? AND owner_jiacn='copied-owner'",TENANT,CLIENT);

        jdbc.update("UPDATE economy_task_estimator_current SET generation=2,receipt_sha=NULL WHERE group_sha=?",group.digest());
        assertNull(calibrationCatalog.resolve(query,group));assertFalse(locked(()->calibrationCatalog.lockCurrent(query,active.pin())));
        jdbc.update("UPDATE economy_task_estimator_current SET generation=3,receipt_sha=SHA2(?,256) WHERE group_sha=?",receiptJson,group.digest());
        assertFalse(locked(()->calibrationCatalog.lockCurrent(query,active.pin()))); // reactivation of same bytes cannot resurrect stale preparation
        var engine=new cn.jia.economy.service.impl.MeasuredHistoryEstimateEngine(historyCatalog,(priceScope,v,routes,kinds)->{
            assertEquals(query.scope(),priceScope);assertEquals("r1",v);assertEquals(java.util.Set.of("MODEL_RESPONSE"),kinds);
            var ref=new ModelTokenPrice("fixture","model","tier","test-price","synthetic",Map.of(ModelTokenPrice.Category.INPUT,new BigDecimal("10000")),null,Map.of());
            return new MeasuredTaskRepricing.Reference(v,Map.of("MODEL_RESPONSE",Map.of("INFERENCE",new MeasuredTaskRepricing.TokenRate("fixture",ref))));});
        var prediction=engine.estimate(query,group.execution(),needle,calibrationCatalog.resolve(query,group),Map.of("MODEL_RESPONSE",Map.of("INFERENCE",new cn.jia.economy.pricing.TaskReferencePricing.TokenRoute("fixture","model","tier","fixture"))));assertEquals(65L,prediction.wen());assertEquals(sid,prediction.contributions().getFirst().sampleId());
        var estimator=new cn.jia.economy.service.impl.TaskBudgetEstimatorService(calibrationCatalog,historyCatalog,engine);
        var terms=new cn.jia.economy.task.TaskBudgetEstimator.Terms(group.execution(),Map.of("MODEL_RESPONSE",Map.of("INFERENCE",new cn.jia.economy.pricing.TaskReferencePricing.TokenRoute("fixture","model","tier","fixture"))));
        assertEquals(new cn.jia.economy.task.TaskBudgetEstimator.Unavailable(TaskQuoteBatches.Reason.BILLING_ROUTE_MISSING),estimator.prepare(query,needle,null));
        assertEquals(new cn.jia.economy.task.TaskBudgetEstimator.Unavailable(TaskQuoteBatches.Reason.NO_BASELINE),estimator.prepare(new TaskBillingService.Task(query.scope(),"other","offer"),needle,terms));
        var available=assertInstanceOf(cn.jia.economy.task.TaskBudgetEstimator.Available.class,estimator.prepare(query,needle,terms));assertEquals(65,available.preparation().estimate().wen());
        locked(()->{estimator.lockSelected(query,List.of(available,available));return null;});
        var pointRecordedQuote=verifyHistoryQuoteHttp(study,group,book,query,proxy(estimator,cn.jia.economy.task.TaskBudgetEstimator.class),terms,65,null);
        var quotes=recommendationSql.getMapper(EconomyTaskBillingMapper.class);
        var point=study.confirmedPoint(BILLING_TASK,group,selected,forecast.sha256(),artifacts::get,quotes);
        var mismatch=assertThrows(IllegalArgumentException.class,()->study.confirmedPoint(BILLING_TASK,group,selected,drift.sha256(),driftObjects::get,quotes));
        assertEquals("Quoted rate differs from evaluation book",mismatch.getMessage());
        assertEquals(25L,point.point().predictedWen().longValue());assertEquals(0,new BigDecimal("0.65").compareTo(point.point().truthYuan()));
        var wrong=study.freeze(new cn.jia.economy.service.impl.FrozenCalibrationStudy.OfferedHoldout(before.original(),before.family(),before.groupSha(),before.featureSha(),before.quoteId(),before.agentId(),65,before.referenceSha()));
        assertThrows(IllegalArgumentException.class,()->study.confirmedPoint(BILLING_TASK,group,selected,wrong.sha256(),Map.of(book.sha256(),book.json(),wrong.sha256(),wrong.json())::get,quotes));
        var held=cn.jia.economy.pricing.TaskEstimateEvaluation.Split.HOLDOUT;
        long beforeRead=jdbc.queryForObject("SELECT CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)",Long.class);
        String confirmedQuote=jdbc.queryForObject("SELECT quote_id FROM economy_task_charge WHERE task_id=?",String.class,TASK);
        var joined=observations.freezeConfirmedQuote(BILLING_TASK,group,selected,"frozen-family",held,confirmedQuote,AGENT);
        assertEquals(confirmedQuote,joined.quoteId());assertEquals(AGENT,joined.agentId());
        assertThrows(cn.jia.economy.task.TaskCalibrationObservations.NotEligible.class,()->observations.freezeConfirmedQuote(BILLING_TASK,group,selected,"frozen-family",held,"other-quote",AGENT));
        assertThrows(cn.jia.economy.task.TaskCalibrationObservations.NotEligible.class,()->observations.freezeConfirmedQuote(BILLING_TASK,group,selected,"frozen-family",held,confirmedQuote,"other-agent"));
        var frozen=joined.observation();
        var observation=observations.replay(BILLING_TASK,group,selected,"frozen-family",held,frozen.sha256(),frozen.json());
        assertThrows(IllegalArgumentException.class,()->observations.replay(BILLING_TASK,group,selected,"frozen-family",held,frozen.sha256(),frozen.json()+" "));
        assertThrows(IllegalArgumentException.class,()->observations.replay(BILLING_TASK,group,selected,"other-family",held,frozen.sha256(),frozen.json()));
        assertThrows(IllegalArgumentException.class,()->observations.replay(BILLING_TASK,group,selected,"frozen-family",cn.jia.economy.pricing.TaskEstimateEvaluation.Split.TRAIN,frozen.sha256(),frozen.json()));
        assertThrows(IllegalArgumentException.class,()->observations.replay(query,group,selected,"frozen-family",held,frozen.sha256(),frozen.json()));
        assertEquals(needle,observation.features());assertEquals(group.privateCalibrationGroup(BILLING_TASK),observation.group());assertEquals(selected.revision(),observation.revision());
        assertEquals(measured.operations().stream().map(o->o.fact()).toList(),observation.facts());
        assertTrue(observation.split().truthAt()>=beforeRead);assertEquals(held,observation.split().split());
        assertEquals(jdbc.queryForObject("SELECT q.created_at FROM economy_task_quote q JOIN economy_task_cost_sample s ON s.quote_id=q.quote_id WHERE s.sample_id=?",Long.class,sid).longValue(),observation.split().featuresAt());
        for(var foreign:List.of(query,new TaskBillingService.Task(query.scope(),"other",TASK),new TaskBillingService.Task(new cn.jia.economy.service.EconomyScope(TENANT,"other"),OWNER,TASK)))
            assertThrows(cn.jia.economy.task.TaskCalibrationObservations.NotEligible.class,()->observations.freezeConfirmed(foreign,group,selected,"frozen-family",held));
        assertThrows(cn.jia.economy.task.TaskCalibrationObservations.NotEligible.class,()->observations.freezeConfirmed(BILLING_TASK,group,new cn.jia.economy.task.TaskHistoryCatalog.Revision(sid,selected.revision()+1),"frozen-family",held));
        var recovered=historyCatalog.quantities(query,group,selected);assertEquals(measured.operations().stream().map(o->o.fact()).toList(),recovered);
        assertNull(historyCatalog.quantities(new TaskBillingService.Task(query.scope(),"other","new-task"),group,selected));
        assertTrue(locked(()->historyCatalog.lockCurrent(query,group,selected)));assertEquals(1024,entries.getFirst().features().vector().size());
        assertEquals(65,jdbc.queryForObject("SELECT JSON_LENGTH(operation_refs) FROM economy_task_cost_sample_revision WHERE sample_state='READY'",Integer.class));
        jdbc.update("UPDATE economy_task_cost_sample SET evidence_revision=evidence_revision+1,sample_state='WAITING_EVIDENCE'"); // synthetic source invalidation, journal/CAS tested independently
        assertThrows(cn.jia.economy.task.TaskBudgetEstimator.Stale.class,()->locked(()->{estimator.lockSelected(query,List.of(available));return null;}));
        assertThrows(cn.jia.economy.task.TaskCalibrationObservations.NotEligible.class,()->observations.freezeConfirmed(BILLING_TASK,group,selected,"frozen-family",held));
        assertEquals(observation,observations.replay(BILLING_TASK,group,selected,"frozen-family",held,frozen.sha256(),frozen.json()),"replay preserves the original observation and its time; it is not fresh eligibility");
        assertTrue(historyCatalog.page(query,group,"").isEmpty());assertFalse(locked(()->historyCatalog.lockCurrent(query,group,selected)));assertNull(historyCatalog.quantities(query,group,selected));
        jdbc.update("UPDATE economy_usage_capture_dispatch_group SET route_sha256=REPEAT('3',64) WHERE capture_pk=(SELECT id FROM economy_usage_capture WHERE execution_id='exec64')");
        assertEquals("MIXED_EXECUTION_CONFIG",sampleEvidence.inspect(BILLING_TASK,sid).verdict().name());
        var independentPoint=study.outcomePoint(BILLING_TASK,group,forecast.sha256(),artifacts::get,quotes,sampleEvidence,fixtureDecoder);
        var sealed=independentPoint.source();var pointObjects=new java.util.HashMap<>(artifacts);pointObjects.put(sealed.sha256(),sealed.json());
        var rows=List.of(new cn.jia.economy.service.impl.FrozenCalibrationStudy.ObservedRow("original",TASK,AGENT,forecast.sha256(),"MEASURED",sealed.sha256()),
                new cn.jia.economy.service.impl.FrozenCalibrationStudy.ObservedRow("duplicate-observation",TASK,AGENT,forecast.sha256(),"MEASURED",sealed.sha256()),
                new cn.jia.economy.service.impl.FrozenCalibrationStudy.ObservedRow("no-response","unquoted",AGENT,null,"NOT_EXECUTED",null));
        var roster=study.freeze(new cn.jia.economy.service.impl.FrozenCalibrationStudy.ObservedRoster("budget-evaluation-roster-v1","1".repeat(64),"2".repeat(64),"3".repeat(64),book.sha256(),rows,new cn.jia.economy.service.impl.FrozenCalibrationStudy.BootstrapPlan(17,Map.of(TASK,before.family(),"unquoted","other-family"))));pointObjects.put(roster.sha256(),roster.json());
        var errors=study.scoreObserved(BILLING_TASK.scope(),OWNER,roster.sha256(),pointObjects::get);assertEquals(3,errors.candidateRequests());assertEquals(1,errors.missingTruthRequests());assertEquals(2,errors.pairedRequests());
        assertEquals(1,errors.intervals().families());assertEquals(0,errors.intervals().draws());assertEquals(2,errors.byPricedGroup().get(group.digest()).pricedRequests());assertNull(errors.intervals().wape().low());
        var metric=errors.uniqueTaskErrors();assertEquals(1,metric.positiveQuoted());assertEquals(1,metric.underestimates());
        var pointErrorRatio=new cn.jia.economy.pricing.TaskEstimateEvaluation.Ratio(new BigDecimal("0.40"),new BigDecimal("0.65"));assertEquals(0,pointErrorRatio.compareTo(metric.wape()));assertEquals(0,pointErrorRatio.compareTo(metric.p90Relative()));
        assertThrows(IllegalArgumentException.class,()->study.scoreObserved(BILLING_TASK.scope(),"foreign",roster.sha256(),pointObjects::get));
        var noTruth=study.freeze(new cn.jia.economy.service.impl.FrozenCalibrationStudy.ObservedRoster("budget-evaluation-roster-v1","1".repeat(64),"2".repeat(64),"3".repeat(64),book.sha256(),List.of(
                new cn.jia.economy.service.impl.FrozenCalibrationStudy.ObservedRow("unmeasured",TASK,AGENT,forecast.sha256(),"USAGE_INCOMPLETE",null)),null));
        pointObjects.put(noTruth.sha256(),noTruth.json());var missingErrors=study.scoreObserved(BILLING_TASK.scope(),OWNER,noTruth.sha256(),pointObjects::get);
        assertEquals(1,missingErrors.pricedRequests());assertEquals(0,missingErrors.pairedRequests());assertNull(missingErrors.uniqueTaskErrors().wape());assertNull(missingErrors.uniqueTaskErrors().p90Relative());
        var zero=cn.jia.economy.pricing.TaskEstimateEvaluation.pairedErrors(List.of(new cn.jia.economy.pricing.TaskEstimateEvaluation.Prediction(TASK,BigDecimal.ZERO,25L)));
        assertEquals(1,zero.zeroQuoted());assertEquals(0,new BigDecimal("0.25").compareTo(zero.zeroAbsoluteYuanSum()));assertNull(zero.wape());assertNull(zero.p90Relative());
        assertEquals(point.point(),independentPoint.point());assertEquals(65,new ObjectMapper().readTree(independentPoint.source().json()).path("operations").size());
        assertThrows(IllegalArgumentException.class,()->study.outcomePoint(query,group,forecast.sha256(),artifacts::get,quotes,sampleEvidence,fixtureDecoder));
        assertNull(sampleEvidence.measureOutcome(query,before.quoteId(),AGENT,group.digest(),before.featureSha(),fixtureDecoder));
        assertTrue(sampleProjection.refresh(BILLING_TASK,sid,fixtureDecoder));assertEquals(0,count("economy_history_feature_projection"));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM economy_task_cost_sample_revision WHERE sample_state='EXCLUDED' AND reason_code='MIXED_EXECUTION_CONFIG'",Integer.class));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM economy_task_cost_sample_revision WHERE sample_state='READY'",Integer.class));
        jdbc.update("UPDATE economy_task_cost_sample SET evidence_revision=evidence_revision+1,sample_state='WAITING_EVIDENCE'");
        jdbc.update("UPDATE economy_usage_capture SET input_revision=2 WHERE execution_id='exec64'");
        assertEquals("USAGE_INCOMPLETE",sampleEvidence.inspect(BILLING_TASK,sid).verdict().name());assertEquals("WAITING_EVIDENCE",jdbc.queryForObject("SELECT sample_state FROM economy_task_cost_sample",String.class));
        assertEquals("USAGE_INCOMPLETE",assertThrows(cn.jia.economy.service.impl.FrozenCalibrationStudy.MissingOutcome.class,()->study.outcomePoint(BILLING_TASK,group,forecast.sha256(),artifacts::get,quotes,sampleEvidence,fixtureDecoder)).getMessage());
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
        pointRecordedQuote.call(); // An already published price is not recomputed from the now-ineligible training row.
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

    private void prepareNativeExecutionSchema() {
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
        prepareNativeExecutionSchema();
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

    private cn.jia.agent.api.AgentController directController;
    @Test void directAssignmentPreservesSignedReplayAndSingleHold() throws Exception {
        directController=new cn.jia.agent.api.AgentController(mock(AgentService.class),mock(AbilityEvaluationService.class));
        directController.setTaskExecutionGrants(assignment);startHttp();
        var params=new java.util.LinkedHashMap<String,Object>();params.put("workflowVersion",2);params.put("businessAction","assign_and_start");
        params.put("expectedTaskVersion",0);params.put("requirementRevision",1);params.put("agentId",AGENT);params.put("quoteId",quoteId);
        params.put("recommendationId","recommendation-1");params.put("requestedOperations",List.of("DELIBERATE","EXECUTE_SKILL"));
        params.put("initialOperation","DELIBERATE");params.put("inputRefs",List.of());
        var json=new ObjectMapper();String token=httpToken();
        try(var client=HttpClient.newHttpClient()){
            class Send {tools.jackson.databind.JsonNode post(String key,int status,String code)throws Exception{
                var request=HttpRequest.newBuilder(URI.create(origin+"/agent/tasks/"+TASK+"/assign")).header("Content-Type","application/json").header("Authorization","Bearer "+token).header("Idempotency-Key",key).header("X-Request-Id","direct-assign").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("protocolVersion",1,"requestId","direct-assign","data",params)))).build();
                var response=client.send(request,HttpResponse.BodyHandlers.ofString());assertEquals(status,response.statusCode(),response.body());
                assertTrue(response.headers().allValues("Cache-Control").stream().anyMatch(v->v.contains("no-store")));
                var env=json.readTree(response.body());assertEquals(code,env.path("code").asText());assertEquals("direct-assign",env.path("requestId").asText());return env.path("data");
            }}var send=new Send();
            httpPost("/assign",json.writeValueAsString(params),"direct",false,401);assertAssignmentAbsent();
            var first=send.post("direct",200,"E0");assertFalse(first.path("grantId").asText().isBlank());
            assertEquals(first,send.post("direct",200,"E0"));
            params.put("initialOperation","EXECUTE_SKILL");send.post("direct",409,"AGENT_GRANT_IDEMPOTENCY_CONFLICT");
            assertEquals(50,wallet(independent));assertEquals("HELD",charge());assertEquals(1,count("economy_transaction"));assertEquals(1,count("agent_task_execution_grant"));assertEquals(1,count("agent_task_bounty_bootstrap_outbox"));
        }
    }

    /** Independent listeners and application services; SQL, signing authority and external test observations are shared. */
    private final class AssignmentNode implements AutoCloseable {
        final Tomcat server=new Tomcat(); final AnnotationConfigWebApplicationContext app=new AnnotationConfigWebApplicationContext();
        cn.jia.agent.api.AssigneeRecommendationController recommendation;
        NativeTaskPreparationService selection;
        String base;
        void start(String name) throws Exception {start(name,null);}
        void start(String name,cn.jia.agent.api.IndependentTaskQuoteController quote) throws Exception {
            Path dir=Files.createDirectory(httpDirectory.resolve(name));server.setBaseDir(dir.toString());server.setPort(0);
            server.getConnector().setProperty("address","127.0.0.1");
            var context=server.addContext("",Files.createDirectory(dir.resolve("webroot")).toString());context.setParentClassLoader(getClass().getClassLoader());
            app.setServletContext(context.getServletContext());
            app.register(HttpApp.class);
            var service=newAssignment.get();var controller=new cn.jia.agent.api.AgentController(mock(AgentService.class),mock(AbilityEvaluationService.class));controller.setTaskExecutionGrants(service);
            var decoder=NimbusJwtDecoder.withSecretKey(new SecretKeySpec(signingKey,"HmacSHA256")).macAlgorithm(MacAlgorithm.HS256).build();decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer("cyf-agent-billing-fixture"));
            app.addBeanFactoryPostProcessor(f->{f.registerSingleton("decoder",decoder);f.registerSingleton("direct",controller);if(quote!=null)f.registerSingleton("quotes",quote);
                if(selection!=null)f.registerSingleton("selection",new cn.jia.agent.api.TaskInitialActionController(selection));
                if(recommendation!=null)f.registerSingleton("recommendation",recommendation);
                f.registerSingleton("point",new AgentTaskDeliberationOperationController(operationReads,service));
                f.registerSingleton("cancel",new AgentTaskCancellationController(cancellation));});
            app.refresh();
            installDispatcher(context,app);server.start();base="http://127.0.0.1:"+server.getConnector().getLocalPort();
        }
        public void close() throws Exception {try {if(base!=null)server.stop();}finally{try{server.destroy();}finally{app.close();}}}
    }
    @Test void directAssignmentAcrossNodesAndRestartHasSingleHold() throws Exception {
        signingKey=new byte[32];new SecureRandom().nextBytes(signingKey);String token=httpToken();var json=new ObjectMapper();
        var params=Map.of("workflowVersion",2,"businessAction","assign_and_start","expectedTaskVersion",0,"requirementRevision",1,"agentId",AGENT,"quoteId",quoteId,"recommendationId","recommendation-1","requestedOperations",List.of("DELIBERATE","EXECUTE_SKILL"),"initialOperation","DELIBERATE","inputRefs",List.of());
        String body=json.writeValueAsString(Map.of("protocolVersion",1,"requestId","node-cutover","data",params));
        try(var a=new AssignmentNode();var client=HttpClient.newHttpClient()) {
            class Send {tools.jackson.databind.JsonNode post(AssignmentNode node,String key,int status,String code)throws Exception {
                var req=HttpRequest.newBuilder(URI.create(node.base+"/agent/tasks/"+TASK+"/assign")).header("Content-Type","application/json").header("Authorization","Bearer "+token).header("Idempotency-Key",key).header("X-Request-Id","node-cutover").POST(HttpRequest.BodyPublishers.ofString(body)).build();
                var res=client.send(req,HttpResponse.BodyHandlers.ofString());assertEquals(status,res.statusCode(),res.body());var env=json.readTree(res.body());assertEquals(code,env.path("code").asText());assertEquals("node-cutover",env.path("requestId").asText());return env.path("data");
            }}var send=new Send();a.start("a");tools.jackson.databind.JsonNode first;
            try(var b=new AssignmentNode()){b.start("b");assertNotEquals(a.base,b.base);assertAssignmentAbsent();first=send.post(b,"original",200,"E0");assertFalse(first.path("grantId").asText().isBlank());}
            try(var replacement=new AssignmentNode()){replacement.start("replacement");assertNotEquals(a.base,replacement.base);
                for(var node:List.of(a,replacement)){assertEquals(first,send.post(node,"original",200,"E0"));}}
            assertEquals(50,wallet(independent));assertEquals("HELD",charge());assertEquals(1,count("economy_transaction"));assertEquals(1,count("agent_task_execution_grant"));assertEquals(1,count("agent_task_bounty_bootstrap_outbox"));
        }
    }

    @Test void recommendationReadsAcrossNodesWithoutWalletWrites() throws Exception {
        signingKey=new byte[32];new SecureRandom().nextBytes(signingKey);String token=httpToken();var json=new ObjectMapper();
        jdbc.update("INSERT INTO agent_task_meta(task_id,owner_jiacn,tenant_id,client_id) VALUES('other-rec','foreign-owner',?,?)",TENANT,CLIENT);
        try(var a=new AssignmentNode();var b=new AssignmentNode();var client=HttpClient.newHttpClient()){
            var first=cn.jia.agent.service.recommendation.RecommendationHttpTestWiring.readOnly(roots,TASK);
            var second=cn.jia.agent.service.recommendation.RecommendationHttpTestWiring.readOnly(roots,TASK);
            a.recommendation=first.controller();b.recommendation=second.controller();a.start("a");b.start("b");assertNotEquals(a.base,b.base);
            for(var node:List.of(a,b))for(var task:List.of(TASK,"other-rec","missing-rec"))for(var version:List.of("0","999")){
                String id="recommend-stop";String body=json.writeValueAsString(Map.of("protocolVersion",1,"requestId",id,"data",Map.of("expectedTaskVersion",version)));
                var req=HttpRequest.newBuilder(URI.create(node.base+"/agent/tasks/"+task+"/recommend")).header("Authorization","Bearer "+token).header("Content-Type","application/json").header("X-Request-Id",id).POST(HttpRequest.BodyPublishers.ofString(body)).build();
                var res=client.send(req,HttpResponse.BodyHandlers.ofString());boolean visible=task.equals(TASK);assertEquals(visible?(version.equals("0")?200:409):404,res.statusCode(),res.body());var env=json.readTree(res.body());
                assertEquals(visible?(version.equals("0")?"E0":"RECOMMENDATION_STALE"):"RECOMMENDATION_NOT_FOUND",env.path("code").asText());assertEquals(id,env.path("requestId").asText());
                if(visible&&version.equals("0")){assertEquals("NO_CANDIDATES",env.at("/data/state").asText());assertEquals(0,env.at("/data/recommendations").size());}
                else assertTrue(env.at("/data/recommendations").isMissingNode());
                String cache=String.join(",",res.headers().allValues("Cache-Control"));assertTrue(cache.contains("private")&&cache.contains("no-store"),cache);
            }
            first.verifyReads().run();second.verifyReads().run();
            assertEquals(100,wallet(independent));assertEquals(0,count("economy_transaction"));assertEquals(0,count("agent_task_execution_grant"));
            assertEquals(0,count("agent_task_bounty_bootstrap_outbox"));assertEquals(1,count("economy_task_quote"));
            assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM agent_task_meta WHERE task_id IN (?,?) AND task_version=0 AND reward_status='open'",Integer.class,TASK,"other-rec"));
        }
    }
    @Test void pointAssignmentAcrossNodesReplaysReadsAndCancelsOriginalGrant() throws Exception {
        prepareCancellation();signingKey=new byte[32];new SecureRandom().nextBytes(signingKey);String token=httpToken();var json=new ObjectMapper();
        String point="/point-and-deliberate";
        try(var client=HttpClient.newHttpClient();var a=new AssignmentNode()) {
            class Wire {tools.jackson.databind.JsonNode send(AssignmentNode node,String route,String key,String data,int status,String code)throws Exception {
                var req=HttpRequest.newBuilder(URI.create(node.base+"/agent/tasks/"+TASK+route)).header("Authorization","Bearer "+token).header("Idempotency-Key",key).header("X-Request-Id","point-cutover");
                if(data!=null)req.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("protocolVersion",1,"requestId","point-cutover","data",json.readTree(data)))));
                var res=client.send(req.build(),HttpResponse.BodyHandlers.ofString());assertEquals(status,res.statusCode(),res.body());var env=json.readTree(res.body());assertEquals(code,env.path("code").asText());assertEquals("point-cutover",env.path("requestId").asText());
                if(status==503){var cache=String.join(",",res.headers().allValues("Cache-Control"));assertTrue(cache.contains("private")&&cache.contains("no-store"),cache);}return env.path("data");
            }}var wire=new Wire();a.start("a");tools.jackson.databind.JsonNode original;
            try(var open=new AssignmentNode()){open.start("open");assertAssignmentAbsent();original=wire.send(open,point,"point",pointBody(),200,"E0");assertFalse(original.path("grantId").asText().isBlank());}
            try(var b=new AssignmentNode()){b.start("b");for(var node:List.of(a,b)){
                assertEquals(original,wire.send(node,point,"point",pointBody(),200,"E0"));assertEquals(original,wire.send(node,point+"/request","point",null,200,"E0"));}
                assertEquals(50,wallet(independent));assertEquals(1,count("economy_transaction"));assertEquals(1,count("agent_task_execution_grant"));assertEquals(1,count("agent_task_bounty_bootstrap_outbox"));
                prepareRetry();for(var node:List.of(b,a)){wire.send(node,"/cancel","cancel-point","{\"expectedTaskVersion\":\"1\"}",200,"E0");assertCancelled();}
            }
        }
    }

    @Test void assignmentAuthenticatesAndPreservesReplayAndCancellation() throws Exception {
        prepareCancellation(); startHttp();

        httpPost("/point-and-deliberate",pointBody(),"assign-http",false,401);
        assertAssignmentAbsent();

        pointHttp(200);
        pointHttp(200);
        assertEquals(1,count("economy_transaction")); assertEquals(1,count("agent_task_execution_grant"));
        prepareRetry(); httpPost("/cancel","{\"expectedTaskVersion\":\"1\"}","cancel-http",true,200); assertCancelled();
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
        failBootstrap = true; assertAssignmentAbsent();
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
    private cn.jia.agent.api.TaskInitialActionController initialSelectionController;
    /** The archived sample, cutoff, candidate and terms are deliberately synthetic. Test real HTTP/SQL/commit/replay, not accuracy. */
    private java.util.concurrent.Callable<Void> verifyHistoryQuoteHttp(cn.jia.economy.service.impl.FrozenCalibrationStudy study,cn.jia.economy.task.TaskHistoryCatalog.Group group,cn.jia.economy.service.impl.FrozenCalibrationStudy.Artifact book,TaskBillingService.Task newTask,cn.jia.economy.task.TaskBudgetEstimator estimator,
                                        cn.jia.economy.task.TaskBudgetEstimator.Terms terms,long expectedWen,AttachedQuote attached) throws Exception {
        String id=newTask.taskId();jdbc.update("INSERT INTO agent_task_meta(task_id,owner_jiacn,tenant_id,client_id) VALUES(?,?,?,?)",id,OWNER,TENANT,CLIENT);
        var authority=quoteAndRecommendationAuthority();var req=authority.req();String agent="agt_00000000000000000000000000000002";
        jdbc.update("INSERT INTO agent_persona_binding(jiacn,persona_code,agent_id,bound_at,status,tenant_id,client_id) VALUES(?,?,?,1,1,?,?)",OWNER,"quote-point",agent,TENANT,CLIENT);
        long binding=jdbc.queryForObject("SELECT id FROM agent_persona_binding WHERE agent_id=?",Long.class,agent);
        jdbc.update("INSERT INTO agent_identity_registry(canonical_agent_id,canonical_type,lifecycle_status,client_id,owner_jiacn,tenant_id,binding_id,provisioned_at,activated_at,audit_reason,create_time,update_time) VALUES (?,'OPAQUE','ACTIVE',?,?,?,?,1,1,'fixture',1,1)",agent,CLIENT,OWNER,TENANT,binding);
        var requirement=roots.executeWithLockedTaskRootInOwnerScope(TENANT,CLIENT,OWNER,id,r->req.captureOnCreate(SCOPE,id,"Fixture title","Fixture description"));
        var storage=mock(PersonalWorkspaceStorage.class);var writes=mock(PersonalWorkspaceWriteService.class);
        cn.jia.agent.service.recommendation.RecommendationMaterials materials;
        if(attached==null)materials=mock(cn.jia.agent.service.recommendation.RecommendationMaterials.class);
        else{var workspace=quoteWorkspace();ReflectionTestUtils.setField(assignment,"taskLinks",workspace.links());ReflectionTestUtils.setField(assignment,"workspace",workspace.files());attachQuoteFile(id,105);
            materials=new cn.jia.agent.service.recommendation.RecommendationMaterials(proxy(new PersonalWorkspaceTaskLinkServiceImpl(workspace.links()),PersonalWorkspaceTaskLinkService.class),new PersonalWorkspaceServiceImpl(workspace.files(),storage,writes));}
        var inputs=roots.executeWithLockedTaskRootInOwnerScope(TENANT,CLIENT,OWNER,id,r->assignment.snapshotTaskInputsWithinLockedTask(SCOPE,id));
        // Only the additional 108-member performance roster is synthetic; the selected candidate uses real policy/identity reads.
        var agents=mock(AgentExternalServicePolicyService.class);when(agents.quoteTargets(eq(SCOPE),eq(id),eq(List.of(agent)),anyBoolean())).thenAnswer(i->authority.policies().quoteTargets(SCOPE,id,List.of(agent),i.getArgument(3)));
        var targets=authority.policies().quoteTargets(SCOPE,id,List.of(agent),false);assertEquals(1,targets.size());assertEquals(binding,targets.getFirst().binding());
        for(String f:List.of("agent-task-native-preparation-v1.sql","agent-task-native-initial-admission-v1.sql","agent-task-native-selection-receipt-v1.sql"))new ResourceDatabasePopulator(new FileSystemResource(DDL.resolve(f))).execute(jdbc.getDataSource());
        var decisions=new NativeTaskPreparationService(jdbc,roots,req,assignment,authority.policies(),new ObjectMapper());decisions.setSelectionEvents(mock(org.springframework.context.ApplicationEventPublisher.class));
        initialSelectionController=new cn.jia.agent.api.TaskInitialActionController(decisions);
        var source=mock(cn.jia.agent.service.recommendation.ProspectiveTaskExecution.class);
        var subject=new cn.jia.agent.service.recommendation.ProspectiveTaskExecution.Inputs(newTask,"0",1,requirement.sha256(),inputs.digest());
        var calls=new java.util.concurrent.atomic.AtomicInteger();var encoder=new cn.jia.agent.service.TaskFeatureEncoder(){
            public Identity identity(){return new Identity("cfg","fixture",1024);}
            public Vector encode(String text){calls.incrementAndGet();float[] vec=new float[1024];vec[0]=1;
                if(attached!=null){assertTrue(text.contains("file-105"));assertFalse(text.contains("private:v"));assertFalse(text.contains("b".repeat(64)));for(int i=0;i<vec.length;i++)vec[i]=attached.reference().vector().get(i);}
                return new Vector(identity(),vec);}
        };
        var profile=new QuoteProfile();boolean diagnostic=Boolean.getBoolean("cyf.measuredBatchQuoteLoad");
        java.util.function.Supplier<cn.jia.agent.api.IndependentTaskQuoteController> quoteNode=()->cn.jia.agent.service.recommendation.QuoteHttpTestWiring.controller(roots,batches,req,assignment,agents,
                materials,diagnostic?profile.wrap(TaskBillingService.class,billing,java.util.Set.of("storeOffersBatch")):billing);
        if(diagnostic)independentQuoteController=quoteNode.get(); // Preserve single-listener timing for the load diagnostic.
        startHttp();int money=count("economy_transaction"),batchCount=count("economy_task_quote_batch"),fixed=count("economy_task_quote");
        var hot=new java.util.ArrayList<Long>();String firstId=null,firstBatch=null;long cold=0,replay=0;
        String token=httpToken();var mapper=new ObjectMapper();
        var selection=URI.create(origin+"/agent/tasks/"+id+"/initial-actions/"+agent);
        try(var client=HttpClient.newHttpClient()){
            var get=HttpRequest.newBuilder(selection).header("Authorization","Bearer "+token).header("X-Request-Id","select-history");
            var current=client.send(get.GET().build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,current.statusCode(),current.body());
            String version=mapper.readTree(current.body()).at("/data/selectionVersion").asText();
            var put=get.header("Content-Type","application/json").header("Idempotency-Key","history-choice").PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of("protocolVersion",1,"requestId","select-history","data",Map.of("selectionVersion",version,"choice","LOCAL_ANSWER"))))).build();
            var chosen=client.send(put,HttpResponse.BodyHandlers.ofString());assertEquals(200,chosen.statusCode(),chosen.body());assertEquals("LOCAL_ANSWER",mapper.readTree(chosen.body()).at("/data/choice").asText());
        }
        var frozen=decisions.find(new NativeTaskPreparation.Binding(SCOPE,id,0,1,requirement.sha256(),inputs.digest(),targets.getFirst()));assertNotNull(frozen);
        var proofs=Map.of(agent,new cn.jia.agent.service.recommendation.ProspectiveTaskExecution.Evidence("fixture-fixed",terms,frozen.sha256()));
        when(source.observe(subject,targets)).thenReturn(proofs);when(source.current(subject,targets,proofs)).thenReturn(true);
        var privateKeys=List.of(java.util.UUID.randomUUID().toString(),java.util.UUID.randomUUID().toString());
        var window=QuoteWireWindow.paid(id,agent,group.digest(),book.sha256(),privateKeys);
        TaskQuoteBatches.Result observedFirst=null,observedOther=null;
        try(var client=HttpClient.newHttpClient();var open=diagnostic?null:new AssignmentNode()){
            if(open!=null)open.start("quote-open",quoteNode.get());String quoteOrigin=open==null?origin:open.base;
            for(int i=0;i<32;i++){
                String key=i==31?privateKeys.get(0):i<2?privateKeys.get(i):"fixture-"+i;
                String rawBody="{\"protocolVersion\":1,\"requestId\":\"quote-http\",\"data\":{\"expectedTaskVersion\":\"0\",\"agentIds\":[\""+agent+"\"]}}";
                var request=HttpRequest.newBuilder(URI.create(quoteOrigin+"/agent/tasks/"+id+"/quotes")).header("Content-Type","application/json")
                    .header("X-Request-Id","quote-http").header("Authorization","Bearer "+token).header("Idempotency-Key",key)
                    .POST(HttpRequest.BodyPublishers.ofString(rawBody )).build();
                long sent=window==null?0:window.elapsed();long t=System.nanoTime();var response=client.send(request,HttpResponse.BodyHandlers.ofString());long elapsed=System.nanoTime()-t;
                if(window!=null&&i<2){window.received(request,rawBody,sent,window.elapsed(),response);if(i==1)window.seal();}
                assertEquals(200,response.statusCode(),response.body());var envelope=new ObjectMapper().readTree(response.body());assertEquals("E0",envelope.path("code").asText());
                if(i==0||i==1){var data=mapper.treeToValue(envelope.path("data"),TaskQuoteBatches.Result.class);if(i==0)observedFirst=data;else observedOther=data;}
                var item=envelope.at("/data/quotes/0");assertEquals("AVAILABLE",item.path("state").asText());assertEquals(Long.toString(expectedWen),item.at("/amount/amountMinor").asText());
                if(i==0){firstId=item.path("quoteId").asText();firstBatch=item.path("recommendationId").asText();assertEquals(agent,item.path("agentId").asText());assertFalse(firstId.isBlank());cold=elapsed;}
                else if(i==31){assertEquals(firstId,item.path("quoteId").asText());replay=elapsed;}
                else {assertNotEquals(firstId,item.path("quoteId").asText());hot.add(elapsed);}
            }
        }
        if(!diagnostic){
            long balance=wallet(independent);int charges=count("economy_task_charge");var original=observedFirst;
            String foreign=id+"-foreign";jdbc.update("INSERT INTO agent_task_meta(task_id,owner_jiacn,tenant_id,client_id) VALUES(?,?,?,?)",foreign,"other-owner",TENANT,CLIENT);
            try(var a=new AssignmentNode();var b=new AssignmentNode();var client=HttpClient.newHttpClient()){
                a.start("quote-peer-a",quoteNode.get());b.start("quote-peer-b",quoteNode.get());assertNotEquals(a.base,b.base);
                class Wire {void post(AssignmentNode node,String task,String key,String version,int status,String code)throws Exception{
                    var data=Map.of("expectedTaskVersion",version,"agentIds",List.of(agent));String rid="quote-cutover";
                    var request=HttpRequest.newBuilder(URI.create(node.base+"/agent/tasks/"+task+"/quotes")).header("Content-Type","application/json").header("Authorization","Bearer "+token).header("Idempotency-Key",key).header("X-Request-Id",rid).POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of("protocolVersion",1,"requestId",rid,"data",data)))).build();
                    var response=client.send(request,HttpResponse.BodyHandlers.ofString());assertEquals(status,response.statusCode(),response.body());var env=mapper.readTree(response.body());assertEquals(code,env.path("code").asText());assertEquals(rid,env.path("requestId").asText());String cache=String.join(",",response.headers().allValues("Cache-Control"));assertTrue(cache.contains("private")&&cache.contains("no-store"),cache);
                    if(status==200)assertEquals(original,mapper.treeToValue(env.path("data"),TaskQuoteBatches.Result.class));
                }}var wire=new Wire();for(var node:List.of(a,b)){
                    wire.post(node,foreign,privateKeys.getFirst(),"0",404,"QUOTE_NOT_FOUND");wire.post(node,id,"fresh-stale","999",409,"QUOTE_STALE");
                    wire.post(node,id,privateKeys.getFirst(),"1",409,"QUOTE_IDEMPOTENCY_CONFLICT");wire.post(node,id,privateKeys.getFirst(),"0",200,"E0");
                }
            }
            assertEquals(balance,wallet(independent));assertEquals(charges,count("economy_task_charge"));
        }
        if(attached!=null){String snapshot=jdbc.queryForObject("SELECT snapshot_json FROM economy_task_quote_resolved WHERE quote_id=?",String.class,firstId);
            var raw=mapper.readTree(snapshot);var feature=mapper.treeToValue(raw.at("/preparation/features"),TaskEstimateFeatures.class);
            assertEquals(requirement.sha256(),feature.requirementSha256());assertEquals(inputs.digest(),feature.inputSnapshotSha256());assertEquals(attached.reference().files(),feature.files());assertNull(feature.requestedDeliverables());assertEquals(feature.digest(),raw.path("featureSha256").asText());
            assertEquals(mapper.readTree(mapper.writeValueAsString(attached.expected())),raw.at("/preparation/estimate"));verifyNoInteractions(storage,writes);
        }
        assertEquals(frozen.sha256(),jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(snapshot_json,'$.initialIntentSha256')) FROM economy_task_quote WHERE quote_id=?",String.class,firstId));
        assertEquals(1,calls.get());verify(source,times(31)).observe(subject,targets);verify(source,times(31)).current(subject,targets,proofs);
        assertEquals(batchCount+31,count("economy_task_quote_batch"));assertEquals(fixed+31,count("economy_task_quote"));assertEquals(money,count("economy_transaction"));
        hot.sort(Long::compare);System.out.printf(java.util.Locale.ROOT,"MEASURED_QUOTE_HTTP coldFixtureMs=%.3f hotCount=30 p50ms=%.3f p95ms=%.3f maxms=%.3f replayMs=%.3f fixtureEncodes=%d%n",
                cold/1e6,hot.get(14)/1e6,hot.get(28)/1e6,hot.getLast()/1e6,replay/1e6,calls.get());
        if(Boolean.getBoolean("cyf.measuredBatchQuoteLoad"))measureBatchQuoteHttp(id,token,agents,source,subject,terms,calls,profile,expectedWen);
        if(Boolean.getBoolean("cyf.denseQuoteConcurrency") && id.startsWith("dense-http-")) {
            // Keep the serial baseline above. This adds distinct intentions on the same task/root and hot feature.
            var multi=java.util.stream.IntStream.range(0,108).mapToObj(i->String.format(java.util.Locale.ROOT,"q-target-%03d",i)).toList();
            for(var ids:List.of(List.of(agent),multi))for(int lanes:List.of(5,10))
                concurrentDenseQuotes(id,token,ids,lanes,calls,profile,expectedWen);
        }
        if(window!=null){String export=Files.readString(window.directory.resolve("roster.json"))+Files.readString(window.directory.resolve("capture.json"));
            for(String raw:privateKeys)assertFalse(export.contains(raw),"transport artifacts retain only key digests");}
        var key=new TaskQuoteBatches.ObservationKey(newTask,PAYER,QuoteWireWindow.digest(privateKeys.get(0)));var intent=new TaskQuoteBatches.Intent("0",List.of(agent));
        var quotes=recommendationSql.getMapper(EconomyTaskBillingMapper.class);
        var forecast=study.publishedOffer(key,intent,observedFirst,agent,"history-http",group,book,batches,quotes);
        if(window!=null){var second=study.publishedOffer(new TaskQuoteBatches.ObservationKey(newTask,PAYER,QuoteWireWindow.digest(privateKeys.get(1))),intent,observedOther,agent,"history-http",group,book,batches,quotes);
            for(var entry:Map.of("fixture-0",forecast,"fixture-1",second,"reference",book).entrySet())Files.writeString(window.directory.resolve(entry.getKey()+".json"),entry.getValue().json(),java.nio.file.StandardOpenOption.CREATE_NEW);}
        var other=observedOther;assertThrows(IllegalArgumentException.class,()->study.publishedOffer(key,intent,other,agent,"history-http",group,book,batches,quotes));
        assertThrows(IllegalArgumentException.class,()->study.publishedOffer(key,intent,null,agent,"history-http",group,book,batches,quotes));
        var objects=Map.of(book.sha256(),book.json(),forecast.sha256(),forecast.json());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM economy_task_charge WHERE task_id=?",Integer.class,id));
        String fixedQuote=firstId,batchId=firstBatch;
        return ()->{
            ReflectionTestUtils.setField(assignment,"requirementSnapshots",req);ReflectionTestUtils.setField(operationReads,"requirements",req);
            var actualAuthority=new TaskAssigneeAuthorityServiceImpl(new AgentExternalServicePolicyDaoImpl(recommendationSql.getMapper(AgentExternalServicePolicyMapper.class)),authority.identities(),new AgentTaskExecutionGrantDaoImpl(recommendationSql.getMapper(AgentTaskExecutionGrantMapper.class)));
            assignment.setAssigneeAuthority(actualAuthority);ReflectionTestUtils.setField(assignment,"identities",authority.identities());
            ReflectionTestUtils.setField(ReflectionTestUtils.getField(assignment,"legacyAssignments"),"identityService",authority.identities());
            assignment.setDeliberationRuntime(e->e.equals(new TaskDeliberationRuntimeLookup.Scope(TENANT,CLIENT,OWNER,agent)));
            int posts=count("economy_transaction"),grants=count("agent_task_execution_grant");long balance=wallet(independent);
            try(var client=HttpClient.newHttpClient()){
                class Point{tools.jackson.databind.JsonNode post(String batch,int expected)throws Exception{
                    var body=Map.of("targetAgentId",agent,"expectedTaskVersion",subject.taskVersion(),"requirementRevision",Long.toString(subject.requirementRevision()),"quoteId",fixedQuote,"recommendationId",batch);
                    var request=HttpRequest.newBuilder(URI.create(origin+"/agent/tasks/"+id+"/point-and-deliberate")).header("Authorization","Bearer "+token).header("X-Request-Id","hold").header("Idempotency-Key","history-point").header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of("protocolVersion",1,"requestId","hold","data",body)))).build();
                    var response=client.send(request,HttpResponse.BodyHandlers.ofString());assertEquals(expected,response.statusCode(),response.body());return mapper.readTree(response.body()).path("data");
                }}var point=new Point();point.post("different-batch",409);assertEquals(balance,wallet(independent));assertEquals(grants,count("agent_task_execution_grant"));
                // The signed quote was trained on another task in this very partition. Its fixture
                // uses its own immutable requirement reader. Claim it first in actual FIFO order.
                long claimTime = System.currentTimeMillis();
                var previousDiscussion = bootstrap.claimNext(SCOPE, "history-fixture", claimTime);
                assertNotNull(previousDiscussion); assertEquals(TASK, previousDiscussion.taskId());
                assertNotEquals(id, previousDiscussion.taskId());
                var receipt=point.post(batchId,200);assertEquals(receipt,point.post(batchId,200));assertEquals(agent,receipt.path("targetAgentId").asText());assertEquals(id,receipt.path("taskId").asText());
                assertEquals(balance-expectedWen,wallet(independent));assertEquals(posts+1,count("economy_transaction"));assertEquals(grants+1,count("agent_task_execution_grant"));
                var absent=assertThrows(cn.jia.economy.service.impl.FrozenCalibrationStudy.MissingOutcome.class,()->study.outcomePoint(newTask,group,forecast.sha256(),objects::get,quotes,sampleEvidence,textFixture(r->{throw new AssertionError("Active task has no terminal cost");})));
                assertEquals("ACTIVE",absent.getMessage());
                if(window!=null){var selected=jdbc.queryForObject("SELECT quote_id FROM economy_task_charge WHERE tenant_id=? AND client_id=? AND owner_jiacn=? AND task_id=?",String.class,TENANT,CLIENT,OWNER,id);
                    assertEquals(fixedQuote,selected);Files.writeString(window.directory.resolve("as-of.json"),mapper.writeValueAsString(Map.of("taskId",id,"agentId",agent,"selectedQuoteId",selected,"lifecycle",absent.getMessage(),"rosterSha256",window.rosterSha)),java.nio.file.StandardOpenOption.CREATE_NEW);}

                // The point-and-deliberate boundary yields a discussion, not an execution step.
                var relaySource = new AgentTaskBountyBootstrapOutboxServiceImpl(outbox, new ObjectMapper(), manager,
                        req, new cn.jia.core.security.FixedTenantAdmissionPolicy(TENANT, CLIENT));

                var discussion = relaySource.claimNextAvailable("quote-chat-fixture", claimTime);
                assertNotNull(discussion);
                assertEquals("DELIBERATE", discussion.permittedOperation());
                assertEquals(TENANT, discussion.tenantId()); assertEquals(CLIENT, discussion.clientId());
                assertEquals(OWNER, discussion.ownerJiacn()); assertEquals(id, discussion.taskId());
                assertEquals(agent, discussion.targetAgentId()); assertEquals(subject.requirementRevision(), discussion.requirementRevision());
                assertEquals(receipt.path("grantId").asText(), discussion.grantId());
                assertEquals(receipt.path("grantVersion").asLong(), discussion.grantVersion());
                assertEquals(receipt.path("assignmentRevision").asLong(), discussion.assignmentRevision());
                assertNull(relaySource.claimNext(SCOPE, "second-chat-fixture", claimTime));
                var chat = bindQuotedDiscussion(discussion, req,actualAuthority,authority.identities());
                // Preserve the independent negative command check; the full Runtime path below uses the committed Chat event.
                var admission=new QuotedInitialActionAdmission(billing,decisions);
                prepareNativeExecutionSchema();
                new ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("db/economy-v0-skill-application.sql"),new org.springframework.core.io.ClassPathResource("db/agent-personal-workspace-skill-v1.sql")).execute(jdbc.getDataSource());
                var service=NativeCancellationFixture.create(jdbc,executionRows,new SkillExecutionDaoImpl(recommendationSql.getMapper(SkillExecutionMapper.class)),mock(org.springframework.context.ApplicationEventPublisher.class),TENANT,CLIENT,OWNER,id,agent,roots);
                ReflectionTestUtils.setField(service,"conversationExecutionEnabled",true);if(System.getProperty("cyf.ownedPickupCodex")!=null)ReflectionTestUtils.setField(service,"meteredCaptureEnabled",true);service.setAssigneeAuthority(actualAuthority);service.setInitialActions(admission);service.setConversationAdmission(assignment,roots);
                var nativeActions=proxy(service,SkillExecutionService.class);var ownerScope=new PersonalWorkspaceExecutionService.OwnerScope(TENANT,CLIENT,OWNER);
                service.setTaskExecutionDependencies(chat.access(),mock(AgentTaskWorkItemDao.class),mock(AgentWorkItemLeaseService.class));
                var scope=new SkillExecutionService.TaskScope(chat.discussion().conversationId(),chat.discussion().generation(),id,receipt.path("grantId").asText(),Long.parseLong(receipt.path("grantVersion").asText()),Long.parseLong(receipt.path("assignmentRevision").asText()));
                var pending=frozen.intent().output();java.util.function.Supplier<String> consumed=()->independent.queryForObject("SELECT initial_execution_id FROM agent_task_native_preparation WHERE intent_sha=?",String.class,frozen.sha256());assertNull(consumed.get());
                var snapshot=new java.util.LinkedHashMap<String,Object>();snapshot.put("schemaVersion",1);snapshot.put("executionProtocolVersion",6);snapshot.put("toolPolicyRef",SkillExecutionService.NATIVE_LOCAL_FILES_POLICY);snapshot.put("skills",List.of());snapshot.put("taskGrant",Map.of("id",scope.taskGrantId(),"version",scope.taskGrantVersion(),"assignmentRevision",scope.assignmentRevision()));snapshot.put("scope",scope.wire().asMap());snapshot.put("targetAgentId",agent);snapshot.put("actionEventId","history-first");snapshot.put("instruction","fixture answer");snapshot.put("parameters",Map.of());snapshot.put("inputs",List.of());snapshot.put("sourceSnapshotDigest",jdbc.queryForObject("SELECT SHA2('[]',256)",String.class));snapshot.put("providerBinding",null);snapshot.put("costInfo",Map.of("mode","UNKNOWN"));
                int executions=count("agent_personal_workspace_execution"),members=count("agent_task_execution_member");
                snapshot.put("outputs",Map.of("mimeTypes",List.of("text/plain"),"multiple",true,"artifactsRequired",true));
                var wrong=new SkillExecutionService.Create("history-first",scope,agent,snapshot);
                assertEquals(PersonalWorkspaceExecutionService.Reason.TASK_CONFLICT,assertThrows(PersonalWorkspaceExecutionService.Failure.class,()->nativeActions.create(ownerScope,wrong)).getReason());
                assertNull(consumed.get());assertEquals(executions,count("agent_personal_workspace_execution"));assertEquals(members,count("agent_task_execution_member"));
                final SkillExecutionService.Creation created;
                if(chat.action()!=null)created=chat.action().consume(nativeActions,admission);
                else { // Component-only run without the fixed Runtime source cannot produce a Chat final.
                    assertNull(System.getProperty("ownedMeteredRuntime"));snapshot.put("outputs",pending.commandOutputs());
                    var right=new SkillExecutionService.Create("history-first",scope,agent,snapshot);created=nativeActions.create(ownerScope,right);assertEquals(created,nativeActions.create(ownerScope,right));
                }
                assertEquals("QUEUED",created.state());
                assertEquals(executions+1,count("agent_personal_workspace_execution"));assertEquals(members+1,count("agent_task_execution_member"));assertEquals(created.executionId(),consumed.get());
                assertEquals(created.executionId(),jdbc.queryForObject("SELECT initial_execution_id FROM agent_task_native_preparation WHERE intent_sha=?",String.class,frozen.sha256()));assertEquals(created.runId(),jdbc.queryForObject("SELECT run_id FROM agent_task_execution_member WHERE execution_id=? AND task_id=?",String.class,created.executionId(),id));
                verifyNativePickup(authority,service,created,pending);
                assertEquals(balance-expectedWen,wallet(independent));assertEquals(posts+1,count("economy_transaction"));assertEquals(grants+1,count("agent_task_execution_grant"));
                if (cancelQuotedNativeResult) {
                    for (String ddl : List.of("agent-task-funding-v0.sql", "agent-selected-output-finalization-v1.sql",
                            "agent-task-provider-cost-consent-v1.sql"))
                        new ResourceDatabasePopulator(new FileSystemResource(DDL.resolve(ddl))).execute(jdbc.getDataSource());
                    for (String table : List.of("agent_command_delivery", "agent_outbox_event"))
                        createTable(DDL.resolve("schema.sql"), table, true);
                    ReflectionTestUtils.setField(cancellation, "executions", proxy(
                            new TaskExecutionCancellationServiceImpl(executionRows, nativeActions), TaskExecutionCancellationService.class));
                    long version = independent.queryForObject("SELECT task_version FROM agent_task_meta WHERE task_id=?", Long.class, id);
                    String requestId = "cancel-quoted-native";
                    var cancelRequest = HttpRequest.newBuilder(URI.create(origin+"/agent/tasks/"+id+"/cancel"))
                            .header("Authorization", "Bearer "+token).header("X-Request-Id", requestId)
                            .header("Idempotency-Key", requestId).header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of(
                                    "protocolVersion", 1, "requestId", requestId,
                                    "data", Map.of("expectedTaskVersion", Long.toString(version)))))).build();
                    for (int cancelAttempt=0; cancelAttempt<2; cancelAttempt++) {
                        var cancelled=client.send(cancelRequest,HttpResponse.BodyHandlers.ofString());
                        assertEquals(200,cancelled.statusCode(),cancelled.body());
                        assertEquals(balance,wallet(independent));
                        assertEquals(posts+2,count("economy_transaction"));
                        assertEquals("cancelled",independent.queryForObject("SELECT reward_status FROM agent_task_meta WHERE task_id=?",String.class,id));
                        assertEquals("RELEASED",independent.queryForObject("SELECT state FROM economy_task_charge WHERE task_id=?",String.class,id));
                        assertEquals(0,independent.queryForObject("SELECT COUNT(*) FROM agent_task_execution_grant WHERE task_id=? AND state='ACTIVE'",Integer.class,id));
                    }
                    System.out.println("QUOTED_NATIVE_CANCEL sameTask=true resultCommitted=true refundOnce=true replay=true");
                }
            }return null;
        };
    }

    /** Same committed execution: authenticated MVC filter, controller, service, MySQL lease. Socket liveness is a fixture. */
    private interface QuotedAction {SkillExecutionService.Creation consume(SkillExecutionService executions,TaskInitialActionOutput outputs);}
    private record BoundBountyChat(ChatBountyConversationService.Discussion discussion,
                                  WorkspaceConversationAccessService access,QuotedAction action) {}

    /** Real Chat binding and ACL against the same committed assignment. No synthetic conversation ID. */
    private BoundBountyChat bindQuotedDiscussion(AgentTaskBountyBootstrapClaimDTO intent, AgentTaskRequirementSnapshotService requirements,TaskAssigneeAuthorityService actualAuthority,AgentIdentityService identities) throws Exception {
        Path chat = Path.of("../../chat/jia-chat-mapper/src/test/resources/db/schema.sql");
        createTable(chat, "chat_conversation", false);
        createTable(chat, "agent_task_thread", false);
        new ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("db/chat-deliberation-schema.sql")).execute(jdbc.getDataSource());
        var conversations = new cn.jia.chat.dao.impl.ChatConversationDaoImpl();
        ReflectionTestUtils.setField(conversations, "baseMapper", recommendationSql.getMapper(cn.jia.chat.mapper.ChatConversationMapper.class));
        var threads = new cn.jia.chat.dao.impl.AgentTaskThreadDaoImpl(recommendationSql.getMapper(cn.jia.chat.mapper.AgentTaskThreadMapper.class));
        var access = new cn.jia.chat.service.impl.WorkspaceConversationAccessServiceImpl(conversations, threads);
        var bindings = new ChatBountyConversationService(new cn.jia.chat.deliberation.ChatBountyBindingStore(jdbc), conversations, assignment);
        var requirement = requirements.read(SCOPE, intent.taskId(), intent.requirementRevision());
        assertNotNull(requirement);
        var tx = new TransactionTemplate(manager);
        java.util.function.Supplier<ChatBountyConversationService.Discussion> bind = () -> tx.execute(status ->
                bindings.ensure(SCOPE, intent.taskId(), intent.grantId(), intent.grantVersion(), intent.assignmentRevision(),
                        intent.targetAgentId(), intent.permittedOperation(), requirement.title()));
        var discussion = bind.get(); assertTrue(discussion.newlyCreated());
        var replay = bind.get(); assertFalse(replay.newlyCreated());
        assertEquals(discussion.conversationId(), replay.conversationId()); assertEquals(discussion.generation(), replay.generation());
        assertEquals(1, count("chat_bounty_binding")); assertEquals(1, count("chat_conversation"));
        var view = access.requireAccessible(new WorkspaceConversationAccessService.Scope(TENANT,CLIENT,OWNER), discussion.conversationId());
        assertEquals(intent.taskId(), view.taskId()); assertEquals(List.of(intent.targetAgentId()), view.targetAgentIds());
        assertThrows(IllegalStateException.class, () -> access.requireAccessible(
                new WorkspaceConversationAccessService.Scope(TENANT,CLIENT,"other-owner"), discussion.conversationId()));
        var action=verifyBootstrapRequest(intent, requirements, bindings, conversations, discussion, tx,actualAuthority,identities);
        return new BoundBountyChat(discussion, access,action);
    }

    /** Chat owns all request/turn/message/outbox writes. Device capabilities and the task display DTO
     * are fixtures here, not a claim of executing the discussion or accepting its eventual answer. */
    private QuotedAction verifyBootstrapRequest(AgentTaskBountyBootstrapClaimDTO intent, AgentTaskRequirementSnapshotService requirements,
            ChatBountyConversationService discussions, cn.jia.chat.dao.ChatConversationDao conversations,
            ChatBountyConversationService.Discussion bound, TransactionTemplate tx,TaskAssigneeAuthorityService actualAuthority,AgentIdentityService identities) throws Exception {
        createTable(Path.of("../../chat/jia-chat-mapper/src/test/resources/db/schema.sql"), "chat_message", false);
        new ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("db/chat-native-execution-v6.sql"),new org.springframework.core.io.ClassPathResource("db/chat-typed-deliberation-schema-v1.sql")).execute(jdbc.getDataSource());
        var dao = new cn.jia.chat.deliberation.ChatDeliberationDaoImpl(recommendationSql.getMapper(cn.jia.chat.deliberation.ChatDeliberationMapper.class));
        var messages = new cn.jia.chat.dao.impl.ChatMessageDaoImpl();
        ReflectionTestUtils.setField(messages, "baseMapper", recommendationSql.getMapper(cn.jia.chat.mapper.ChatMessageMapper.class));
        var requirement = requirements.read(SCOPE, intent.taskId(), intent.requirementRevision());
        var agents = mock(AgentService.class); var dto = new AgentTaskDTO();
        dto.setId(intent.taskId()); dto.setClientId(CLIENT); dto.setTenantId(TENANT); dto.setTitle(requirement.title());
        dto.setAssignedAgentId(intent.targetAgentId()); dto.setTaskVersion(Long.toString(intent.assignmentRevision()));
        when(agents.getTask(intent.taskId())).thenReturn(dto);
        var deliberation = new ChatDeliberationService(dao, conversations, messages, agents);
        var dispatch = new ChatDispatchAuthorityService(recommendationSql.getMapper(cn.jia.chat.deliberation.ChatDispatchAuthorityMapper.class), dao,
                actualAuthority, assignment, roots);
        deliberation.setDispatchAuthority(dispatch);
        deliberation.setContextHistory(recommendationSql.getMapper(cn.jia.chat.deliberation.ChatContextHistoryMapper.class));
        quoteWorkspace(); // Real empty task-linked input catalog for this new task.
        var fixture=quotedActionContext(intent,requirements,actualAuthority,identities,dispatch);
        var typed = new ChatTypedDiscussionAdmissionService(roots,new cn.jia.chat.deliberation.ChatBountyBindingStore(jdbc),
                conversations,new JuyitingConversationScopeService(new BuiltinHallAgentSupport(agents),agents),deliberation,dao,fixture.context(),
                new ChatTypedDeliberationService(new cn.jia.chat.deliberation.JdbcChatTypedDeliberationStore(jdbc)), mock(ChatActionFinalService.class));
        typed.setSkillGrants(assignment);
        var entry = new ChatBountyBootstrapAdmissionService(requirements,discussions,conversations,messages,dao,
                new cn.jia.chat.deliberation.ChatInteractionStepStore(jdbc),mock(ChatConversationEventBroker.class),new ObjectMapper(),typed);
        var membership = new cn.jia.agent.service.usage.TaskDiscussionMembershipService(executionRows);
        deliberation.setTaskMembers((owner,task,turn,attempt,hash,capture) -> {membership.admit(owner,task,turn,attempt,hash,capture); throw new InjectedFailure();});
        assertThrows(InjectedFailure.class,() -> tx.execute(status -> entry.admit(intent)));
        for(String table:List.of("chat_request","chat_message","chat_turn","chat_dispatch_outbox","chat_context_snapshot","chat_typed_admission")) assertEquals(0,count(table),table);
        assertEquals(0,independent.queryForObject("SELECT COUNT(*) FROM agent_task_execution_member WHERE task_id=?",Integer.class,intent.taskId()));
        deliberation.setTaskMembers(membership);
        if (cancelQuotedNativeResult) ReflectionTestUtils.setField(cancellation, "discussions",
                proxy(deliberation, cn.jia.chat.service.TaskDiscussionCancellation.class));
        // Exercise explicit enrollment in a rollback-only copy of this new admission. Baseline flow stays unmetered.
        new ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("db/chat-task-dispatch-origin-v1.sql")).execute(jdbc.getDataSource());
        var enrolledWire = new java.util.concurrent.atomic.AtomicReference<byte[]>();
        ReflectionTestUtils.setField(typed,"textBootstrapCaptureEnabled",true);
        tx.executeWithoutResult(status -> {
            var admitted=entry.admit(intent);assertFalse(admitted.replay());
            var data=new ObjectMapper().readTree(jdbc.queryForObject("SELECT payload_json FROM chat_dispatch_outbox WHERE event_type='DISPATCH'",String.class));
            var capture=data.at("/factsManifest/taskUsageCapture");
            if(conflictOnDiscussionRetry) assertTrue(capture.isMissingNode()); // registration has no matching route
            else {
                String expected=cn.jia.agent.service.usage.NativeCaptureEvidence.captureIdentity(data.path("turnId").asText(),data.path("dispatchId").asText());
                assertEquals(expected,capture.path("captureId").asText());assertEquals("METERED_CAPTURE_V2",capture.path("protocol").asText());
                assertEquals(expected,jdbc.queryForObject("SELECT capture_id FROM agent_task_execution_member WHERE task_id=?",String.class,intent.taskId()));
                var codec=new ObjectMapper();var relay=new ChatDeliberationOutboxService(dao,new cn.jia.core.security.FixedTenantAdmissionPolicy(TENANT,CLIENT));
                relay.setDeliberation(deliberation);long now=System.currentTimeMillis();var rows=relay.discover(now,2);assertEquals(1,rows.size());
                var claimed=relay.claim(rows.getFirst(),"enrolled-chat-probe",now,60000);assertNotNull(claimed);
                var frozen=claimed.row();var bytes=codec.readTree(frozen.getPayloadJson());assertEquals("BOOTSTRAP",bytes.at("/factsManifest/contextDelivery/mode").asText());
                assertEquals(expected,bytes.at("/factsManifest/taskUsageCapture/captureId").asText());
                var builder=mock(ChatDeliberationOutboxRelay.class,org.mockito.Mockito.CALLS_REAL_METHODS);
                Map<String,Object> wire=ReflectionTestUtils.invokeMethod(builder,"hostedWire",frozen,codec.readValue(frozen.getPayloadJson(),Map.class));
                enrolledWire.set(codec.writeValueAsBytes(cn.jia.agent.common.AgentMessageWire.event("chat.message",wire,null)));
                var request=dao.findRequest(TENANT,OWNER,CLIENT,admitted.requestId());var history=recommendationSql.getMapper(cn.jia.chat.deliberation.ChatContextHistoryMapper.class);
                String turn=frozen.getTurnId(),run=frozen.getDispatchId(),digest=request.getRequestDigest().substring("sha256:".length());
                String hash=bytes.path("contextHash").asText(),target=intent.targetAgentId();
                var host=new AgentManagedSessionLookup.SessionFence("installation","host","boot",1);
                var subject=new AuthenticatedRuntimeOperation.Scope(TENANT,CLIENT,OWNER,target);
                var presenting=new java.util.concurrent.atomic.AtomicReference<>(new AuthenticatedRuntimeOperation.Device(subject,host));
                // Only authentication is a fixture: the scope/lease, original grant, member and snapshot are actual DB rows.
                var auth=new AuthenticatedRuntimeOperation(){
                    public <T>T withAuthenticatedDevice(java.security.Principal p,java.util.function.Function<Device,T> op){return op.apply(presenting.get());}
                    public <T>T withAuthenticatedRuntime(java.security.Principal p,java.util.function.Function<Scope,T> op){throw new AssertionError();}
                };
                var store=new cn.jia.chat.deliberation.ChatTaskDispatchOriginStore(jdbc);
                var access=new cn.jia.chat.service.usage.ChatDiscussionCaptureAccess(auth,new cn.jia.chat.service.usage.ChatUsageDispatchAuthority(
                    recommendationSql.getMapper(cn.jia.chat.deliberation.ChatDispatchAuthorityMapper.class),dao,store),membership);
                assertEquals(1,jdbc.update("""
                    INSERT INTO agent_runtime(agent_id,name,tenant_id,client_id,owner_jiacn,binding_id,status)
                    SELECT agent_id,'capture-test',tenant_id,client_id,jiacn,id,'offline' FROM agent_persona_binding
                    WHERE tenant_id=? AND client_id=? AND jiacn=? AND agent_id=? AND status=1
                    """,TENANT,CLIENT,OWNER,target));
                java.util.function.Supplier<cn.jia.economy.usage.UsageCaptureJournal.Binding> resolve=()->access.withCapture(null,turn,run,hash,expected,b->b);
                var origins=new ChatTaskDispatchOriginService(relay,store);origins.beforeSend(claimed,hash,host);
                assertThrows(ChatDeliberationException.class,resolve::get); // beforeSend is not a delivered dispatch
                relay.setDispatchAuthority(dispatch);assertTrue(relay.acknowledgeHostedDispatch(TENANT,OWNER,CLIENT,target,run,frozen.getEventId(),now));
                verifyChatCaptureHttp(access,turn,run,hash,expected);
                var binding=resolve.get();assertEquals(expected,binding.captureId());assertEquals(intent.taskId(),binding.taskId());
                assertThrows(ChatDeliberationException.class,()->access.withCapture(null,turn,run,hash,"0".repeat(64),b->b));
                assertThrows(ChatDeliberationException.class,()->access.withCapture(null,turn,run,"sha256:"+"0".repeat(64),expected,b->b));
                presenting.set(new AuthenticatedRuntimeOperation.Device(subject,new AgentManagedSessionLookup.SessionFence("installation","host","boot",2)));
                assertThrows(ChatDeliberationException.class,resolve::get);
                assertTrue(membership.enrolled(SCOPE,intent.taskId(),turn,run,digest,expected));
                assertFalse(membership.enrolled(SCOPE,intent.taskId(),turn,run,digest,null));
                assertFalse(membership.enrolled(SCOPE,intent.taskId(),turn,run,digest,expected+" "));
                assertFalse(membership.enrolled(SCOPE,intent.taskId(),turn,run,"0".repeat(64),expected));
                assertFalse(membership.enrolled(SCOPE,intent.taskId(),turn,run+" ",digest,expected));
                var otherOwner=new AgentTaskExecutionGrantService.Scope(TENANT,CLIENT,OWNER+"-other");
                assertFalse(membership.enrolled(otherOwner,intent.taskId(),turn,run,digest,expected));
                assertFalse(history.hasEarlierRequest(request));request.setId(request.getId()+1);assertTrue(history.hasEarlierRequest(request));

            }
            ReflectionTestUtils.setField(typed,"textBootstrapCaptureEnabled",false);
            assertTrue(entry.admit(intent).replay());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM agent_task_execution_member WHERE task_id=?",Integer.class,intent.taskId()));
            status.setRollbackOnly();
        });
        assertEquals(0,count("chat_dispatch_outbox"));assertEquals(0,count("chat_turn"));
        if(enrolledWire.get()!=null && System.getProperty("ownedMeteredRuntime")!=null)verifyEnrolledDiscussionFrame(enrolledWire.get());
        var first = tx.execute(status -> entry.admit(intent));
        assertNotNull(first); assertFalse(first.replay()); assertNull(first.stepId()); assertEquals(bound.conversationId(),first.conversationId());
        var again = tx.execute(status -> entry.admit(intent));
        assertTrue(again.replay()); assertEquals(first.requestId(),again.requestId()); assertEquals(first.userMessageId(),again.userMessageId());
        for (String table : List.of("chat_message","chat_request","chat_turn","chat_context_snapshot","chat_dispatch_outbox","chat_typed_admission"))
            assertEquals(1,count(table),table);
        assertEquals(0,count("chat_interaction_step")); assertEquals(0,count("chat_step_execution_link"));
        String saved = independent.queryForObject("SELECT m.content FROM chat_message m JOIN chat_request r ON m.id=r.user_message_id JOIN chat_turn t ON t.request_id=r.request_id JOIN chat_dispatch_outbox o ON o.turn_id=t.turn_id WHERE r.tenant_id=? AND r.client_id=? AND r.owner_jiacn=? AND r.conversation_id=? AND r.request_id=? AND m.message_type='USER' AND t.route='CHAT' AND t.target_agent_id=? AND o.status='READY' AND o.task_grant_id=? AND o.task_grant_version=? AND o.task_assignment_revision=? AND o.task_requirement_revision=?",String.class,
                TENANT,CLIENT,OWNER,bound.conversationId(),first.requestId(),intent.targetAgentId(),intent.grantId(),intent.grantVersion(),intent.assignmentRevision(),intent.requirementRevision());
        int registered = independent.queryForObject("SELECT COUNT(*) FROM agent_task_execution_member WHERE tenant_id=? AND client_id=? AND owner_jiacn=? AND task_id=?",Integer.class,TENANT,CLIENT,OWNER,intent.taskId());
        assertEquals(1,registered);
        assertEquals(1,independent.queryForObject("SELECT COUNT(*) FROM agent_task_execution_member m JOIN chat_turn t ON BINARY t.turn_id=BINARY m.execution_id AND BINARY t.dispatch_id=BINARY m.run_id JOIN chat_request r ON r.request_id=t.request_id WHERE m.task_id=? AND m.capture_id IS NULL AND BINARY m.admission_sha256=BINARY SUBSTRING(r.request_digest,8) AND r.request_id=?",Integer.class,intent.taskId(),first.requestId()));
        assertEquals(requirement.title()+"\n\n"+requirement.description(),saved);
        // Actual dispatch claim, not a hand-made context hash or an outbox row assumed delivered.
        var relay=new ChatDeliberationOutboxService(dao,new cn.jia.core.security.FixedTenantAdmissionPolicy(TENANT,CLIENT));
        relay.setDeliberation(deliberation);long now=System.currentTimeMillis();var due=relay.discover(now,2);
        assertEquals(1,due.size());var draft=due.getFirst();var codec=new ObjectMapper();
        assertEquals("PENDING",codec.readTree(draft.getPayloadJson()).path("contextPreparation").asText());
        var claim=tx.execute(st->relay.claim(draft,"owned-chat-relay",now,60000));assertNotNull(claim);
        var row=claim.row();assertEquals("CLAIMED",row.getStatus());assertEquals(draft.getDispatchId(),row.getDispatchId());
        assertNull(tx.execute(st->relay.claim(draft,"other-relay",now,60000)));
        var payload=codec.readTree(row.getPayloadJson());assertFalse(payload.has("contextPreparation"));
        assertEquals("BOOTSTRAP",payload.at("/factsManifest/contextDelivery/mode").asText());
        var outgoing=dispatch.outgoing(row);assertEquals(OWNER,outgoing.executorOwner());
        assertEquals(intent.targetAgentId(),outgoing.turn().getTargetAgentId());assertEquals(row.getTurnId(),outgoing.turn().getTurnId());
        assertEquals(outgoing.turn().getContextDigest(),payload.path("contextHash").asText());
        var finalSource = verifyDiscussionOrigin(tx,relay,claim,row,draft,outgoing.turn().getContextDigest(),now);
        QuotedAction selected=null;
        if(System.getProperty("ownedMeteredRuntime")!=null){
            relay.setDispatchAuthority(dispatch);
            var finals=new ChatActionFinalService(new cn.jia.chat.deliberation.JdbcChatTypedDeliberationStore(jdbc),dao,new cn.jia.chat.handler.TypedInspectionSessionRegistry());
            deliberation.setActionFinals(finals);
            verifyDiscussionInbox(tx,relay,row,outgoing,deliberation,finalSource);
            selected=(executions,outputs)->{
                var store=new cn.jia.chat.deliberation.JdbcChatTypedDeliberationStore(jdbc);
                var observed=new java.util.concurrent.atomic.AtomicReference<SkillExecutionService.Creation>();
                var observer=mock(SkillExecutionService.class);
                when(observer.create(any(),any())).thenAnswer(inv->{var created=executions.create(inv.getArgument(0),inv.getArgument(1));assertTrue(observed.compareAndSet(null,created));return created;});
                var exec=new ChatActionExecutionService(deliberation,dao,store,new cn.jia.chat.deliberation.ChatInteractionStepStore(jdbc),assignment,
                        observer,fixture.installed(),fixture.live(),fixture.policy());exec.setNativeCapabilities(fixture.caps(),fixture.sessions());exec.setInitialOutputs(outputs);
                var provider=new org.springframework.beans.factory.support.StaticListableBeanFactory(Map.of("executor",exec)).getBeanProvider(ChatActionDispatchService.Executor.class);
                var consumer=new ChatActionDispatchService(proxy(finals,ChatActionFinalService.class),roots,new cn.jia.chat.deliberation.ChatBountyBindingStore(jdbc),conversations,
                        new JuyitingConversationScopeService(new BuiltinHallAgentSupport(agents),agents),store,null,deliberation,dao,relay,provider);
                long time=System.currentTimeMillis();var events=relay.discover(time,32).stream().filter(e->ChatActionFinalService.ACTION_EVENT.equals(e.getEventType())&&row.getTurnId().equals(e.getTurnId())).toList();
                assertEquals(1,events.size());var actionClaim=tx.execute(st->relay.claim(events.getFirst(),"quote-action",time,60000));assertNotNull(actionClaim);
                var action=tx.execute(st->finals.loadAction(actionClaim.row()));assertNotNull(action);
                // Scoped, rollback-only revocation fixture at admission, not a forged action or terminal execution.
                tx.executeWithoutResult(st->{assertEquals(1,jdbc.update("UPDATE agent_task_execution_grant SET state='REVOKED' WHERE grant_id=? AND state='ACTIVE'",intent.grantId()));
                    assertThrows(ChatActionDispatchService.Rejected.class,()->exec.admit(action));st.setRollbackOnly();});
                assertNull(observed.get());assertEquals(0,count("chat_step_execution_link"));
                consumer.consume(actionClaim);assertNotNull(observed.get());
                assertEquals("SENT",independent.queryForObject("SELECT status FROM chat_dispatch_outbox WHERE event_id=?",String.class,events.getFirst().getEventId()));
                assertThrows(IllegalStateException.class,()->consumer.consume(actionClaim));
                var replay=tx.execute(st->exec.admit(action));assertNotNull(replay);
                assertEquals(1,count("chat_step_execution_link"));assertEquals(observed.get().executionId(),independent.queryForObject("SELECT execution_id FROM chat_step_execution_link",String.class));
                verify(observer,times(1)).create(any(),any());return observed.get();
            };
        }
        assertEquals(1,independent.queryForObject("SELECT COUNT(*) FROM agent_task_execution_member WHERE task_id=? AND execution_id=? AND run_id=? AND capture_id IS NULL",Integer.class,intent.taskId(),row.getTurnId(),row.getDispatchId()));

        return selected;
    }

    /** Real task policy, grant and action compiler. Only current device observations and
     * the enabled native-file capability are controlled inputs, not prebuilt action facts. */
    private record QuotedActionContext(ChatTypedDeliberationContextService context,ChatActionCapabilityService caps,cn.jia.chat.handler.TypedDeliberationSessionRegistry sessions,
            InstalledSkillDescriptorLookup installed,SkillCapabilityLookup live,TaskSkillPolicyService policy) {}
    private QuotedActionContext quotedActionContext(AgentTaskBountyBootstrapClaimDTO intent,
            AgentTaskRequirementSnapshotService requirements,TaskAssigneeAuthorityService authority,
            AgentIdentityService identities,ChatDispatchAuthorityService dispatch){
        String agent=intent.targetAgentId(),session="quote-chat-session";
        var sessions=new cn.jia.chat.handler.TypedDeliberationSessionRegistry();
        sessions.register(session,TENANT,OWNER,CLIENT,agent,Map.of("schemaVersion",5,"state","READY","carrier","CHAT_MESSAGE_FINAL_SIDECAR_V5",
                "referenceModes",List.of("NONE","AVAILABLE"),"outcomeKinds",List.of("ANSWER","CLARIFY","ACTION_REQUEST","REVISION_REQUEST","SUBMISSION_REQUEST"),
                "engine","CODEX_APP_SERVER_NATIVE_OUTPUT_SCHEMA","strictNoToolsVerified",false,"toolPolicy","read-only-constrained",
                "discussionCaptureRoute",conflictOnDiscussionRetry?"UNAVAILABLE":"SINGLE_BOUNTY_CHAT_TEXT_BOOTSTRAP_V1",
                "nativeExecution",Map.of("schemaVersion",1,"executionProtocolVersion",6,"toolPolicyRef","NATIVE_LOCAL_FILES_V1","outputMimeTypes",List.of("text/plain"))));
        var auth=mock(cn.jia.agent.security.AgentRuntimeAuthenticationService.class);
        var proof=new cn.jia.agent.security.AgentRuntimeAuthenticationService.Proof(new cn.jia.agent.security.AgentRuntimeAuthentication.Scope(TENANT,CLIENT,OWNER,agent,"boot"),"installation","host",1,"fixture",1,1);
        when(auth.currentRegisteredProof(TENANT,CLIENT,agent)).thenReturn(proof);
        var policies=new TaskSkillPolicyServiceImpl(withMapper(new AgentTaskMetaDaoImpl(),AgentTaskMetaMapper.class),
                new AgentTaskExecutionGrantDaoImpl(recommendationSql.getMapper(AgentTaskExecutionGrantMapper.class)),identities,requirements,auth);
        policies.setAssigneeAuthority(authority);
        var scope=new SkillCapabilityLookup.Scope(TENANT,CLIENT,OWNER,agent);
        var fence=new AgentManagedSessionLookup.SessionFence("installation","host","boot",1);
        InstalledSkillDescriptorLookup installed=key->{assertEquals(scope,key);return new InstalledSkillDescriptorLookup.Snapshot(List.of());};
        SkillCapabilityLookup live=(key,observed)->{assertEquals(scope,key);assertEquals(fence,observed);return new SkillCapabilityLookup.View(SkillCapabilityLookup.State.AVAILABLE,session,fence,new SkillCapabilityLookup.Catalog("1",List.of()));};
        var caps=new ChatActionCapabilityService(installed,live,policies);
        var nativeFiles=mock(PersonalWorkspaceExecutionService.class);
        when(nativeFiles.nativeLocalFileCapabilities()).thenReturn(new PersonalWorkspaceExecutionService.ExecutionCapabilities(List.of("text/plain"),List.of(),true));
        caps.setNativeExecutions(nativeFiles);caps.setNativeSessions(sessions);
        var context=new ChatTypedDeliberationContextService(jdbc,sessions,()->cn.jia.chat.config.ChatTypedDeliberationReadiness.Readiness.READY,caps,true);
        context.setDispatchAuthority(dispatch);return new QuotedActionContext(context,caps,sessions,installed,live,policies);
    }

    // Original admission and ACK share the test's rollback-only transaction. This is NOT a post-commit network loss test.
    private void verifyChatCaptureHttp(cn.jia.chat.service.usage.ChatDiscussionCaptureAccess access,String turn,String run,String hash,String cap){
        try {
            var mapper=recommendationSql.getMapper(cn.jia.economy.mapper.EconomyUsageCaptureMapper.class);
            var journal=proxy(new cn.jia.economy.service.impl.UsageCaptureJournalImpl(mapper,event->{}),cn.jia.economy.usage.UsageCaptureJournal.class);
            var controller=new cn.jia.chat.api.ChatDiscussionCaptureController(access,new cn.jia.agent.service.usage.RuntimeCaptureIntakeService(journal,null,null));
            var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
                    .setControllerAdvice(new cn.jia.core.config.ApiEnvelopeAdvice(),new cn.jia.core.config.ApiRequestEnvelopeAdvice())
                    .addFilters(new cn.jia.core.filter.RequestIdFilter()).build();
            String ctx=hash.substring(7),path="/internal/agent/chat/dispatches/"+run+"/turns/"+turn+"/contexts/"+ctx+"/captures/"+cap+"/usage";
            var codec=new ObjectMapper();var digest=(java.util.function.Function<Object,String>)cn.jia.agent.service.usage.NativeCaptureEvidence::digest;
            var obs=new java.util.LinkedHashMap<String,Object>(Map.of("captureProtocol","METERED_CAPTURE_V2","snapshotSha256",ctx,"executionConfigSha256","f".repeat(64),"engineVersion","fixture","rootSourceId","discussion"));
            String record=digest.apply(List.of("USAGE_RECORD_V2",cap,"PLAN",List.of()));String ack=null;
            for(String policy:List.of("TEXT_MODEL_ONLY_V1","CHAT_SOURCE_UNVERIFIED_V1","CHAT_SOURCE_UNVERIFIED_V1")){
                obs.put("coveragePolicyVersion",policy);var e=new java.util.LinkedHashMap<String,Object>();
                e.put("schemaVersion",2);e.put("executionId",turn);e.put("runId",run);e.put("captureId",cap);e.put("kind","PLAN");e.put("recordId",record);
                e.put("sourceRevision","1");e.put("previousEvidenceSha256",null);e.put("observedAt",1);e.put("observation",obs);
                String sha=digest.apply(e),id=digest.apply(java.util.Arrays.asList("USAGE_RECEIPT_V2",sha,null));e.put("evidenceSha256",sha);e.put("receiptId",id);e.put("expectedRevision",null);
                var post=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).header("Idempotency-Key",id).header("X-Request-Id",id)
                        .contentType("application/json").content(codec.writeValueAsBytes(Map.of("protocolVersion",1,"requestId",id,"data",e)));
                var reply=mvc.perform(post).andReturn().getResponse();
                if(policy.equals("TEXT_MODEL_ONLY_V1")){assertEquals(409,reply.getStatus());assertEquals(0,count("economy_usage_capture_receipt"));continue;}
                assertEquals(200,reply.getStatus(),reply.getContentAsString());String saved=codec.readTree(reply.getContentAsByteArray()).path("data").toString();
                if(ack!=null)assertEquals(ack,saved);ack=saved;assertEquals(1,count("economy_usage_capture_receipt"));
                for(String item:List.of("receipts/"+id,"records/"+record)){
                    var read=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path+"/"+item).header("X-Request-Id",id)).andReturn().getResponse();
                    assertEquals(200,read.getStatus());var data=codec.readTree(read.getContentAsByteArray()).path("data");assertEquals(sha,data.path("evidenceSha256").asText());
                    if(item.startsWith("receipts"))assertEquals(ack,data.toString());
                }
            }
        } catch(Exception ex){throw new AssertionError(ex);}
    }
    // Runs outside the database transaction. This probe has no provider, API receiver, or wallet writer.
    private void verifyEnrolledDiscussionFrame(byte[] frame) throws Exception {
        var temp=Files.createTempDirectory("quoted-enrolled-chat-");
        try {
            var pb=new ProcessBuilder("node","--input-type=module","-e","""
                import fs from 'node:fs';import assert from 'node:assert/strict';
                import {fromAgentWire} from './agent-message-wire.mjs';import {normalizeInboundMessage} from './agent-client.mjs';
                import {PersistentChatInbox} from './chat-runtime.mjs';import {startDiscussionCapture,recoverStoppedDiscussionCapture} from './metered-request-capture.mjs';
                const m=normalizeInboundMessage(fromAgentWire(fs.readFileSync(0,'utf8')));
                const opts={rootDir:process.env.TMPDIR,profile:{agentId:m.targetAgentId}},inbox=new PersistentChatInbox(opts);inbox.initialize();
                const {key}=await inbox.accept(m);assert.ok(inbox.claim(key));const cap=await startDiscussionCapture(inbox,key,{
                  executionConfigSha256:'a'.repeat(64),engineVersion:'fixture',adapterVersion:'FIXTURE',rootSourceId:'discussion',channelNamespace:'fixture',checkActive(){}});
                assert.equal(cap.captureId,m.contextSnapshot.facts.taskUsageCapture.captureId);assert.equal(cap.executionId,m.turnId);assert.equal(cap.runId,m.dispatchId);
                await cap.markStopped(()=>{});const restored=new PersistentChatInbox(opts);restored.initialize();
                assert.equal((await recoverStoppedDiscussionCapture(restored,key,()=>{})).closeState,'SEALED_INCOMPLETE');
                """);
            pb.directory(Path.of(System.getProperty("ownedMeteredRuntime"),"conf/codex-bridge").toFile());String path=System.getenv("PATH");
            pb.environment().clear();pb.environment().putAll(Map.of("PATH",path,"HOME",temp.toString(),"TMPDIR",temp.toString()));pb.redirectError(ProcessBuilder.Redirect.INHERIT);
            var proc=pb.start();try{try(var stdin=proc.getOutputStream()){stdin.write(frame);}assertTrue(proc.waitFor(20,TimeUnit.SECONDS));assertEquals(0,proc.exitValue());}
            finally{if(proc.isAlive()){proc.destroyForcibly();proc.waitFor();}}
        } finally {org.springframework.util.FileSystemUtils.deleteRecursively(temp);}
    }

    /** Actual frozen Chat payload, public API/Runtime codecs and disk inbox. Stdin stands in for
     * network transport here; device selection and authentication are separate tests. No model call. */
    private void verifyDiscussionInbox(TransactionTemplate tx,ChatDeliberationOutboxService outbox,
            cn.jia.chat.deliberation.ChatDispatchOutboxEntity row,ChatDispatchAuthorityService.Binding outgoing,ChatDeliberationService deliberation,java.util.function.Supplier<cn.jia.chat.service.usage.ChatUsageDispatchAuthority.TaskSource> source) throws Exception {
        var codec=new ObjectMapper();var builder=mock(ChatDeliberationOutboxRelay.class,org.mockito.Mockito.CALLS_REAL_METHODS);
        Map<String,Object> wire=ReflectionTestUtils.invokeMethod(builder,"hostedWire",row,codec.readValue(row.getPayloadJson(),Map.class));
        var frame=cn.jia.agent.common.AgentMessageWire.event("chat.message",wire,null);
        var temp=Files.createTempDirectory("quoted-discussion-inbox-");
        try{var pb=new ProcessBuilder("node","--input-type=module","-e","""
            import fs from 'node:fs';import assert from 'node:assert/strict';
            import {fromAgentWire} from './agent-message-wire.mjs';import {normalizeInboundMessage} from './agent-client.mjs';
            import {PersistentChatInbox,buildChatDispatchAck} from './chat-runtime.mjs';
            // Fixed labels/monotonic durations only; never record the wire frame or credentials.
            const mark=name=>fs.appendFileSync(process.env.TMPDIR+'/phase.log',name+':'+Math.round(performance.now())+' ');
            mark('modules');
            const m=normalizeInboundMessage(fromAgentWire(fs.readFileSync(0,'utf8')));
            assert.equal(m.durable,true);assert.equal(m.route,'CHAT');
            const profile={profileId:'owned',agentId:m.targetAgentId};const opts={rootDir:process.env.TMPDIR,profile};
            const {resolveActionChatRequest,validateActionOutcome}=await import('./juyiting-action-outcome.mjs');
            const facts=resolveActionChatRequest(profile,m);assert.equal(facts?.schemaVersion,6);
            assert.equal(facts.availableActions.length,1);const descriptor=facts.availableActions[0];assert.equal(descriptor.operation,'EXECUTE_NATIVE');assert.equal(descriptor.skill,null);
            const action={actionId:descriptor.actionId,instruction:'Answer the task',sourceRefIds:[],parameters:{outputMode:'ANSWER'}};
            const outcome={schemaVersion:5,kind:'ACTION_REQUEST',text:'Proceed',clarification:null,action,revision:null,deliverable:false,deliveryRelation:null};
            assert.deepEqual(validateActionOutcome(outcome,facts),outcome);
            assert.throws(()=>validateActionOutcome({...outcome,action:{...action,actionId:'not-advertised'}},facts),/ACTION_NOT_ADVERTISED/);
            const inbox=new PersistentChatInbox(opts);inbox.initialize();const first=await inbox.accept(m);assert.equal(first.accepted,true);
            const restored=new PersistentChatInbox(opts);assert.equal(restored.initialize().pending,1);assert.equal((await restored.accept(m)).duplicate,true);
            // Fixture at the model boundary, not at the action-final or dispatch boundary.
            const {runFastChat}=await import('./agent-client.mjs');const item=restored.claim(first.key);assert.ok(item);
            const {measureCodexAppServerBinary}=await import('./app-server-adapter.mjs');const {skillDigest}=await import('./skill-executor.mjs');
            mark('inbox');const schema=measureCodexAppServerBinary({codexBin:process.env.CYF_CODEX_BIN,codexHome:process.env.TMPDIR});
            mark('measured');let chatPhase;
            const frames=[];let turns=0;const result=await runFastChat({...profile,chatEngine:'app-server',chatSandbox:'read-only',chatToolPolicy:'read-only-constrained'},m,{
              chatWorkdir:process.env.TMPDIR,adapter:{readback:{initialize:{},schema},
                startOrResumeThread:async(prior,settings)=>{assert.equal(turns,0);assert.ok(settings.developerInstructions.length>0);assert.equal(settings.config.network,false);
                  chatPhase={taskId:m.taskId,conversationId:m.conversationId,binarySha256:schema.binarySha256,instructionsSha256:skillDigest(settings.developerInstructions)};return {threadId:'quote-thread'};},runTurn:async({threadId,policy,onAccepted})=>{
                  assert.equal(threadId,'quote-thread');const actionSchema=policy.outputSchema.properties.action.anyOf;
                  assert.ok(actionSchema.some(v=>v.properties?.actionId?.enum?.includes(descriptor.actionId)));
                  turns++;onAccepted({threadId,turnId:'quote-turn'});return {threadId,turnId:'quote-turn',content:JSON.stringify(outcome),finishReason:'completed'};}},
              controls:{markRunning:()=>{},isCancelled:()=>false,markFinalPrepared:v=>restored.markFinalPrepared(item,v),markFinalPublication:v=>restored.markFinalPublication(item,v)},
              sendProtocolFn:(type,payload)=>{frames.push(payload);return true;}});
            mark('chat');assert.equal(turns,1);assert.equal(result.serverPersistence,'unconfirmed');assert.equal(frames.length,1);
            fs.writeFileSync(process.env.TMPDIR+'/final.json',JSON.stringify(frames[0]));
            const ack=buildChatDispatchAck(profile,m);fs.writeFileSync(process.env.TMPDIR+'/receipt.json',JSON.stringify({ack,context:m.contextHash,turn:m.turnId,chatPhase}));mark('saved');
            """);pb.directory(Path.of(System.getProperty("ownedMeteredRuntime"),"conf/codex-bridge").toFile());String path=System.getenv("PATH");pb.environment().clear();pb.environment().putAll(Map.of("PATH",path,"HOME",temp.toString(),"TMPDIR",temp.toString(),"CYF_CODEX_BIN",System.getProperty("cyf.ownedPickupCodex")));pb.redirectError(ProcessBuilder.Redirect.INHERIT);pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            var proc=pb.start();try{try(var stdin=proc.getOutputStream()){stdin.write(codec.writeValueAsBytes(frame));}boolean ended=proc.waitFor(20,TimeUnit.SECONDS);
                Path phases=temp.resolve("phase.log");String timings=Files.exists(phases)?Files.readString(phases):"no module checkpoint";
                System.out.println("QUOTED_DISCUSSION_PHASE_MS "+timings);assertTrue(ended,"discussion child deadline; "+timings);assertEquals(0,proc.exitValue());}finally{if(proc.isAlive()){proc.destroyForcibly();proc.waitFor();}}
            var file=temp.resolve("receipt.json");assertTrue(Files.size(file)<2048);var receipt=codec.readTree(Files.readAllBytes(file));var ack=receipt.path("ack");
            quotedChatPhase=codec.convertValue(receipt.path("chatPhase"),Map.class);
            assertEquals(outgoing.turn().getContextDigest(),receipt.path("context").asText());assertEquals(row.getTurnId(),receipt.path("turn").asText());assertEquals(row.getDispatchId(),ack.path("dispatchId").asText());assertEquals(row.getEventId(),ack.path("messageId").asText());
            for(int i=0;i<2;i++)assertTrue(tx.execute(st->outbox.acknowledgeHostedDispatch(TENANT,outgoing.executorOwner(),CLIENT,ack.path("agentId").asText(),ack.path("dispatchId").asText(),ack.path("messageId").asText(),System.currentTimeMillis())).booleanValue());
            assertEquals("SENT",independent.queryForObject("SELECT status FROM chat_dispatch_outbox WHERE event_id=?",String.class,row.getEventId()));
            assertEquals(conflictOnDiscussionRetry ? TaskSource.UNMATCHED : TaskSource.OBSERVED, source.get());
            var output=temp.resolve("final.json");assertTrue(Files.size(output)<16384);var reply=codec.readTree(Files.readAllBytes(output));
            var sender=new ServerResolvedAgentSender("agent","Quoted agent",outgoing.executorOwner(),CLIENT,outgoing.turn().getTargetAgentId());
            java.util.function.Supplier<ChatDeliberationService.FinalResult> save=()->tx.execute(st->deliberation.persistFinal(TENANT,OWNER,CLIENT,reply.path("conversationId").asText(),reply.path("conversationGeneration").asLong(),reply.path("targetAgentId").asText(),reply.path("requestId").asText(),reply.path("turnId").asText(),reply.path("dispatchId").asText(),reply.path("contextSnapshotId").asText(),reply.path("contextHash").asText(),reply.path("content").asText(),reply.path("outcomeContractVersion").asInt(),reply.path("interactionOutcome").toString(),null,codec.convertValue(reply.path("contextReceipt"),Map.class),sender));
            var saved=save.get();assertEquals(ChatDeliberationService.FinalStatus.PERSISTED,saved.status());
            assertEquals(conflictOnDiscussionRetry ? TaskSource.UNMATCHED : TaskSource.FINAL_BOUND, source.get());
            var repeated=save.get();assertEquals(ChatDeliberationService.FinalStatus.DUPLICATE,repeated.status());assertEquals(saved.messageId(),repeated.messageId());
            assertEquals(1,independent.queryForObject("SELECT COUNT(*) FROM chat_dispatch_outbox WHERE turn_id=? AND event_type='ACTION_REQUESTED' AND status='READY'",Integer.class,row.getTurnId()));
            assertEquals(0,count("chat_step_execution_link")); // Event committed, consumer not yet run.
        }finally{org.springframework.util.FileSystemUtils.deleteRecursively(temp);}
    }

    private java.util.function.Supplier<cn.jia.chat.service.usage.ChatUsageDispatchAuthority.TaskSource> verifyDiscussionOrigin(TransactionTemplate tx,ChatDeliberationOutboxService relay,ChatDeliberationOutboxService.Claim claim,
            cn.jia.chat.deliberation.ChatDispatchOutboxEntity row,cn.jia.chat.deliberation.ChatDispatchOutboxEntity draft,String context,long now){
        new ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("db/chat-task-dispatch-origin-v1.sql")).execute(jdbc.getDataSource());
        var origins=new ChatTaskDispatchOriginService(relay,new cn.jia.chat.deliberation.ChatTaskDispatchOriginStore(jdbc));
        var host=new cn.jia.agent.service.AgentManagedSessionLookup.SessionFence("installation","host","boot",1);
        assertThrows(InjectedFailure.class,()->tx.executeWithoutResult(st->{origins.beforeSend(claim,context,host);throw new InjectedFailure();}));
        assertEquals(0,count("chat_task_dispatch_origin"));
        var authority=new cn.jia.chat.service.usage.ChatUsageDispatchAuthority(recommendationSql.getMapper(cn.jia.chat.deliberation.ChatDispatchAuthorityMapper.class),
                new cn.jia.chat.deliberation.ChatDeliberationDaoImpl(recommendationSql.getMapper(cn.jia.chat.deliberation.ChatDeliberationMapper.class)),new cn.jia.chat.deliberation.ChatTaskDispatchOriginStore(jdbc));
        var target=new ObjectMapper().readTree(row.getPayloadJson()).path("targetAgentId").asText();
        var subject=new AuthenticatedRuntimeOperation.Scope(TENANT,CLIENT,OWNER,target);var device=new AuthenticatedRuntimeOperation.Device(subject,host);
        java.util.function.Supplier<cn.jia.chat.service.usage.ChatUsageDispatchAuthority.TaskSource> source = () -> authority.taskSource(device,row.getTurnId(),row.getDispatchId(),context);
        java.util.function.BooleanSupplier matches = () -> source.get() != TaskSource.UNMATCHED;
        assertThrows(ChatDeliberationException.class, matches::getAsBoolean);
        // Supply only the original executor binding. No live session or device proof is fabricated here.
        assertEquals(1,jdbc.update("""
                INSERT INTO agent_runtime(agent_id,name,tenant_id,client_id,owner_jiacn,binding_id,status)
                SELECT agent_id,'quoted-chat-source',tenant_id,client_id,jiacn,id,'offline'
                FROM agent_persona_binding WHERE tenant_id=? AND client_id=? AND jiacn=? AND agent_id=? AND status=1
                """,TENANT,CLIENT,OWNER,target));
        assertFalse(matches.getAsBoolean());
        tx.executeWithoutResult(st->origins.beforeSend(claim,context,host));tx.executeWithoutResult(st->origins.beforeSend(claim,context,host));
        assertTrue(matches.getAsBoolean());assertEquals(TaskSource.UNMATCHED,authority.taskSource(device,"other-turn",row.getDispatchId(),context));
        assertEquals(TaskSource.UNMATCHED,authority.taskSource(device,row.getTurnId(),row.getDispatchId(),"sha256:"+"0".repeat(64)));
        var changed=new AuthenticatedRuntimeOperation.Device(subject,new AgentManagedSessionLookup.SessionFence("installation","host","boot",2));
        assertEquals(TaskSource.UNMATCHED,authority.taskSource(changed,row.getTurnId(),row.getDispatchId(),context));
        assertThrows(ChatDeliberationException.class,()->authority.taskSource(new AuthenticatedRuntimeOperation.Device(new AuthenticatedRuntimeOperation.Scope(TENANT,CLIENT,"different-owner",target),host),row.getTurnId(),row.getDispatchId(),context));
        assertEquals(1,count("chat_task_dispatch_origin"));assertEquals(0,independent.queryForObject("SELECT mixed_origin FROM chat_task_dispatch_origin",Integer.class));
        assertTrue(tx.execute(st->relay.retry(claim,"TEST_LOST_SEND",now)).booleanValue());
        assertThrows(IllegalStateException.class,()->tx.executeWithoutResult(st->origins.beforeSend(claim,context,host)));
        var later=tx.execute(st->relay.claim(draft,"next-relay",now+2000,60000));assertNotNull(later);
        if (conflictOnDiscussionRetry) tx.executeWithoutResult(st->origins.beforeSend(later,context,new cn.jia.agent.service.AgentManagedSessionLookup.SessionFence("installation-2","host-2","boot-2",2)));
        tx.executeWithoutResult(st->origins.beforeSend(later,context,host));
        assertEquals(conflictOnDiscussionRetry ? 1 : 0,independent.queryForObject("SELECT COUNT(*) FROM chat_task_dispatch_origin WHERE event_id=? AND context_hash=? AND installation_id='installation' AND host_id='host' AND runtime_instance_id='boot' AND session_generation=1 AND mixed_origin=1",Integer.class,row.getEventId(),context));
        assertEquals(conflictOnDiscussionRetry ? TaskSource.UNMATCHED : TaskSource.OBSERVED, source.get());
        return source;
    }

    private Map<String,Object> quotedChatPhase; // This test instance owns one quoted discussion. No production log or store.
    private void verifyNativePickup(Authority authority,PersonalWorkspaceExecutionServiceImpl service,SkillExecutionService.Creation created,NativeTaskPreparation.Output expected)throws Exception{
        for(String f:List.of("agent-runtime-v1-schema.sql","agent-runtime-session-fence-v1.sql"))new ResourceDatabasePopulator(new FileSystemResource(DDL.resolve(f))).execute(jdbc.getDataSource());
        String agent=created.targetAgentId(),install="rti_"+"2".repeat(32),host="pickup-host",boot="pickup-boot";
        jdbc.update("INSERT INTO agent_runtime_v1_installation(installation_id,canonical_agent_id,manifest_version,manifest_sha256,enrollment_secret_hash,enrollment_expires_at,status,tenant_id,client_id) VALUES(?,?,'1',REPEAT('a',64),UNHEX(REPEAT('01',32)),1,'ACTIVE',?,?)",install,agent,TENANT,CLIENT);
        var installations=withMapper(new AgentRuntimeV1InstallationDaoImpl(),AgentRuntimeV1InstallationMapper.class);
        var accounts=mock(cn.jia.user.security.AccountSecurityService.class);when(accounts.findUniqueByExactJiacn(OWNER)).thenReturn(java.util.Optional.of(new cn.jia.user.security.AccountSecuritySnapshot(1,OWNER,cn.jia.user.security.AccountState.ACTIVE,1)));
        var gate=new cn.jia.agent.config.AgentTaskEventsGate(new cn.jia.agent.config.AgentTaskEventsProperties(true,List.of(new cn.jia.agent.config.AgentTaskEventsProperties.AllowedScope(TENANT,CLIENT))));
        var target=new cn.jia.agent.security.AgentRuntimeAuthenticationService(authority.runtimes(),installations,withMapper(new AgentIdentityRegistryDaoImpl(),AgentIdentityRegistryMapper.class),authority.identities(),accounts,gate,new cn.jia.core.security.FixedTenantAdmissionPolicy(TENANT,CLIENT));
        var factory=new ProxyFactory(target);factory.setProxyTargetClass(true);factory.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));
        var auth=(cn.jia.agent.security.AgentRuntimeAuthenticationService)factory.getProxy();service.setRuntimeAuthentication(auth);
        var session=new TransactionTemplate(manager).execute(tx->auth.issue(installations.lock(install),new cn.jia.agent.entity.AgentRuntimeV1SessionRequest(install,TENANT,CLIENT,agent,"1","a".repeat(64),host,boot),System.currentTimeMillis()));assertNotNull(session);
        var principal=new org.springframework.security.web.servletapi.SecurityContextHolderAwareRequestFilter();principal.afterPropertiesSet();
        var controller=new cn.jia.agent.api.PersonalWorkspaceConversationRuntimeController(proxy(service,PersonalWorkspaceExecutionService.class),auth);
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiEnvelopeAdvice(),new ApiRequestEnvelopeAdvice()).addFilters(new RequestIdFilter(),new cn.jia.agent.security.AgentRuntimeAuthenticationFilter(auth),principal).build();
        var json=new ObjectMapper();String queue="/internal/agent/executions/commands",run="/internal/agent/executions/"+created.executionId()+"/runs/"+created.runId();
        class Device{int seq;tools.jackson.databind.JsonNode call(String path,Object data,int expectedStatus)throws Exception{
            String id="pickup-"+(++seq);var req=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(data==null?org.springframework.http.HttpMethod.GET:org.springframework.http.HttpMethod.POST,URI.create(path));
            req.header("Authorization","AgentRuntime "+session.sessionToken()).header("X-Agent-Id",agent).header("X-Agent-Installation-Id",install).header("X-Agent-Host-Id",host).header("X-Agent-Runtime-Id",boot).header("X-Agent-Session-Generation",Long.toString(session.sessionGeneration())).header("X-Request-Id",id);
            if(data!=null)req.contentType("application/json").content(json.writeValueAsBytes(Map.of("protocolVersion",1,"requestId",id,"data",data)));
            var res=mvc.perform(req).andReturn().getResponse();assertEquals(expectedStatus,res.getStatus());return json.readTree(res.getContentAsByteArray()).path("data");}}
        var device=new Device();assertFalse(device.call(queue,null,401).has("items"));
        jdbc.update("UPDATE agent_runtime SET status='online' WHERE tenant_id=? AND client_id=? AND agent_id=? AND runtime_instance_id=?",TENANT,CLIENT,agent,boot);
        var headers=new cn.jia.agent.security.AgentRuntimeAuthenticationFilter.SessionHeaders(agent,install,host,boot,session.sessionGeneration(),session.sessionToken());
        auth.bind("pickup-socket",auth.verify(headers),()->true);
        try{var items=device.call(queue,null,200).path("items");assertEquals(1,items.size());var cmd=items.get(0);assertEquals(created.executionId(),cmd.path("executionId").asText());assertEquals(created.runId(),cmd.path("runId").asText());
            assertEquals(created.snapshotDigest(),cmd.path("snapshotSha256").asText());assertEquals(created.taskId(),cmd.at("/scope/taskId").asText());assertEquals(json.valueToTree(expected.commandOutputs()),cmd.path("outputs"));assertTrue(cmd.path("skills").isArray()&&cmd.path("skills").isEmpty());
            var claim=Map.of("commandId",cmd.path("commandId").asText(),"messageId",cmd.path("messageId").asText());var lease=device.call(run+"/lease",claim,200);assertEquals("1",lease.path("version").asText());assertTrue(lease.path("token").asText().matches("[0-9a-f-]{36}"));
            var replay=device.call(run+"/lease",claim,200);assertTrue(lease.equals(replay),"same live lease without exposing its token");
            var inputs=device.call(run+"/inputs",Map.of("version","1","token",lease.path("token").asText()),200);assertEquals(created.snapshotDigest(),inputs.path("snapshotSha256").asText());assertTrue(inputs.path("noReferencedMaterials").asBoolean());
            assertTrue(device.call(queue,null,200).path("items").isEmpty());assertEquals("RUNNING",independent.queryForObject("SELECT phase FROM agent_skill_execution WHERE execution_id=?",String.class,created.executionId()));
            String cli=System.getProperty("cyf.ownedPickupCodex");if(cli!=null){
                var peer=Path.of(System.getProperty("ownedMeteredRuntime"),"conf/codex-bridge/test/fixtures/quoted-native-pickup.mjs");assertTrue(Files.isRegularFile(peer));assertTrue(Files.isExecutable(Path.of(cli)));
                var temp=Files.createTempDirectory("quoted-pickup-");var out=temp.resolve("result.json");
                try{var pb=new ProcessBuilder("node",peer.toString());String path=System.getenv("PATH");pb.environment().clear();pb.environment().putAll(Map.of("PATH",path,"HOME",temp.toString(),"TMPDIR",temp.toString(),"CYF_CODEX_BIN",cli,"CYF_PICKUP_RESULT",out.toString()));pb.redirectError(ProcessBuilder.Redirect.INHERIT);pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
                    var proc=pb.start();try{try(var stdin=proc.getOutputStream()){stdin.write(json.writeValueAsBytes(Map.of("wire",cmd,"snapshot",inputs,"leaseVersion",lease.path("version"),"agent",agent,"chatPhase",quotedChatPhase)));}
                        assertTrue(proc.waitFor(60,TimeUnit.SECONDS),"owned native pickup timed out");assertEquals(0,proc.exitValue(),"owned native pickup");}finally{if(proc.isAlive()){proc.destroyForcibly();proc.waitFor();}}
                    assertTrue(Files.size(out)<8192);var actual=json.readTree(Files.readAllBytes(out));assertEquals(created.executionId(),actual.path("executionId").asText());assertEquals(created.runId(),actual.path("runId").asText());assertEquals(created.snapshotDigest(),actual.path("snapshotSha256").asText());assertEquals(2,actual.path("calls").asInt());
                    var phases=actual.path("phaseComparison");assertEquals(quotedChatPhase.get("binarySha256"),phases.path("binarySha256").asText());assertEquals(quotedChatPhase.get("instructionsSha256"),phases.path("chatInstructions").asText());
                    assertTrue(phases.path("nativeInstructions").asText().matches("[a-f0-9]{64}"));assertNotEquals(phases.path("nativeInstructions"),phases.path("chatInstructions"));assertEquals(0,phases.path("preflightProviderCalls").asInt(-1));
                    System.out.printf("QUOTED_PHASE_PROBE binary=%s chatInstructions=%s nativeInstructions=%s preflightProviderCalls=0%n",phases.path("binarySha256").asText(),phases.path("chatInstructions").asText(),phases.path("nativeInstructions").asText());
                    var body=(tools.jackson.databind.node.ObjectNode)actual.path("payload").deepCopy();body.remove("schemaVersion");body.put("snapshotSha256",created.snapshotDigest());body.set("fence",json.valueToTree(Map.of("version","1","token",lease.path("token").asText())));
                    String manifest=actual.path("manifestId").asText(),route=run+"/result-commits/"+manifest;var receipt=device.call(route,body,200);assertEquals(manifest,receipt.path("manifestId").asText());assertEquals("RESULT_COMMITTED",receipt.path("state").asText());assertEquals("ARCHIVING",receipt.path("phase").asText());
                    assertEquals("native pickup fixture",independent.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(result_manifest_json,'$.data.answer')) FROM agent_skill_execution WHERE execution_id=?",String.class,created.executionId()));assertEquals(receipt,device.call(route,body,200));
                    var different=body.deepCopy();different.set("data",json.valueToTree(Map.of("answer","different fixture")));
                    var altered=(tools.jackson.databind.node.ObjectNode)actual.path("payload").deepCopy();altered.set("data",different.path("data"));String changed=SkillCanonicalJson.write(json.readValue(json.writeValueAsBytes(altered),Map.class));
                    String changedId="manifest_"+java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(changed.getBytes(StandardCharsets.UTF_8)));assertNotEquals(manifest,changedId);device.call(run+"/result-commits/"+changedId,different,409);
                    assertEquals(receipt,device.call(route,body,200));
                    System.out.println("QUOTED_NATIVE_PICKUP requests=2 artifacts=0 result=RESULT_COMMITTED replay=confirmed");
                }finally{org.springframework.util.FileSystemUtils.deleteRecursively(temp);}
            }
        }finally{auth.disconnect("pickup-socket");}assertFalse(device.call(queue,null,401).has("items"));
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
    @Test void installedInstructionChoicePinsPackageAndOutputWithoutGrantOrTextMeasurement() throws Exception {
        var workspace=quoteWorkspace();var authority=quoteAndRecommendationAuthority();
        ReflectionTestUtils.setField(assignment,"taskLinks",workspace.links());ReflectionTestUtils.setField(assignment,"workspace",workspace.files());
        attachQuoteFile(0);
        String agent="agt_00000000000000000000000000000001";
        jdbc.update("INSERT INTO agent_persona_binding(jiacn,persona_code,agent_id,bound_at,status,tenant_id,client_id) VALUES(?,?,?,1,1,?,?)",OWNER,"quote-fixture",agent,TENANT,CLIENT);
        long binding=jdbc.queryForObject("SELECT id FROM agent_persona_binding WHERE agent_id=?",Long.class,agent);
        jdbc.update("INSERT INTO agent_identity_registry(canonical_agent_id,canonical_type,lifecycle_status,client_id,owner_jiacn,tenant_id,binding_id,provisioned_at,activated_at,audit_reason,create_time,update_time) VALUES (?,'OPAQUE','ACTIVE',?,?,?,?,1,1,'quote-fixture',1,1)",agent,CLIENT,OWNER,TENANT,binding);
        locked(()->authority.req().captureOnCreate(SCOPE,TASK,"有附件的事项","核对资料"));
        new ResourceDatabasePopulator(new FileSystemResource(DDL.resolve("agent-personal-workspace-v1_11-executions.sql")),new FileSystemResource(DDL.resolve("agent-task-native-preparation-v1.sql")),new FileSystemResource(DDL.resolve("agent-task-native-observation-v1.sql")),new FileSystemResource(DDL.resolve("agent-task-native-observation-expiry-v1.sql")),new FileSystemResource(DDL.resolve("agent-task-native-initial-admission-v1.sql")),new FileSystemResource(DDL.resolve("agent-task-native-selection-receipt-v1.sql"))).execute(jdbc.getDataSource());
        var service=new NativeTaskPreparationService(jdbc,roots,authority.req(),assignment,authority.policies(),new ObjectMapper());
        var req=authority.req().readCurrent(SCOPE,TASK);var target=authority.policies().quoteTargets(SCOPE,TASK,List.of(agent),false).getFirst();
        var input=locked(()->assignment.snapshotTaskInputsWithinLockedTask(SCOPE,TASK)).digest();
        var bindingKey=new NativeTaskPreparation.Binding(SCOPE,TASK,req.taskVersion(),req.requirementRevision(),req.sha256(),input,target);
        var installations=mock(InstalledSkillDescriptorLookup.class);
        var runtime=new SkillCapabilityLookup.Scope(TENANT,CLIENT,target.provider(),agent);
        var descriptor=new InstalledSkillDescriptorLookup.Descriptor("drawing","2.0","install-draw","a".repeat(64),null,null,"device","host",null);
        when(installations.readCurrent(runtime)).thenReturn(new InstalledSkillDescriptorLookup.Snapshot(List.of(descriptor)));
        when(installations.withPreparedRead(eq(runtime),any())).thenAnswer(i->((java.util.function.Supplier<?>)i.getArgument(1)).get());
        service.setInstallations(installations);service.setSelectionEvents(mock(org.springframework.context.ApplicationEventPublisher.class));
        var version=service.view(SCOPE,TASK,agent).selectionVersion();
        var catalog=mock(cn.jia.agent.skill.SkillManagementService.class);service.setSkillCatalog(catalog);
        var fact=new cn.jia.agent.skill.SkillManagementService.RecommendationSkill("install-draw","drawing","2.0","绘图",null,true,"NOT_EXECUTABLE","INSTRUCTION","key","fact");
        when(catalog.citableSkillPurposes(TENANT,CLIENT,agent)).thenReturn(List.of(fact));
        var options=service.skillOptions(SCOPE,TASK,agent);assertEquals(1,options.size());assertEquals("绘图",options.getFirst().title());
        assertTrue(options.getFirst().ref().matches("[a-f0-9]{64}"));assertFalse(options.toString().contains("install-draw"));
        var chosen=new TaskInitialActionSelection.SkillChoice(options.getFirst().ref(),"image/png");
        var unknown=new TaskInitialActionSelection.SkillChoice("unknown","image/png");
        assertThrows(TaskInitialActionSelection.Missing.class,()->service.chooseSkill(SCOPE,TASK,agent,version,unknown,PAYER,"missing"));
        var view=service.chooseSkill(SCOPE,TASK,agent,version,chosen,PAYER,"pick");assertEquals("INSTALLED_SKILL",view.choice());
        assertEquals(view,service.chooseSkill(SCOPE,TASK,agent,version,chosen,PAYER,"pick"));
        assertThrows(TaskInitialActionSelection.KeyConflict.class,()->service.chooseSkill(SCOPE,TASK,agent,version,unknown,PAYER,"pick"));
        var f=service.find(bindingKey);var intent=f.intent();assertEquals(NativeTaskPreparation.INSTRUCTION,intent.schema());
        var pin=intent.instruction();assertEquals(descriptor.packageSha256(),pin.packageSha256());
        var outputs=intent.output().commandOutputs();assertEquals(List.of("image/png"),outputs.get("mimeTypes"));
        var cmp=service.reserveComparisons(SCOPE,TASK,req.taskVersion(),List.of(agent));
        assertFalse(service.recordMeasurement(cmp,agent,new NativeTaskConfigurationProbe.Measurement(f.sha256(),null,"NATIVE_DISPATCH_GROUP_V1","a".repeat(64),"b".repeat(64),"c".repeat(64),"NATIVE_LOCAL_ARTIFACT_TEXT_V1",null)));
        var granted=new TaskAssigneeAuthorityService.Target(TENANT,CLIENT,target.provider(),agent,binding,null);
        var measured=cn.jia.chat.handler.SelectedProbeTestPeer.exchange(f,new AgentManagedSessionLookup.SessionFence("install","host","boot",2)).toCompletableFuture().join();
        assertNotNull(measured);assertEquals("NATIVE_PINNED_IMAGE_V1",measured.capturePolicyVersion());assertTrue(service.recordMeasurement(cmp,agent,measured));
        var other=new NativeTaskPreparation.Instruction("install-draw",pin.key(),pin.version(),"b".repeat(64),pin.policy());
        assertThrows(PersonalWorkspaceExecutionService.Failure.class,()->locked(()->{service.consumeFirst(SCOPE,TASK,granted,f.sha256(),"first",pin.policy(),List.of(other.selection()),outputs);return null;}));
        assertThrows(PersonalWorkspaceExecutionService.Failure.class,()->locked(()->{service.consumeFirst(SCOPE,TASK,granted,f.sha256(),"first",pin.policy(),List.of(pin.selection()),Map.of());return null;}));
        assertNull(jdbc.queryForObject("SELECT initial_execution_id FROM agent_task_native_preparation",String.class));
        var group=new TaskBillingService.MeasuredGroup(measured.version(),measured.executionConfigSha256(),measured.routeSha256(),"r1",measured.toolConfigSha256(),measured.capturePolicyVersion());
        var winner=mock(TaskBillingService.class);when(winner.heldInitialCommitment(BILLING_TASK,agent)).thenReturn(new TaskBillingService.InitialCommitment(f.sha256(),group));
        var admission=new QuotedInitialActionAdmission(winner,service);
        var profile=cn.jia.agent.service.usage.NativeCaptureEvidence.imageProfile(measured.version(),measured.executionConfigSha256(),measured.routeSha256(),measured.toolConfigSha256());
        assertNotNull(profile);assertEquals(profile,locked(()->admission.admit(SCOPE,TASK,granted,"first",pin.policy(),List.of(pin.selection()),outputs)));
        assertEquals(profile,locked(()->admission.admit(SCOPE,TASK,granted,"first",pin.policy(),List.of(pin.selection()),outputs)));
        assertNull(locked(()->admission.admit(SCOPE,TASK,granted,"followup",pin.policy(),List.of(pin.selection()),outputs)));
        assertEquals("first",jdbc.queryForObject("SELECT initial_execution_id FROM agent_task_native_preparation",String.class));
        assertEquals(0,count("agent_task_execution_grant"));assertEquals(0,count("economy_transaction"));
    }
    @Test void explicitNativeTaskIntentFreezesOnlyExactOpenInputsAndCandidateWithoutDispatch() throws Exception {
        var workspace=quoteWorkspace();var authority=quoteAndRecommendationAuthority();
        ReflectionTestUtils.setField(assignment,"taskLinks",workspace.links());ReflectionTestUtils.setField(assignment,"workspace",workspace.files());
        attachQuoteFile(0);
        String agent="agt_00000000000000000000000000000001";
        jdbc.update("INSERT INTO agent_persona_binding(jiacn,persona_code,agent_id,bound_at,status,tenant_id,client_id) VALUES(?,?,?,1,1,?,?)",OWNER,"quote-fixture",agent,TENANT,CLIENT);
        long binding=jdbc.queryForObject("SELECT id FROM agent_persona_binding WHERE agent_id=?",Long.class,agent);
        jdbc.update("INSERT INTO agent_identity_registry(canonical_agent_id,canonical_type,lifecycle_status,client_id,owner_jiacn,tenant_id,binding_id,provisioned_at,activated_at,audit_reason,create_time,update_time) VALUES (?,'OPAQUE','ACTIVE',?,?,?,?,1,1,'quote-fixture',1,1)",agent,CLIENT,OWNER,TENANT,binding);
        locked(()->authority.req().captureOnCreate(SCOPE,TASK,"有附件的事项","核对资料"));
        new ResourceDatabasePopulator(new FileSystemResource(DDL.resolve("agent-personal-workspace-v1_11-executions.sql")),new FileSystemResource(DDL.resolve("agent-task-native-preparation-v1.sql")),new FileSystemResource(DDL.resolve("agent-task-native-observation-v1.sql")),new FileSystemResource(DDL.resolve("agent-task-native-observation-expiry-v1.sql")),new FileSystemResource(DDL.resolve("agent-task-native-initial-admission-v1.sql")),new FileSystemResource(DDL.resolve("agent-task-native-selection-receipt-v1.sql"))).execute(jdbc.getDataSource());
        var service=new NativeTaskPreparationService(jdbc,roots,authority.req(),assignment,authority.policies(),new ObjectMapper());
        var req=authority.req().readCurrent(SCOPE,TASK);var target=authority.policies().quoteTargets(SCOPE,TASK,List.of(agent),false).getFirst();
        var input=locked(()->assignment.snapshotTaskInputsWithinLockedTask(SCOPE,TASK)).digest();
        var bindingKey=new NativeTaskPreparation.Binding(SCOPE,TASK,req.taskVersion(),req.requirementRevision(),req.sha256(),input,target);
        var types=new java.util.ArrayList<>(List.of("text/plain"));var output=new NativeTaskPreparation.Output(NativeTaskPreparation.Mode.ARTIFACTS,types);types.clear();
        var intent=new NativeTaskPreparation.Intent("TASK_NATIVE_LOCAL_FILES_V1",bindingKey,output);assertNull(service.find(bindingKey));
        assertTrue(output.comparisonOutputs().containsKey("dataSchema"));assertNull(output.comparisonOutputs().get("dataSchema"));assertFalse(output.commandOutputs().containsKey("dataSchema"));
        assertEquals(List.of("text/plain"),output.mimeTypes());int quotes=count("economy_task_quote"),executions=count("agent_personal_workspace_execution");
        assertThrows(InjectedFailure.class,()->locked(()->{service.freeze(intent);throw new InjectedFailure();}));assertNull(service.find(bindingKey));
        var events=mock(org.springframework.context.ApplicationEventPublisher.class);service.setSelectionEvents(events);
        var view=service.view(SCOPE,TASK,agent);assertNull(view.choice());verifyNoInteractions(events);
        var choice=TaskInitialActionSelection.Choice.LOCAL_TEXT_FILE;
        assertThrows(TaskInitialActionSelection.Missing.class,()->service.view(SCOPE,TASK,"unknown"));
        assertThrows(TaskInitialActionSelection.Changed.class,()->service.choose(SCOPE,TASK,agent,"0".repeat(64),choice,PAYER,"select"));
        var selected=service.choose(SCOPE,TASK,agent,view.selectionVersion(),choice,PAYER,"select");assertEquals("LOCAL_TEXT_FILE",selected.choice());
        assertEquals(selected,service.choose(SCOPE,TASK,agent,view.selectionVersion(),choice,PAYER,"select"));
        assertThrows(TaskInitialActionSelection.Changed.class,()->service.choose(SCOPE,TASK,agent,view.selectionVersion(),TaskInitialActionSelection.Choice.LOCAL_ANSWER,PAYER,"new-choice"));
        verify(events).publishEvent(new TaskInitialActionSelection.Selected(bindingKey));verifyNoMoreInteractions(events);
        var frozen=service.freeze(intent);assertEquals(frozen,service.freeze(intent));assertEquals(frozen,service.find(bindingKey));
        assertThrows(IllegalStateException.class,()->service.freeze(new NativeTaskPreparation.Intent(intent.schema(),bindingKey,new NativeTaskPreparation.Output(NativeTaskPreparation.Mode.ANSWER,List.of()))));
        var wrong=new NativeTaskPreparation.Binding(SCOPE,TASK,req.taskVersion(),req.requirementRevision(),req.sha256(),input,new AgentExternalServicePolicyService.QuoteTarget(agent,target.provider(),target.binding()+1,target.externalPolicyRevision()));
        var ids=List.of(agent,"agt_00000000000000000000000000000002",agent);
        assertEquals(Map.of(agent,frozen),service.currentForProbes(SCOPE,TASK,req.taskVersion(),ids));
        assertTrue(service.currentForProbes(SCOPE,TASK,req.taskVersion()+1,ids).isEmpty());
        assertEquals(Set.of(bindingKey),service.readBatch(SCOPE,TASK,List.of(bindingKey,wrong),false).due());
        assertTrue(service.reserveDue(wrong,Set.of(wrong)).decisions().isEmpty());
        try(var pool=Executors.newFixedThreadPool(2)){
            var first=pool.submit(()->service.reserveDue(bindingKey,Set.of(bindingKey)));var second=pool.submit(()->service.reserveDue(bindingKey,Set.of(bindingKey)));
            assertEquals(1,first.get(5,TimeUnit.SECONDS).decisions().size()+second.get(5,TimeUnit.SECONDS).decisions().size());}
        assertTrue(service.readBatch(SCOPE,TASK,List.of(bindingKey),false).due().isEmpty());
        var slow=service.reserveComparisons(SCOPE,TASK,req.taskVersion(),ids);var fresh=service.reserveComparisons(SCOPE,TASK,req.taskVersion(),ids);
        var measured=new NativeTaskConfigurationProbe.Measurement(frozen.sha256(),new AgentManagedSessionLookup.SessionFence("install","host","boot",2),"NATIVE_DISPATCH_GROUP_V1","1".repeat(64),"2".repeat(64),"3".repeat(64),"NATIVE_LOCAL_ARTIFACT_TEXT_V1",new NativeTaskConfigurationProbe.Profile(1,"4".repeat(64)));
        assertFalse(service.recordMeasurement(slow,agent,measured));assertTrue(service.recordMeasurement(fresh,agent,measured));
        assertFalse(service.recordMeasurement(fresh,agent,measured));assertFalse(locked(()->service.lockMeasurement(slow.ticket(),frozen,measured)));
        assertTrue(locked(()->service.lockMeasurement(fresh.ticket(),frozen,measured)));
        assertThrows(InjectedFailure.class,()->locked(()->{service.reserveComparisons(SCOPE,TASK,req.taskVersion(),ids);throw new InjectedFailure();}));
        assertTrue(locked(()->service.lockMeasurement(fresh.ticket(),frozen,measured)));
        // A reader pins the journal row. The concurrent writer has already taken the task root but cannot replace the ticket.
        try(var pool=Executors.newSingleThreadExecutor()){
            var readFirst=new TransactionTemplate(manager).execute(tx->{assertTrue(service.lockMeasurement(fresh.ticket(),frozen,measured));
                var next=pool.submit(()->service.reserveComparisons(SCOPE,TASK,req.taskVersion(),ids));awaitPreparationRowWait(next);return next;});
            var unmeasured=readFirst.get(5,TimeUnit.SECONDS);
            // Writer goes first; a late publish on another pooled transaction waits, then sees the new ticket and loses.
            var late=locked(()->{service.reserveComparisons(SCOPE,TASK,req.taskVersion(),ids);var old=pool.submit(()->service.recordMeasurement(unmeasured,agent,measured));
                awaitPreparationRowWait(old);return old;});assertFalse(late.get(5,TimeUnit.SECONDS));
        }
        var bounded=service.reserveComparisons(SCOPE,TASK,req.taskVersion(),ids);assertTrue(service.recordMeasurement(bounded,agent,measured));
        assertEquals(new NativeTaskPreparation.Recorded(bounded.ticket(),frozen,measured),service.recorded(SCOPE,TASK,List.of(bindingKey,wrong),false).get(bindingKey));
        jdbc.update("UPDATE agent_task_native_preparation SET probe_valid_until=UTC_TIMESTAMP(6) WHERE probe_ticket=?",bounded.ticket());
        assertTrue(service.recorded(SCOPE,TASK,List.of(bindingKey),false).isEmpty());assertFalse(locked(()->service.lockMeasurement(bounded.ticket(),frozen,measured)));
        var tooLate=service.reserveComparisons(SCOPE,TASK,req.taskVersion(),ids);
        jdbc.update("UPDATE agent_task_native_preparation SET probe_valid_until=NULL WHERE probe_ticket=?",tooLate.ticket());assertFalse(service.recordMeasurement(tooLate,agent,measured));
        verifyPreparedEpochLock(agent,binding,measured.fence());
        var nextFence=new AgentManagedSessionLookup.SessionFence("install","host","next-boot",3);
        var m=new NativeTaskConfigurationProbe.Measurement(frozen.sha256(),nextFence,measured.version(),measured.executionConfigSha256(),measured.routeSha256(),measured.toolConfigSha256(),measured.capturePolicyVersion(),measured.profile());
        var config=new cn.jia.economy.task.PreparedReferenceRoutes.Configuration(m.version(),m.executionConfigSha256(),m.routeSha256(),m.toolConfigSha256(),m.capturePolicyVersion());
        var routeKey=Map.of("tenant",TENANT,"client",CLIENT,"owner",OWNER,"configuration",config);
        var manifest=new ObjectMapper().writeValueAsBytes(Map.of("schema","prepared-reference-routes-v1","entries",List.of(Map.of("key",routeKey,"channel","fixture","model","fixture","tier","test"))));
        var file=Files.write(httpDirectory.resolve("reference-routes.json"),manifest);
        var routes=new cn.jia.economy.service.impl.PinnedReferenceRoutes(file.toString(),java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(manifest)));
        var terms=routes.resolve(new TaskBillingService.Task(new EconomyScope(TENANT,CLIENT),OWNER,TASK),config);assertNotNull(terms);
        var guard=new cn.jia.agent.security.PreparedRuntimeEpochFence(jdbc);
        var source=new cn.jia.agent.service.recommendation.RecordedProspectiveTaskExecution(service,guard,routes);
        var inputs=new cn.jia.agent.service.recommendation.ProspectiveTaskExecution.Inputs(new TaskBillingService.Task(new EconomyScope(TENANT,CLIENT),OWNER,TASK),Long.toString(req.taskVersion()),req.requirementRevision(),req.sha256(),input);
        var targets=List.of(target,new AgentExternalServicePolicyService.QuoteTarget(ids.get(1),OWNER,binding+1,target.externalPolicyRevision()));
        var missing=source.observe(inputs,targets);assertTrue(missing.isEmpty());assertTrue(locked(()->source.current(inputs,targets,missing)));
        // A quote observation does not acquire a ticket or schedule a runtime comparison.
        assertTrue(service.readBatch(SCOPE,TASK,List.of(bindingKey),false).due().contains(bindingKey));
        var nextTicket=service.reserveDue(bindingKey,Set.of(bindingKey));assertTrue(service.recordMeasurement(nextTicket,agent,m));
        assertTrue(service.reserveDue(bindingKey,Set.of(bindingKey)).decisions().isEmpty());
        var ready=source.observe(inputs,targets);assertEquals(Map.of(agent,new cn.jia.agent.service.recommendation.ProspectiveTaskExecution.Evidence(nextTicket.ticket(),terms,frozen.sha256())),ready);
        assertFalse(locked(()->source.current(inputs,targets,missing)));assertTrue(locked(()->source.current(inputs,targets,ready)));
        // This fixture supplies a deterministic neighbor valuation, not a calibration or an accuracy claim.
        var selectedProof=ready.get(agent);var vec=new java.util.ArrayList<>(java.util.Collections.nCopies(1024,0f));vec.set(0,1f);
        var features=new TaskEstimateFeatures(TaskEstimateFeatures.VERSION,cn.jia.agent.service.recommendation.QuoteHttpTestWiring.template(),req.sha256(),input,"b".repeat(64),new TaskEstimateFeatures.Identity("cfg","fixture",1024),vec,List.of(),null);
        var preparation=new TaskBillingService.MeasuredPreparation(features,selectedProof.terms().execution(),"v1","fixture","fixture",fixtureNeighborEstimate());
        var quoteBinding=new TaskBillingService.Binding(req.requirementRevision(),req.sha256(),input,agent,"prepared-hold");
        var offer=locked(()->billing.storeMeasuredBatch(inputs.task(),PAYER,List.of(new TaskBillingService.MeasuredWrite(quoteBinding,preparation,selectedProof.initialIntentSha256())))).getFirst();
        assertNull(locked(()->billing.heldInitialIntent(inputs.task(),agent)));
        var reservation=locked(()->billing.reserve(inputs.task(),offer.quoteId(),quoteBinding,PAYER));
        assertEquals(reservation,locked(()->billing.reserve(inputs.task(),offer.quoteId(),quoteBinding,PAYER)));
        assertEquals(1,count("economy_transaction"));assertEquals(75,wallet(independent));
        var admit=new QuotedInitialActionAdmission(billing,service);var granted=new TaskAssigneeAuthorityService.Target(TENANT,CLIENT,OWNER,agent,binding,null);
        assertEquals(output,locked(()->admit.pending(SCOPE,TASK,agent)));assertEquals(output,locked(()->admit.pending(SCOPE,TASK,agent))); // projection never consumes
        assertThrows(PersonalWorkspaceExecutionService.Failure.class,()->locked(()->{admit.admit(SCOPE,TASK,granted,"first",SkillExecutionService.NATIVE_SKILL_SCRIPT_POLICY,List.of(Map.of()),output.commandOutputs());return null;}));
        assertThrows(PersonalWorkspaceExecutionService.Failure.class,()->locked(()->{admit.admit(SCOPE,TASK,granted,"first",SkillExecutionService.NATIVE_LOCAL_FILES_POLICY,List.of(),Map.of());return null;}));
        assertThrows(InjectedFailure.class,()->locked(()->{admit.admit(SCOPE,TASK,granted,"first",SkillExecutionService.NATIVE_LOCAL_FILES_POLICY,List.of(),output.commandOutputs());throw new InjectedFailure();}));
        assertNull(jdbc.queryForObject("SELECT initial_execution_id FROM agent_task_native_preparation",String.class));
        locked(()->{admit.admit(SCOPE,TASK,granted,"first",SkillExecutionService.NATIVE_LOCAL_FILES_POLICY,List.of(),output.commandOutputs());return null;});
        assertNull(locked(()->admit.pending(SCOPE,TASK,agent)));
        locked(()->{admit.admit(SCOPE,TASK,granted,"followup",SkillExecutionService.NATIVE_SKILL_SCRIPT_POLICY,List.of(Map.of()),Map.of());return null;});assertEquals("first",jdbc.queryForObject("SELECT initial_execution_id FROM agent_task_native_preparation",String.class));
        var httpCandidates=List.of(agent,ids.get(1));
        verifyPreparedQuoteHttp(authority,workspace,source,httpCandidates,()->guard.retire(TENANT,CLIENT,agent,OWNER,nextFence));
        assertFalse(locked(()->source.current(inputs,targets,ready)));assertEquals(missing,source.observe(inputs,targets));assertTrue(locked(()->source.current(inputs,targets,missing)));

        service.reserveComparisons(SCOPE,TASK,req.taskVersion(),ids); // timeout/no reply cannot leave yesterday's observation current
        assertFalse(locked(()->service.lockMeasurement(fresh.ticket(),frozen,measured)));

        assertNull(service.find(wrong));assertThrows(IllegalStateException.class,()->service.freeze(new NativeTaskPreparation.Intent(intent.schema(),wrong,output)));
        jdbc.update("UPDATE agent_personal_workspace_task_file_link SET link_state='DETACHED' WHERE file_id='file-0'");
        assertTrue(service.currentForProbes(SCOPE,TASK,req.taskVersion(),ids).isEmpty());
        assertEquals(frozen,service.find(bindingKey)); // historical intent is intact, but no longer observable as current
        assertThrows(IllegalStateException.class,()->service.freeze(intent));assertEquals(1,count("agent_task_native_preparation"));assertEquals(quotes+1+2*httpCandidates.size(),count("economy_task_quote")); // one measured hold and two unique HTTP batches
        assertEquals(executions,count("agent_personal_workspace_execution"));
        verifySignedSelectionWorker(service,ids.get(1),()->new NativeTaskPreparationService(jdbc,roots,authority.req(),assignment,authority.policies(),new ObjectMapper()));
    }
    private void verifySignedSelectionWorker(NativeTaskPreparationService service,String agent,java.util.function.Supplier<NativeTaskPreparationService> factory)throws Exception{
        var sessions=mock(cn.jia.agent.security.AgentRuntimeAuthenticationService.class);
        var proof=new cn.jia.agent.security.AgentRuntimeAuthenticationService.Proof(new cn.jia.agent.security.AgentRuntimeAuthentication.Scope(TENANT,CLIENT,OWNER,agent,"boot"),"inst","host",1,"fixture",1,1);
        when(sessions.currentRegisteredProofs(TENANT,CLIENT,Set.of(agent))).thenReturn(Map.of(agent,proof));
        var sends=new java.util.concurrent.atomic.AtomicInteger();
        NativeTaskConfigurationProbe probe=(decision,fence)->{
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(NativeTaskPreparation.Mode.ANSWER,decision.intent().output().mode());assertEquals(decision,service.find(decision.intent().binding()));sends.incrementAndGet();
            return cn.jia.chat.handler.SelectedProbeTestPeer.exchange(decision,fence);};
        try(var pool=Executors.newSingleThreadExecutor();var ctx=new org.springframework.context.annotation.AnnotationConfigApplicationContext()){
            ctx.registerBean(org.springframework.transaction.event.TransactionalEventListenerFactory.class);
            ctx.registerBean(NativeTaskConfigurationProbe.class,()->probe);
            var reader=new cn.jia.agent.service.recommendation.PreparedTaskTermsReader(service,sessions,ctx.getDefaultListableBeanFactory().getBeanProvider(NativeTaskConfigurationProbe.class));
            var queued=new java.util.concurrent.atomic.AtomicInteger();var release=new java.util.concurrent.CountDownLatch(1);
            // Occupy the original worker until cross-node replay has completed. Do not stop its event context.
            pool.submit(()->{release.await();return null;});
            try {
                ctx.registerBean(cn.jia.agent.service.recommendation.SelectedTaskComparison.class,()->new cn.jia.agent.service.recommendation.SelectedTaskComparison(job->{queued.incrementAndGet();pool.execute(job);},reader));ctx.refresh();service.setSelectionEvents(ctx);
                java.util.function.Supplier<NativeTaskPreparationService> peer=()->{var p=factory.get();p.setSelectionEvents(ctx);return p;};
                verifySignedSelectionReplay(agent,service,peer,()->{
                    String measured="SELECT COUNT(*) FROM agent_task_native_preparation WHERE JSON_UNQUOTE(JSON_EXTRACT(intent_json,'$.binding.target.agent'))=? AND measurement_sha IS NOT NULL";
                    assertEquals(1,queued.get());assertEquals(0,sends.get());assertEquals(0,independent.queryForObject(measured,Integer.class,agent));
                    release.countDown();pool.submit(()->{}).get(5,TimeUnit.SECONDS);assertEquals(1,sends.get());assertEquals(1,independent.queryForObject(measured,Integer.class,agent));return null;
                });assertEquals(1,queued.get());assertEquals(1,sends.get());
            }finally{release.countDown();}
        }
    }
    private void verifySignedSelectionReplay(String agent,NativeTaskPreparationService original,java.util.function.Supplier<NativeTaskPreparationService> peer,java.util.concurrent.Callable<Void> drain)throws Exception{
        var token=httpToken();var mapper=new ObjectMapper();
        try(var client=HttpClient.newHttpClient();var a=new AssignmentNode();var b=new AssignmentNode()){
            class Call{tools.jackson.databind.JsonNode run(AssignmentNode node,String key,String choice,String version,int expected,String code)throws Exception{
                var uri=URI.create(node.base+"/agent/tasks/"+TASK+"/initial-actions/"+agent);var req=HttpRequest.newBuilder(uri).header("Authorization","Bearer "+token).header("X-Request-Id","selection");if(choice!=null){
                    if(key!=null)req.header("Idempotency-Key",key);var data=Map.of("choice",choice,"selectionVersion",version);
                    req.header("Content-Type","application/json").PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of("protocolVersion",1,"requestId","selection","data",data))));}
                var r=client.send(req.build(),HttpResponse.BodyHandlers.ofString());assertEquals(expected,r.statusCode(),r.body());var env=mapper.readTree(r.body());assertEquals(code,env.path("code").asText());assertEquals("selection",env.path("requestId").asText());String cache=String.join(",",r.headers().allValues("Cache-Control"));assertTrue(cache.contains("no-store")&&cache.contains("private"),cache);return env.path("data");
            }}var call=new Call();a.selection=peer.get();a.start("peer-a");var empty=call.run(a,null,null,null,200,"E0");String stamp=empty.path("selectionVersion").asText();int before=count("agent_task_native_selection_receipt"),frozen=count("agent_task_native_preparation");
            tools.jackson.databind.JsonNode saved;
            try(var open=new AssignmentNode()){open.selection=original;open.start("open");assertEquals(empty,call.run(open,null,null,null,200,"E0"));call.run(open,null,"LOCAL_ANSWER",stamp,400,"BAD_REQUEST");saved=call.run(open,"one","LOCAL_ANSWER",stamp,200,"E0");assertEquals("LOCAL_ANSWER",saved.path("choice").asText());}
            b.selection=peer.get();b.start("peer-b");assertNotEquals(a.base,b.base);
            for(var node:List.of(a,b)){assertEquals(saved,call.run(node,null,null,null,200,"E0"));assertEquals(saved,call.run(node,"one","LOCAL_ANSWER",stamp,200,"E0"));}
            drain.call();jdbc.update("UPDATE agent_task_meta SET task_version=task_version+1 WHERE task_id=?",TASK);
            for(var node:List.of(b,a)){assertEquals(saved,call.run(node,"one","LOCAL_ANSWER",stamp,200,"E0"));call.run(node,"one","LOCAL_TEXT_FILE",stamp,409,"INITIAL_ACTION_IDEMPOTENCY_CONFLICT");}
            try(var reopened=new AssignmentNode()){reopened.selection=peer.get();reopened.start("reopened");call.run(reopened,"new","LOCAL_ANSWER",stamp,409,"INITIAL_ACTION_STALE");}
            assertEquals(before+1,count("agent_task_native_selection_receipt"));assertEquals(frozen+1,count("agent_task_native_preparation"));
        }
    }
    // Use an actual signed quote request with two authorized candidates and one current native receipt.
    // The test injector retires that real epoch after estimation, before the quote transaction rechecks it.
    private void verifyPreparedQuoteHttp(Authority authority,WorkspaceRows workspace,cn.jia.agent.service.recommendation.RecordedProspectiveTaskExecution source,List<String> ids,Runnable retire)throws Exception{
        String second=ids.getLast();jdbc.update("INSERT INTO agent_persona_binding(jiacn,persona_code,agent_id,bound_at,status,tenant_id,client_id) VALUES(?,?,?,1,1,?,?)",OWNER,"second",second,TENANT,CLIENT);
        long binding=jdbc.queryForObject("SELECT id FROM agent_persona_binding WHERE agent_id=?",Long.class,second);
        jdbc.update("INSERT INTO agent_identity_registry(canonical_agent_id,canonical_type,lifecycle_status,client_id,owner_jiacn,tenant_id,binding_id,provisioned_at,activated_at,audit_reason,create_time,update_time) VALUES (?,'OPAQUE','ACTIVE',?,?,?,?,1,1,'fixture',1,1)",second,CLIENT,OWNER,TENANT,binding);
        var materials=new cn.jia.agent.service.recommendation.RecommendationMaterials(proxy(new PersonalWorkspaceTaskLinkServiceImpl(workspace.links()),PersonalWorkspaceTaskLinkService.class),new PersonalWorkspaceServiceImpl(workspace.files(),mock(PersonalWorkspaceStorage.class),mock(PersonalWorkspaceWriteService.class)));
        independentQuoteController=cn.jia.agent.service.recommendation.QuoteHttpTestWiring.controller(roots,batches,authority.req(),assignment,authority.policies(),materials,billing);
        startHttp();int initial=count("economy_task_quote_batch"),money=count("economy_transaction");String token=httpToken();var mapper=new ObjectMapper();
        var body=mapper.writeValueAsString(Map.of("protocolVersion",1,"requestId","prepared","data",Map.of("expectedTaskVersion","0","agentIds",ids)));
        try(var client=HttpClient.newHttpClient()){
            class Post{tools.jackson.databind.JsonNode run(String key,int status,String code)throws Exception{
                var r=HttpRequest.newBuilder(URI.create(origin+"/agent/tasks/"+TASK+"/quotes")).header("Content-Type","application/json").header("Authorization","Bearer "+token).header("X-Request-Id","prepared").header("Idempotency-Key",key).POST(HttpRequest.BodyPublishers.ofString(body)).build();
                var reply=client.send(r,HttpResponse.BodyHandlers.ofString());assertEquals(status,reply.statusCode(),reply.body());var env=mapper.readTree(reply.body());assertEquals(code,env.path("code").asText());return env.path("data");
            }}var post=new Post();var original=post.run("prepared-first",200,"E0");var rows=original.path("quotes");assertEquals(2,rows.size());
            for(int i=0;i<2;i++){var row=rows.get(i);assertEquals(ids.get(i),row.path("agentId").asText());assertFixedQuote(row);}
            assertNotEquals(rows.get(0).path("quoteId"),rows.get(1).path("quoteId"));
            retire.run();assertEquals(initial+1,count("economy_task_quote_batch"));
            assertEquals(original,post.run("prepared-first",200,"E0"));var fresh=post.run("prepared-racing",200,"E0");assertFixedQuote(fresh.path("quotes").get(0));
        }assertEquals(initial+2,count("economy_task_quote_batch"));assertEquals(money,count("economy_transaction"));
    }
    private void awaitPreparationRowWait(java.util.concurrent.Future<?> writer){
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        do{if(admin.queryForObject("SELECT COUNT(*) FROM performance_schema.data_lock_waits w JOIN performance_schema.data_locks l ON l.ENGINE=w.ENGINE AND l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID WHERE l.OBJECT_SCHEMA=? AND l.OBJECT_NAME='agent_task_native_preparation'",Integer.class,database)>0)return;
            java.util.concurrent.locks.LockSupport.parkNanos(5_000_000);
        }while(!writer.isDone()&&System.nanoTime()<until);fail("expected a real preparation row lock wait");
    }
    private void verifyRegisteredPricingProfile(String agent,long binding,AgentManagedSessionLookup.SessionFence fence){
        new ResourceDatabasePopulator(new FileSystemResource(DDL.resolve("agent-runtime-pricing-profiles-v1.sql"))).execute(jdbc.getDataSource());
        var repo=new cn.jia.agent.security.RegisteredPricingProfiles(jdbc);var target=new AgentExternalServicePolicyService.QuoteTarget(agent,OWNER,binding,0);
        var h="a".repeat(64);
        var row=new java.util.LinkedHashMap<String,String>();var keys=cn.jia.agent.service.NativePricingProfiles.FIELDS;
        var values=List.of("edit","native-image-context-1",h,h,"NATIVE_PINNED_IMAGE_V1","parent",h,"c","image","base","openai-images-modalities-v1","RESPONSES_TEXT_TOKENS_V1",h);
        for(int i=0;i<keys.size();i++)row.put(keys.get(i),values.get(i));
        var arr=List.of(row);String bytes=new ObjectMapper().writeValueAsString(arr);String sha=cn.jia.agent.service.NativePricingProfiles.digest(bytes);
        java.util.function.BiFunction<String,Boolean,Map<String,Object>> report=(seq,empty)->Map.of("version","NATIVE_PRICING_PROFILES_V1","sequence",seq,"profiles",empty?List.of():arr,"sha256",empty?cn.jia.agent.service.NativePricingProfiles.digest("[]"):sha);
        java.util.function.Consumer<Map<String,Object>> send=wire->locked(()->{repo.observe(TENANT,CLIENT,OWNER,agent,fence,wire);return null;});
        java.util.function.Supplier<Map<String,cn.jia.agent.security.RegisteredPricingProfiles.Observed>> read=()->repo.read(TENANT,CLIENT,List.of(target),false);
        send.accept(report.apply("2",false));assertEquals(sha,read.get().get(agent).sha());send.accept(report.apply("1",true));assertEquals(sha,read.get().get(agent).sha());
        assertTrue(repo.read(TENANT,CLIENT,List.of(new AgentExternalServicePolicyService.QuoteTarget(agent,"other",binding,0)),false).isEmpty());
        jdbc.update("UPDATE agent_runtime SET status='offline' WHERE agent_id=?",agent);assertEquals(read.get(),locked(()->repo.read(TENANT,CLIENT,List.of(target),true)));
        assertEquals(sha,read.get().get(agent).sha());send.accept(report.apply("2",true));assertTrue(read.get().isEmpty());send.accept(report.apply("2",false));assertTrue(read.get().isEmpty());
        send.accept(report.apply("3",false));assertEquals(sha,read.get().get(agent).sha());
        var unauth=new java.util.HashMap<>(report.apply("3",false));unauth.put("unexpected","never persist");send.accept(unauth);assertTrue(read.get().isEmpty());
        send.accept(report.apply("4",false));jdbc.update("UPDATE agent_runtime SET status='online',runtime_session_generation=runtime_session_generation+1 WHERE agent_id=?",agent);
        assertTrue(read.get().isEmpty());assertThrows(IllegalStateException.class,()->send.accept(report.apply("99",true)));
        jdbc.update("UPDATE agent_runtime SET runtime_session_generation=? WHERE agent_id=?",fence.sessionGeneration(),agent);
    }
    private void verifyPreparedEpochLock(String agent,long binding,AgentManagedSessionLookup.SessionFence fence)throws Exception{
        for(String f:List.of("agent-runtime-v1-schema.sql","agent-runtime-session-fence-v1.sql","agent-runtime-prepared-profile-v1.sql"))new ResourceDatabasePopulator(new FileSystemResource(DDL.resolve(f))).execute(jdbc.getDataSource());
        jdbc.update("INSERT INTO agent_runtime_v1_installation(installation_id,canonical_agent_id,manifest_version,manifest_sha256,enrollment_secret_hash,enrollment_expires_at,status,tenant_id,client_id) VALUES(?,?,'test',?,UNHEX(?),1,'ACTIVE',?,?)",fence.runtimeInstallationId(),agent,"a".repeat(64),"a".repeat(64),TENANT,CLIENT);
        jdbc.update("INSERT INTO agent_runtime(agent_id,name,tenant_id,client_id,owner_jiacn,binding_id,status,runtime_installation_id,runtime_host_id,runtime_instance_id,runtime_session_generation) VALUES(?,'test',?,?,?,?,'online',?,?,?,?)",agent,TENANT,CLIENT,OWNER,binding,fence.runtimeInstallationId(),fence.hostId(),fence.runtimeInstanceId(),fence.sessionGeneration());
        verifyRegisteredPricingProfile(agent,binding,fence);
        var guard=new cn.jia.agent.security.PreparedRuntimeEpochFence(jdbc);var epoch=new cn.jia.agent.security.PreparedRuntimeEpochFence.Epoch(agent,OWNER,binding,fence,new NativeTaskConfigurationProbe.Profile(1,"4".repeat(64)));
        java.util.function.Supplier<Boolean> exact=()->guard.lock(TENANT,CLIENT,List.of(epoch));assertFalse(locked(exact));
        guard.report(TENANT,CLIENT,agent,OWNER,fence,epoch.profile());assertTrue(locked(exact));
        guard.report(TENANT,CLIENT,agent,OWNER,fence,new NativeTaskConfigurationProbe.Profile(2,null));
        guard.report(TENANT,CLIENT,agent,OWNER,fence,epoch.profile());assertFalse(locked(exact));
        var restored=new cn.jia.agent.security.PreparedRuntimeEpochFence.Epoch(agent,OWNER,binding,fence,new NativeTaskConfigurationProbe.Profile(3,epoch.profile().profileSha256()));
        guard.report(TENANT,CLIENT,agent,OWNER,fence,restored.profile());assertTrue(locked(()->guard.lock(TENANT,CLIENT,List.of(restored))));assertFalse(locked(exact));
        guard.report(TENANT,CLIENT,agent,OWNER,fence,new NativeTaskConfigurationProbe.Profile(3,"5".repeat(64)));guard.report(TENANT,CLIENT,agent,OWNER,fence,restored.profile());
        assertFalse(locked(()->guard.lock(TENANT,CLIENT,List.of(restored))));
        var latest=new cn.jia.agent.security.PreparedRuntimeEpochFence.Epoch(agent,OWNER,binding,fence,new NativeTaskConfigurationProbe.Profile(4,epoch.profile().profileSha256()));
        guard.report(TENANT,CLIENT,agent,OWNER,fence,latest.profile());java.util.function.Supplier<Boolean> current=()->guard.lock(TENANT,CLIENT,List.of(latest));assertTrue(locked(current));
        assertFalse(locked(()->guard.lock(TENANT,CLIENT,List.of(new cn.jia.agent.security.PreparedRuntimeEpochFence.Epoch(agent,OWNER,binding+1,fence,latest.profile())))));
        try(var writer=independent.getDataSource().getConnection()){
            writer.setAutoCommit(false);try{try(var p=writer.prepareStatement("UPDATE agent_runtime SET runtime_session_generation=runtime_session_generation+1 WHERE agent_id=?")){p.setString(1,agent);assertEquals(1,p.executeUpdate());}
                assertFalse(locked(current),"NOWAIT: cannot wait on a session writer under the task lock");writer.commit();
            }finally{writer.rollback();}}
        assertFalse(locked(current));
        jdbc.update("UPDATE agent_runtime SET runtime_session_generation=? WHERE agent_id=?",fence.sessionGeneration(),agent);
        jdbc.update("UPDATE agent_runtime_v1_installation SET status='REVOKED' WHERE installation_id=?",fence.runtimeInstallationId());assertFalse(locked(current));
        jdbc.update("UPDATE agent_runtime_v1_installation SET status='ACTIVE' WHERE installation_id=?",fence.runtimeInstallationId());assertTrue(locked(current));
        guard.retire(TENANT,CLIENT,agent,OWNER,fence);guard.report(TENANT,CLIENT,agent,OWNER,fence,latest.profile());assertFalse(locked(current));
        var nextFence=new AgentManagedSessionLookup.SessionFence(fence.runtimeInstallationId(),fence.hostId(),"next-boot",fence.sessionGeneration()+1);
        jdbc.update("UPDATE agent_runtime SET runtime_instance_id=?,runtime_session_generation=? WHERE agent_id=?",nextFence.runtimeInstanceId(),nextFence.sessionGeneration(),agent);
        var next=new cn.jia.agent.security.PreparedRuntimeEpochFence.Epoch(agent,OWNER,binding,nextFence,epoch.profile());guard.report(TENANT,CLIENT,agent,OWNER,nextFence,epoch.profile());
        guard.retire(TENANT,CLIENT,agent,OWNER,fence);assertTrue(locked(()->guard.lock(TENANT,CLIENT,List.of(next))));
    }
    /** Opt-in export of this loopback fixture only. No headers map, authentication, real model, or price book. */
    private static final class QuoteWireWindow {
        private final Path directory;
        private final ObjectMapper json = new ObjectMapper();
        private final Map<String,List<Map<String,Object>>> attempts = new java.util.LinkedHashMap<>();
        private final long originNanos = System.nanoTime();
        private static final long CUTOFF_MS = 10000;
        private final String rosterSha;
        private final Map<String,String> refsByDigest=new java.util.HashMap<>();
        boolean sealed;
        static QuoteWireWindow open(String agent) throws Exception {
            String dir=System.getProperty("cyf.quoteWireFixture");
            return dir==null ? null : new QuoteWireWindow(Path.of(dir),TASK,agent,List.of("denied","same-intent","fresh-intent"),
                    k->"d77-"+k,digest("fixture:cfg:fixture:1024;no-dispatch-config"),digest("fixture:no-price-book"),Map.of());
        }
        static QuoteWireWindow paid(String task,String agent,String config,String reference,List<String> privateKeys) throws Exception {
            String dir=System.getProperty("cyf.quoteWireFixture");return dir==null?null:new QuoteWireWindow(Path.of(dir).resolveSibling("owned-priced-wire"),task,agent,
                    List.of("fixture-0","fixture-1"),k->"quote-http",config,reference,Map.of("fixture-0",privateKeys.get(0),"fixture-1",privateKeys.get(1)));
        }
        private static String digest(String value) throws Exception {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
        private QuoteWireWindow(Path dir,String task,String agent,List<String> keys,java.util.function.Function<String,String> requestId,String config,String book,Map<String,String> privateKeys) throws Exception {
            Files.createDirectories(dir.getParent()); directory=Files.createDirectory(dir);
            var requests=new java.util.ArrayList<Map<String,Object>>();
            for(String key:keys) {
                attempts.put(key,new java.util.ArrayList<>());String sha=digest(privateKeys.getOrDefault(key,key));assertNull(refsByDigest.put(sha,key));
                requests.add(Map.of("intentRef",key,"taskId",task,"taskVersion","0","agentIds",List.of(key.equals("denied")?"not-visible":agent),
                        "requestId",requestId.apply(key),"idempotencyKeySha256",sha));
            }
            var roster=json.writeValueAsString(Map.of("format","budget-roster-v1","configurationSha256",config,
                    "referenceSha256",book,"cutoffAtMs",CUTOFF_MS,"requests",requests));
            Files.writeString(directory.resolve("roster.json"),roster,java.nio.file.StandardOpenOption.CREATE_NEW);
            rosterSha=digest(roster);
            System.out.println("OWNED_QUOTE_WIRE_ROSTER_SHA256="+rosterSha);
        }
        long elapsed(){return TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-originNanos);}
        void received(HttpRequest req,String body,long sent,long seen,HttpResponse<String> res) throws Exception {
            if(sealed)return;
            assertEquals("127.0.0.1",req.uri().getHost());String key=req.headers().firstValue("Idempotency-Key").orElseThrow();
            var a=new java.util.LinkedHashMap<String,Object>();a.put("method",req.method());a.put("path",req.uri().getRawPath());a.put("sentAtMs",sent);a.put("receivedAtMs",seen);
            a.put("requestIdHeader",req.headers().firstValue("X-Request-Id").orElseThrow());a.put("idempotencyKeySha256",digest(key));a.put("requestBody",body);
            a.put("httpStatus",res.statusCode());a.put("responseIdHeader",res.headers().firstValue("X-Request-Id").orElse(null));a.put("responseBody",res.body());attempts.get(refsByDigest.get(digest(key))).add(a);
        }
        void seal() throws Exception {
            assertTrue(elapsed()<=CUTOFF_MS,"fixture did not complete its planned sends before cutoff");
            // The fixture is the only issuer; every send completed synchronously. No more sends until after cutoff.
            while(elapsed()<=CUTOFF_MS)Thread.sleep(Math.max(1,CUTOFF_MS+1-elapsed()));
            var intents=attempts.entrySet().stream().map(e->Map.of("intentRef",e.getKey(),"attempts",e.getValue())).toList();
            var log=json.writeValueAsString(Map.of("format","budget-transport-export-v1","rosterSha256",rosterSha,"completeThroughMs",elapsed(),"intents",intents));
            Files.writeString(directory.resolve("capture.json"),log,java.nio.file.StandardOpenOption.CREATE_NEW);sealed=true;
            System.out.println("OWNED_QUOTE_WIRE_CAPTURE_SHA256="+digest(log));
        }
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
        var files=spy(new PersonalWorkspaceServiceImpl(workspace.files(),storage,writes));
        var raced=new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(inv->{var snapshot=inv.callRealMethod();if(raced.compareAndSet(false,true))assertEquals(1,independent.update("UPDATE agent_personal_workspace_file SET display_name='修订后的名称',metadata_revision=2 WHERE file_id='file-0'"));return snapshot;}).when(files).selectedVersionsBatch(any(),any());
        var materials=new cn.jia.agent.service.recommendation.RecommendationMaterials(proxy(new PersonalWorkspaceTaskLinkServiceImpl(workspace.links()),PersonalWorkspaceTaskLinkService.class),files);
        independentQuoteController=cn.jia.agent.service.recommendation.QuoteHttpTestWiring.controller(roots,batches,authority.req(),assignment,authority.policies(),materials,billing);
        startHttp();int fixed=count("economy_task_quote"),batchCount=count("economy_task_quote_batch"),money=count("economy_transaction");long balance=wallet(jdbc);
        var json=new ObjectMapper();String token=httpToken();try(var client=HttpClient.newHttpClient()){
            var window=QuoteWireWindow.open(agent);
            class Post {tools.jackson.databind.JsonNode post(String id,String key,int status,String code)throws Exception{
                var body=json.writeValueAsString(Map.of("protocolVersion",1,"requestId","d77-"+key,"data",Map.of("expectedTaskVersion","0","agentIds",List.of(id))));
                var request=HttpRequest.newBuilder(URI.create(origin+"/agent/tasks/"+TASK+"/quotes")).header("Content-Type","application/json").header("Authorization","Bearer "+token).header("X-Request-Id","d77-"+key).header("Idempotency-Key",key).POST(HttpRequest.BodyPublishers.ofString(body)).build();
                long sent=window==null?0:window.elapsed();var response=client.send(request,HttpResponse.BodyHandlers.ofString());
                if(window!=null)window.received(request,body,sent,window.elapsed(),response);
                assertEquals(status,response.statusCode());var env=json.readTree(response.body());assertEquals(code,env.path("code").asText());return env.path("data");
            }}var http=new Post();String key="same-intent";
            http.post("not-visible","denied",404,"QUOTE_NOT_FOUND");assertFalse(raced.get());
            http.post(agent,key,409,"QUOTE_STALE");assertEquals(batchCount,count("economy_task_quote_batch"));
            var original=http.post(agent,key,200,"E0");var item=original.path("quotes").get(0);assertEquals(agent,item.path("agentId").asText());
            assertFixedQuote(item);assertTrue(raced.get());
            assertEquals(1,jdbc.update("UPDATE agent_personal_workspace_file SET state='TRASHED' WHERE file_id='file-0'"));
            assertEquals(original,http.post(agent,key,200,"E0"));http.post(agent,"fresh-intent",404,"QUOTE_NOT_FOUND");
            if(window!=null)window.seal();
            assertEquals(original,http.post(agent,key,200,"E0"));
            http.post("changed-target",key,409,"QUOTE_IDEMPOTENCY_CONFLICT");
            http.post(agent,"another-fresh-intent",404,"QUOTE_NOT_FOUND");
        }
        assertEquals(batchCount+1,count("economy_task_quote_batch"));assertEquals(fixed+1,count("economy_task_quote"));assertEquals(money,count("economy_transaction"));assertEquals(balance,wallet(jdbc));verifyNoInteractions(storage,writes);
    }
    // Outside measured requests. Count original UTF-8 LONGTEXT bytes, not JSON_EXTRACT's
    // reserialized representation or InnoDB allocation. Never emit content or private digests.
    private void profileFrozenPayload(String task,String batch,int quotes,int expectedGroups) throws Exception {
        var json=new ObjectMapper();var digest=java.security.MessageDigest.getInstance("SHA-256");
        var bindings=new java.util.HashSet<String>();var lengths=new java.util.HashMap<String,Integer>();long[] sizes={0,0};
        jdbc.query("""
            SELECT binding_sha256,CAST(snapshot_json AS BINARY) FROM economy_task_quote_resolved
            WHERE JSON_UNQUOTE(JSON_EXTRACT(snapshot_json,'$.schema'))='MEASURED_TASK_QUOTE_V1' AND tenant_id=? AND client_id=? AND owner_jiacn=? AND task_id=?
              AND BINARY JSON_UNQUOTE(JSON_EXTRACT(snapshot_json,'$.binding.recommendationId'))=BINARY ?
            """,(org.springframework.jdbc.core.RowCallbackHandler) rs->{
            assertTrue(bindings.add(rs.getString(1)));byte[] raw=rs.getBytes(2);sizes[0]+=raw.length;
            try(var p=json.createParser(raw)){
                assertEquals(tools.jackson.core.JsonToken.START_OBJECT,p.nextToken());boolean found=false;
                while(p.nextToken()!=tools.jackson.core.JsonToken.END_OBJECT){
                    boolean prep="preparation".equals(p.currentName());var token=p.nextToken();int start=Math.toIntExact(p.currentTokenLocation().getByteOffset());
                    p.skipChildren();p.finishToken();if(!prep)continue;
                    assertFalse(found);found=true;assertEquals(tools.jackson.core.JsonToken.START_OBJECT,token);
                    int end=Math.toIntExact(p.currentLocation().getByteOffset());assertEquals((byte)'{',raw[start]);assertEquals((byte)'}',raw[end-1]);
                    int size=end-start;digest.update(raw,start,size);var id=java.util.HexFormat.of().formatHex(digest.digest());
                    Integer old=lengths.putIfAbsent(id,size);if(old!=null)assertEquals(size,old.intValue());sizes[1]+=size;
                }assertTrue(found);
            }
        },TENANT,CLIENT,OWNER,task,batch);
        assertEquals(quotes,bindings.size());assertEquals(expectedGroups,lengths.size());assertTrue(sizes[0]>sizes[1]);
        long unique=lengths.values().stream().mapToLong(Integer::longValue).sum();
        var storage=jdbc.queryForMap("SELECT COUNT(*) groupsStored, SUM(OCTET_LENGTH(preparation_json)) bodyBytes FROM economy_task_quote_preparation WHERE tenant_id=? AND client_id=? AND owner_jiacn=? AND task_id=? AND recommendation_id=?",TENANT,CLIENT,OWNER,task,batch);
        assertEquals(expectedGroups,((Number)storage.get("groupsStored")).intValue());assertEquals(unique,((Number)storage.get("bodyBytes")).longValue());
        long envelope=jdbc.queryForObject("SELECT SUM(OCTET_LENGTH(snapshot_json)) FROM economy_task_quote WHERE tenant_id=? AND client_id=? AND owner_jiacn=? AND task_id=? AND snapshot_json->>'$.binding.recommendationId'=? AND JSON_TYPE(JSON_EXTRACT(snapshot_json,'$.preparation'))='STRING'",Long.class,TENANT,CLIENT,OWNER,task,batch);
        assertTrue(envelope>0 && envelope+unique<sizes[0]);System.out.printf("QUOTE_RETAINED groups=%d envelopesBytes=%d bodiesBytes=%d retainedPayloadBytes=%d%n",expectedGroups,envelope,unique,envelope+unique);
        System.out.printf("QUOTE_FROZEN_PAYLOAD rows=%d groups=%d logicalBytes=%d preparationBytes=%d distinctPreparationBytes=%d repeatedPreparationBytes=%d%n",quotes,lengths.size(),sizes[0],sizes[1],unique,sizes[1]-unique);
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
            cn.jia.economy.task.TaskBudgetEstimator.Terms terms,java.util.concurrent.atomic.AtomicInteger encodes,QuoteProfile profile,long expectedWen)throws Exception{
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
                    if(j+1==ids.size()){assertFixedQuote(item);}
                    else{assertEquals("AVAILABLE",item.path("state").asText());assertEquals(Long.toString(expectedWen),item.at("/amount/amountMinor").asText());String q=item.path("quoteId").asText();assertFalse(q.isBlank());assertTrue(all.add(q));}
                }
            }
        }
        var end=readQuoteWrites(task);long bytes=end.bytes()-writes.bytes();
        System.out.printf(java.util.Locale.ROOT,"BATCH_QUOTE_COST batches=30 candidates=108 prepareCalls=%d prepareMs=%.3f lockSelectedCalls=%d lockSelectedMs=%.3f storeCalls=%d storeMs=%.3f insertCalls=%d rows=%d insertMs=%.3f snapshotBytes=%d%n",profile.count("prepare"),profile.ms("prepare"),profile.count("lockSelected"),profile.ms("lockSelected"),profile.count("storeMeasuredBatch"),profile.ms("storeMeasuredBatch"),end.calls()-writes.calls(),end.rows()-writes.rows(),(end.dbPicos()-writes.dbPicos())/1e9,bytes);
        assertEquals(60,profile.count("prepare"));assertEquals(30,profile.count("lockSelected"));assertEquals(30,profile.count("storeMeasuredBatch"));assertTrue(end.calls()-writes.calls()<3210);assertEquals(3240,end.rows()-writes.rows());assertTrue(bytes>0);
        assertEquals(3210,all.size());assertEquals(1,encodes.get());assertEquals(batchCount+30,count("economy_task_quote_batch"));assertEquals(fixed+3240,count("economy_task_quote"));assertEquals(money,count("economy_transaction"));
        verify(source,times(30)).observe(subject,roster);verify(source,times(30)).current(subject,roster,proofs);
        profileFrozenPayload(task,first.path("quotes").get(0).path("recommendationId").asText(),107,1);
        samples.sort(Long::compare);System.out.printf(java.util.Locale.ROOT,"MEASURED_BATCH_QUOTE_HTTP candidates=108 available=107 hotN=30 p50ms=%.3f p95ms=%.3f maxms=%.3f replayMs=%.3f persistedQuotes=%d%n",samples.get(14)/1e6,samples.get(28)/1e6,samples.getLast()/1e6,replay/1e6,all.size());
    }

    private void concurrentDenseQuotes(String task,String token,List<String> ids,int lanes,java.util.concurrent.atomic.AtomicInteger encodes,QuoteProfile profile,long wen)throws Exception {
        var mapper=new ObjectMapper();int before=encodes.get(),fixed=count("economy_task_quote"),batchesBefore=count("economy_task_quote_batch"),money=count("economy_transaction");
        denseCalls.reset();var sqlBefore=recShapes().get(compactStatementDigest);
        profile.reset();var endpoint=URI.create(origin+"/agent/tasks/"+task+"/quotes");int payable=ids.size()==1?1:ids.size()-1;
        var quoteIds=new java.util.HashSet<String>();var batches=new java.util.HashSet<String>();var samples=new java.util.ArrayList<Long>();int errors=0;
        record Sample(int status,long nanos,tools.jackson.databind.JsonNode data){}
        try(var client=HttpClient.newHttpClient();var pool=java.util.concurrent.Executors.newFixedThreadPool(lanes)){
            class Wire {Sample send(int index)throws Exception {
                String key="dense-"+ids.size()+"-"+lanes+"-"+index;
                var body=mapper.writeValueAsString(Map.of("protocolVersion",1,"requestId",key,"data",Map.of("expectedTaskVersion","0","agentIds",ids)));
                var req=HttpRequest.newBuilder(endpoint).timeout(java.time.Duration.ofSeconds(45)).header("Content-Type","application/json").header("Authorization","Bearer "+token).header("Idempotency-Key",key).header("X-Request-Id",key).POST(HttpRequest.BodyPublishers.ofString(body)).build();
                long start=System.nanoTime();try {var res=client.send(req,HttpResponse.BodyHandlers.ofString());long elapsed=System.nanoTime()-start;
                    if(res.statusCode()!=200)return new Sample(res.statusCode(),elapsed,null);
                    var env=mapper.readTree(res.body());assertEquals(key,env.path("requestId").asText());assertEquals("E0",env.path("code").asText());return new Sample(200,elapsed,env.path("data"));
                }catch(java.io.IOException failure){return new Sample(0,System.nanoTime()-start,null);}
            }}
            var wire=new Wire();long connectsBefore=physicalConnects.get(),borrowsBefore=appBorrows.get();Sample first=null;var statuses=new java.util.TreeMap<Integer,Integer>();
            for(int base=0;base<30;base+=lanes){var gate=new java.util.concurrent.CyclicBarrier(lanes);var pending=new java.util.ArrayList<java.util.concurrent.Future<Sample>>();
                for(int i=0;i<lanes;i++){int index=base+i;pending.add(pool.submit(()->{gate.await(15,java.util.concurrent.TimeUnit.SECONDS);return wire.send(index);}));}
                for(var job:pending){var sample=job.get(50,java.util.concurrent.TimeUnit.SECONDS);samples.add(sample.nanos());statuses.merge(sample.status(),1,Integer::sum);if(sample.status()!=200){errors++;continue;}
                    if(first==null)first=sample;var rows=sample.data().path("quotes");assertEquals(ids.size(),rows.size());var batch=rows.get(0).path("recommendationId").asText();assertFalse(batch.isBlank());assertTrue(batches.add(batch));
                    for(int i=0;i<ids.size();i++){var row=rows.get(i);assertEquals(ids.get(i),row.path("agentId").asText());assertEquals(batch,row.path("recommendationId").asText());
                        if(i<payable){assertEquals("AVAILABLE",row.path("state").asText());assertEquals(Long.toString(wen),row.at("/amount/amountMinor").asText());var quote=row.path("quoteId").asText();assertFalse(quote.isBlank());assertTrue(quoteIds.add(quote));}
                        else{assertFixedQuote(row);}}
                }
            }
            long opened=physicalConnects.get()-connectsBefore,borrowed=appBorrows.get()-borrowsBefore;
            System.out.printf("DENSE_CONNECTIONS history=%s candidates=%d lanes=%d pooled=%s physicalOpens=%d logicalBorrows=%d%n",task,ids.size(),lanes,ownedHistoryPool!=null,opened,borrowed);
            assertTrue(borrowed>0);
            if(ownedHistoryPool!=null){assertTrue(ownedHistoryPool.getHikariPoolMXBean().getTotalConnections()<=15);assertTrue(opened<borrowed);}
            else assertEquals(borrowed,opened); // page mapper calls and actual connection borrows are different units
            samples.sort(Long::compare);System.out.printf(java.util.Locale.ROOT,"DENSE_QUOTE_CONCURRENT history=%s candidates=%d lanes=%d n=%d errors=%d p50ms=%.3f p95ms=%.3f maxms=%.3f encodes=%d prepareMs=%.3f selectedMs=%.3f storeMs=%.3f statuses=%s%n",task,ids.size(),lanes,samples.size(),errors,samples.get(14)/1e6,samples.get(28)/1e6,samples.getLast()/1e6,encodes.get()-before,profile.ms("prepare"),profile.ms("lockSelected"),profile.ms("storeMeasuredBatch"),statuses);
            var end=recShapes().get(compactStatementDigest);double server=(end.picos()-sqlBefore.picos())/1e9,fetch=denseCalls.ms("compactHistory"),page=denseCalls.ms("cyf.task.quote.history.projection.page"),estimate=denseCalls.ms("cyf.task.quote.estimate");
            long pages=denseCalls.count("compactHistory"),digestCalls=end.calls()-sqlBefore.calls(),sent=end.sent()-sqlBefore.sent();
            System.out.printf(java.util.Locale.ROOT,"DENSE_PHASE history=%s candidates=%d lanes=%d pages=%d digestCalls=%d sent=%d examined=%d serverMs=%.3f mapperMs=%.3f restoreAndValidateMs=%.3f estimateOutsidePageMs=%.3f estimateMs=%.3f%n",task,ids.size(),lanes,pages,digestCalls,sent,end.examined()-sqlBefore.examined(),server,fetch,page-fetch,estimate-page,estimate);
            assertEquals(17L*30,pages);assertTrue(page>=fetch && estimate>=page);assertTrue(digestCalls>0 && sent>0 && server>0);
            assertEquals(0,errors);assertNotNull(first);assertEquals(first.data(),wire.send(0).data());
        }
        assertEquals(30*(payable==1?1:2),profile.count("prepare"));assertEquals(30,profile.count("lockSelected"));assertEquals(30,profile.count("storeMeasuredBatch"));assertEquals(before,encodes.get());
        assertEquals(30*payable,quoteIds.size());assertEquals(fixed+30*ids.size(),count("economy_task_quote"));assertEquals(batchesBefore+30,count("economy_task_quote_batch"));assertEquals(money,count("economy_transaction"));
    }

    private SqlSessionTemplate recommendationSql;
    private cn.jia.agent.api.AssigneeRecommendationController recommendationController;
    private <T> T withMapper(T dao,Class<?> mapper){ReflectionTestUtils.setField(dao,"baseMapper",recommendationSql.getMapper(mapper));return dao;}
    private record Authority(AgentServiceImpl agents,AgentExternalServicePolicyService policies,AgentTaskRequirementSnapshotServiceImpl req,AgentRuntimeDao runtimes,AgentIdentityService identities) {}
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
        return new Authority(agents,policies,req,runtimes,identities);
    }
    @Test @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="cyf.capabilityEsLoad",matches="true")
    void signedRecommendationWithRealCandidateAuthorityAndIsolatedEs() throws Exception {
        var authority=quoteAndRecommendationAuthority();var agents=authority.agents();var req=authority.req();
        var accounts=mock(cn.jia.user.security.AccountSecurityService.class);
        when(accounts.findUniqueByExactJiacn(OWNER)).thenReturn(java.util.Optional.of(new cn.jia.user.security.AccountSecuritySnapshot(1,OWNER,cn.jia.user.security.AccountState.ACTIVE,1)));
        var gate=new cn.jia.agent.config.AgentTaskEventsGate(new cn.jia.agent.config.AgentTaskEventsProperties(true,List.of(new cn.jia.agent.config.AgentTaskEventsProperties.AllowedScope(TENANT,CLIENT))));
        var auth=new cn.jia.agent.security.AgentRuntimeAuthenticationService(authority.runtimes(),withMapper(new AgentRuntimeV1InstallationDaoImpl(),AgentRuntimeV1InstallationMapper.class),withMapper(new AgentIdentityRegistryDaoImpl(),AgentIdentityRegistryMapper.class),authority.identities(),accounts,gate,new cn.jia.core.security.FixedTenantAdmissionPolicy(TENANT,CLIENT));
        var installed=new cn.jia.agent.skill.InstalledPurposeHttpFixture(jdbc,manager,TENANT,CLIENT,OWNER,auth);
        recommendationSql.getConfiguration().addInterceptor(new cn.jia.agent.skill.InstalledPurposeHttpFixture.CompletedReads(sql->{
            if(!sql.contains("FROM agent_task_meta")||!sql.stripTrailing().endsWith("FOR SHARE"))return;
            if(org.springframework.web.context.request.RequestContextHolder.getRequestAttributes() instanceof org.springframework.web.context.request.ServletRequestAttributes attrs){
                String id=attrs.getRequest().getHeader("X-Request-Id");
                if(id!=null && id.startsWith("rec-"))sharedByRequest.merge(id,1,Integer::sum);
            }
        }));
        var skills=installed.skills;
        var workspace=quoteWorkspace();var storage=mock(PersonalWorkspaceStorage.class);var writes=mock(PersonalWorkspaceWriteService.class);
        var materials=new cn.jia.agent.service.recommendation.RecommendationMaterials(proxy(new PersonalWorkspaceTaskLinkServiceImpl(workspace.links()),PersonalWorkspaceTaskLinkService.class),new PersonalWorkspaceServiceImpl(workspace.files(),storage,writes));
        try(var es=new cn.jia.agent.service.recommendation.RecommendationHttpTestWiring(agents,skills,materials,roots)){
            recommendationController=es.controller;startHttp();var expected=new java.util.LinkedHashSet<String>();
            for(int n:List.of(1,108)){
                String task="rec-http-"+n;jdbc.update("INSERT INTO agent_task_meta(task_id,owner_jiacn,tenant_id,client_id) VALUES(?,?,?,?)",task,OWNER,TENANT,CLIENT);
                roots.executeWithLockedTaskRootInOwnerScope(TENANT,CLIENT,OWNER,task,r->req.captureOnCreate(SCOPE,task,"合同审查 "+n,"核对合同条款"));
                if(n==1)assertSharedRecommendationRoot(task);
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
                    es.index(TENANT,CLIENT,OWNER,id,abilities);if(i%8==1){installed.installed(id);if(i==1)installed.retained(id);es.indexSkill(TENANT,CLIENT,id,skills);}expected.add(id);
                }
                if(n==108){for(var cursor:List.of("",expected.stream().skip(99).findFirst().orElseThrow())){explainRecPage(cursor);}}
                es.refresh();assertEquals(n==1?2L:228L,es.documentCount());measureRecommendationHttp(task,expected,es,installed,req);
                if(n==1){String agent=expected.iterator().next();
                    assertEquals(1,jdbc.update("UPDATE agent_runtime SET abilities='[]' WHERE tenant_id=? AND client_id=? AND agent_id=?",TENANT,CLIENT,agent));
                    String key="rec-withdrawn";var request=HttpRequest.newBuilder(URI.create(origin+"/agent/tasks/"+task+"/recommend")).header("Authorization","Bearer "+httpToken()).header("X-Request-Id",key).header("Content-Type","application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"protocolVersion\":1,\"requestId\":\"rec-withdrawn\",\"data\":{\"expectedTaskVersion\":\"0\"}}" )).build();
                    try(var client=HttpClient.newHttpClient()){var res=client.send(request,HttpResponse.BodyHandlers.ofString());assertEquals(200,res.statusCode());var rows=new ObjectMapper().readTree(res.body()).at("/data/recommendations");assertEquals(1,rows.size());var row=rows.get(0);assertEquals(agent,row.at("/agent/agentId").asText());assertTrue(row.path("evidence").isArray());assertEquals(0,row.path("evidence").size());assertFalse(row.has("recommendationLevel"));}
                    assertEquals(2L,es.documentCount());assertEquals(1,jdbc.update("UPDATE agent_runtime SET abilities=? WHERE tenant_id=? AND client_id=? AND agent_id=?",new ObjectMapper().writeValueAsString(List.of("合同审查 0","合同条款核对 0")),TENANT,CLIENT,agent));
                }
                org.mockito.Mockito.verifyNoInteractions(storage,writes);
                if(n==108){int before=es.encodes.get();String agent=expected.iterator().next();
                    String onlySkill=expected.stream().skip(1).findFirst().orElseThrow();assertInstalledHttp(task,onlySkill,true);
                    assertEquals(1,jdbc.update("UPDATE agent_runtime SET runtime_session_generation=2 WHERE tenant_id=? AND client_id=? AND agent_id=?",TENANT,CLIENT,onlySkill));assertInstalledHttp(task,onlySkill,false);
                    installed.registerSession(onlySkill,2L);assertInstalledHttp(task,onlySkill,true);
                    installed.withdraw();assertInstalledHttp(task,onlySkill,false);assertEquals(228L,es.documentCount());
                    assertEquals(1,jdbc.update("DELETE FROM agent_runtime WHERE tenant_id=? AND client_id=? AND owner_jiacn=? AND agent_id=?",TENANT,CLIENT,OWNER,agent));
                    checkRecError(task,503,"RECOMMENDATION_UNAVAILABLE");
                    jdbc.update("INSERT INTO agent_task_meta(task_id,owner_jiacn,tenant_id,client_id) VALUES('foreign-rec','different-owner',?,?)",TENANT,CLIENT);
                    checkRecError("foreign-rec",404,"RECOMMENDATION_NOT_FOUND");assertEquals(before,es.encodes.get());
                    String other=expected.stream().skip(9).findFirst().orElseThrow();var ids=java.util.Set.of(onlySkill,other);
                    assertEquals(ids,auth.currentRegisteredProofs(TENANT,CLIENT,ids).keySet());
                    // Same fence and even the same socket ID after disconnect are not the same observed binding.
                    var escaped = new java.util.concurrent.atomic.AtomicReference<cn.jia.agent.security.AgentRuntimeAuthenticationService.RecommendationChannel>();
                    String socket="fixture-session-2"+onlySkill;
                    auth.observeRecommendationChannels(TENANT,CLIENT,java.util.Set.of(onlySkill),(proof,channel)->{
                        escaped.set(channel);
                        assertTrue(channel.matches(socket,TENANT,CLIENT,OWNER,onlySkill,"instance"));
                        assertFalse(channel.matches(socket,TENANT,CLIENT,OWNER,other,"instance"));
                        assertFalse(channel.matches(socket,TENANT,CLIENT,"foreign",onlySkill,"instance"));
                        assertFalse(java.util.concurrent.CompletableFuture.supplyAsync(()->channel.matches(socket,TENANT,CLIENT,OWNER,onlySkill,"instance")).join());
                    });
                    assertFalse(escaped.get().matches(socket,TENANT,CLIENT,OWNER,onlySkill,"instance"));
                    auth.observeRecommendationChannels(TENANT,CLIENT,java.util.Set.of(onlySkill),(proof,channel)->{
                        var original=auth.bind(socket,proof,()->true);
                        assertTrue(channel.issued(original));
                        auth.disconnect(socket);var replacement=auth.bind(socket,proof,()->true);
                        assertEquals(original,replacement);assertFalse(channel.issued(replacement));
                        assertFalse(channel.matches(socket,TENANT,CLIENT,OWNER,onlySkill,"instance"));
                        auth.observeRecommendationChannels(TENANT,CLIENT,java.util.Set.of(onlySkill),(p2,next)->{
                            assertFalse(next.issued(original));assertTrue(next.issued(replacement));
                            assertTrue(next.matches(socket,TENANT,CLIENT,OWNER,onlySkill,"instance"));
                        });
                    });
                    assertEquals(1,jdbc.update("UPDATE agent_persona_binding SET status=0 WHERE tenant_id=? AND client_id=? AND agent_id=?",TENANT,CLIENT,onlySkill));
                    assertEquals(java.util.Set.of(other),auth.currentRegisteredProofs(TENANT,CLIENT,ids).keySet());
                }
            }
        }
    }
    /** Called only inside the isolated loopback HTTP/MySQL/ES fixture. The initial query vector uses
     * the fixture encoder; cold refers to the API vector cache, not a real provider or cold OS. */
    private void assertSharedRecommendationRoot(String task)throws Exception {
        var arrived=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
            var reader=pool.submit(()->roots.observe(TENANT,CLIENT,OWNER,task,root->{arrived.countDown();
                try{assertTrue(release.await(8,java.util.concurrent.TimeUnit.SECONDS));}catch(InterruptedException e){throw new AssertionError(e);}
                return root.getTaskId();}));
            try {assertTrue(arrived.await(5,java.util.concurrent.TimeUnit.SECONDS));
                assertEquals(task,pool.submit(()->roots.observe(TENANT,CLIENT,OWNER,task,r->r.getTaskId())).get(4,java.util.concurrent.TimeUnit.SECONDS));
                try(var conn=jdbc.getDataSource().getConnection();var ps=conn.prepareStatement("SELECT task_id FROM agent_task_meta WHERE tenant_id=? AND client_id=? AND owner_jiacn=? AND task_id=? FOR UPDATE NOWAIT")){
                    conn.setAutoCommit(false);ps.setString(1,TENANT);ps.setString(2,CLIENT);ps.setString(3,OWNER);ps.setString(4,task);
                    assertEquals(3572,assertThrows(java.sql.SQLException.class,ps::executeQuery).getErrorCode());conn.rollback();}
            }finally{release.countDown();}
            assertEquals(task,reader.get(5,java.util.concurrent.TimeUnit.SECONDS));
        }
        assertEquals(task,roots.executeWithLockedTaskRootInOwnerScope(TENANT,CLIENT,OWNER,task,r->r.getTaskId()));
    }
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
            for(var row:rows)if(agent.equals(row.at("/agent/agentId").asText())){matched++;var evidence=row.path("evidence");assertTrue(evidence.isArray());assertEquals(visible?1:0,evidence.size());if(visible){assertEquals("INSTALLED_SKILL",evidence.get(0).path("kind").asText());assertEquals("合同审查",evidence.get(0).path("label").asText());}else assertFalse(row.has("recommendationLevel"));}assertEquals(1,matched);}
    }
    // Attribute physical single-table SELECTs by normalized table name, never log SQL text or parameters.
    private java.util.Map<String,Long> proofSelects(){
        var counts=new java.util.TreeMap<String,Long>();
        for(String table:List.of("agent_runtime_v1_installation","agent_runtime","agent_identity_registry","agent_persona_binding"))
            counts.put(table,admin.queryForObject("SELECT COALESCE(SUM(COUNT_STAR),0) FROM performance_schema.events_statements_summary_by_digest WHERE SCHEMA_NAME=? AND DIGEST_TEXT LIKE 'SELECT %' AND LOCATE(?,DIGEST_TEXT)>0",Long.class,database," FROM `"+table+"` WHERE "));
        counts.put("installationPoint",admin.queryForObject("SELECT COALESCE(SUM(COUNT_STAR),0) FROM performance_schema.events_statements_summary_by_digest WHERE SCHEMA_NAME=? AND DIGEST_TEXT LIKE 'SELECT * FROM `agent_runtime_v1_installation` WHERE %'",Long.class,database));
        return counts;
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
    private final java.util.concurrent.ConcurrentMap<String,Integer> sharedByRequest=new java.util.concurrent.ConcurrentHashMap<>();
    private long sharedTaskRootSelects(){
        return admin.queryForObject("SELECT COALESCE(SUM(COUNT_STAR),0) FROM performance_schema.events_statements_summary_by_digest WHERE SCHEMA_NAME=? AND DIGEST_TEXT LIKE 'SELECT %`agent_task_meta`%FOR SHARE%'",Long.class,database);
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
    private void measureRecommendationHttp(String task, java.util.Set<String> expected, cn.jia.agent.service.recommendation.RecommendationHttpTestWiring es,cn.jia.agent.skill.InstalledPurposeHttpFixture installed,AgentTaskRequirementSnapshotService reqs) throws Exception {
        var encodes=es.encodes;int before=encodes.get();String token=httpToken();var mapper=new ObjectMapper();
        try(var client=HttpClient.newHttpClient()){
            record Observation(long nanos,boolean degraded,boolean invalid){}
            class Probe { Observation run(String id)throws Exception{return run(task,id);}
                Observation run(String requested,String id)throws Exception{
                var req=HttpRequest.newBuilder(URI.create(origin+"/agent/tasks/"+requested+"/recommend")).header("Authorization","Bearer "+token)
                    .header("Content-Type","application/json").header("X-Request-Id",id).timeout(java.time.Duration.ofSeconds(20))
                    .POST(HttpRequest.BodyPublishers.ofString("{\"protocolVersion\":1,\"requestId\":\""+id+"\",\"data\":{\"expectedTaskVersion\":\"0\"}}" )).build();
                long t=System.nanoTime();var res=client.send(req,HttpResponse.BodyHandlers.ofString());long elapsed=System.nanoTime()-t;
                var env=mapper.readTree(res.body());var data=env.path("data");var rows=data.path("recommendations");var seen=new java.util.HashSet<String>();
                boolean bad=res.statusCode()!=200||!"E0".equals(env.path("code").asText())||!id.equals(env.path("requestId").asText())||!requested.equals(data.path("taskId").asText())||!"0".equals(data.path("taskVersion").asText())||!data.path("candidateSetComplete").asBoolean()||!rows.isArray();
                for(var row:rows)if(!seen.add(row.at("/agent/agentId").asText())||row.path("evidence").size()==0||row.has("recommendationLevel"))bad=true;
                if(id.startsWith("rec-slow-fail-"))bad|=!"DEGRADED".equals(data.path("state").asText())||!"LEXICAL_ONLY".equals(data.path("retrievalMode").asText())||!data.path("degradedReasons").equals(mapper.valueToTree(List.of("QUERY_VECTOR_UNAVAILABLE")));
                return new Observation(elapsed,!"HYBRID".equals(data.path("retrievalMode").asText()),bad||!seen.equals(expected));
            }}
            var probe=new Probe();
            if(Boolean.getBoolean("cyf.capabilityPitWindow")){
                long baseline=es.openContexts(),peak=baseline;assertEquals(0,baseline);var latencies=new java.util.ArrayList<Long>();
                es.slowQuery=true;es.failQuery=true;
                try(var pool=java.util.concurrent.Executors.newFixedThreadPool(10)){
                    var barrier=new java.util.concurrent.CyclicBarrier(10);var pending=new java.util.ArrayList<java.util.concurrent.Future<Observation>>();
                    for(int i=0;i<10;i++){String key="rec-slow-fail-"+expected.size()+"-"+i;pending.add(pool.submit(()->{barrier.await(10,java.util.concurrent.TimeUnit.SECONDS);return probe.run(key);}));}
                    long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(25);
                    while(pending.stream().anyMatch(f->!f.isDone())){assertTrue(System.nanoTime()<deadline);peak=Math.max(peak,es.openContexts());Thread.sleep(100);}
                    for(var future:pending){var sample=future.get();assertFalse(sample.invalid());assertTrue(sample.degraded());latencies.add(sample.nanos());}
                }finally{es.failQuery=false;}
                long after=es.openContexts();assertEquals(0,after);assertTrue(peak>baseline);assertEquals(before+1,encodes.get(),"joined one slow producer");before=encodes.get();
                latencies.sort(Long::compare);System.out.printf(java.util.Locale.ROOT,"RECOMMEND_SLOW_FAILURE candidates=%d requests=%d delayMs=4000 encodes=1 baseline=%d peak=%d after=%d p50ms=%.3f p95ms=%.3f%n",expected.size(),latencies.size(),baseline,peak,after,latencies.get(4)/1e6,latencies.get(9)/1e6);
            }
            var cold=probe.run("rec-cold-"+expected.size());assertFalse(cold.invalid());assertFalse(cold.degraded());assertEquals(before+1,encodes.get());es.slowQuery=false;
            for(int concurrency:List.of(1,5,10)){
                es.resetStages();
                long execBefore=installed.inventoryExecutions();long sharedBefore=sharedTaskRootSelects();var proofBefore=proofSelects();long inventoryBefore=paidInventorySelects();var sqlBefore=readRecSql();var shapes=concurrency==1?recShapes():null;
                int invalid=0,degraded=0;var times=new java.util.ArrayList<Long>();
                try(var pool=java.util.concurrent.Executors.newFixedThreadPool(concurrency)){
                    for(int round=0;round<30/concurrency;round++){
                        var gate=new java.util.concurrent.CyclicBarrier(concurrency);var pending=new java.util.ArrayList<java.util.concurrent.Future<Observation>>();
                        for(int i=0;i<concurrency;i++){String key="rec-"+expected.size()+"-"+concurrency+"-"+round+"-"+i;
                            pending.add(pool.submit(()->{gate.await(15,java.util.concurrent.TimeUnit.SECONDS);return probe.run(key);}));}
                        for(var f:pending){var s=f.get(25,java.util.concurrent.TimeUnit.SECONDS);times.add(s.nanos());if(s.invalid())invalid++;if(s.degraded())degraded++;}
                    }
                }
                var physical=proofSelects();physical.replaceAll((k,v)->v-proofBefore.get(k));
                System.out.printf("RECOMMEND_PROOF_SQL candidates=%d concurrency=%d n=%d counts=%s%n",expected.size(),concurrency,times.size(),physical);
                if(expected.size()==108){assertEquals(2L*times.size(),physical.get("agent_runtime_v1_installation")-physical.get("installationPoint"),"one installation batch for each observation");assertEquals(0L,physical.get("installationPoint"),"each matching Chat session uses the exact native batch observation, independently on both reads");}
                long inventory=paidInventorySelects()-inventoryBefore;int installs=expected.size()==1?0:14;
                System.out.printf("RECOMMEND_INVENTORY installations=%d concurrency=%d n=%d selects=%d%n",installs,concurrency,times.size(),inventory);
                long executed=installed.inventoryExecutions()-execBefore;
                System.out.printf("RECOMMEND_INVENTORY_CROSSCHECK concurrency=%d executed=%d digest=%d invalid=%d degraded=%d%n",concurrency,executed,inventory,invalid,degraded);
                assertEquals(installs==0?0L:2L*times.size(),executed,"completed physical inventory reads");
                assertEquals(installs==0?0L:2L*times.size(),inventory,"two independent observations, not two queries per installation");
                times.sort(Long::compare);System.out.printf(java.util.Locale.ROOT,"RECOMMEND_HTTP candidates=%d concurrency=%d coldFixtureMs=%.3f hotN=%d p50ms=%.3f p95ms=%.3f maxms=%.3f invalid=%d degraded=%d%n",expected.size(),concurrency,cold.nanos()/1e6,times.size(),times.get(14)/1e6,times.get(28)/1e6,times.getLast()/1e6,invalid,degraded);
                long shared=sharedTaskRootSelects()-sharedBefore;
                int exact=0;
                for(int round=0;round<30/concurrency;round++)for(int i=0;i<concurrency;i++){
                    String id="rec-"+expected.size()+"-"+concurrency+"-"+round+"-"+i;
                    int calls=sharedByRequest.getOrDefault(id,0);assertEquals(2,calls,"completed shared reads for "+id);exact+=calls;
                }
                System.out.printf("RECOMMEND_ROOT_CROSSCHECK candidates=%d concurrency=%d executed=%d digest=%d%n",expected.size(),concurrency,exact,shared);
                assertEquals(2L*times.size(),exact,"independent completed physical reads per request");
                assertTrue(shared>0,"missing database digest observations");
                if(concurrency==1)assertEquals(exact,shared,"serial database digest cross-check");
                else if(exact!=shared)System.out.printf("RECOMMEND_ROOT_DIGEST_DIVERGENCE candidates=%d concurrency=%d completed=%d digest=%d%n",expected.size(),concurrency,exact,shared);
                var delta=readRecSql().minus(sqlBefore);if(shapes!=null)reportShapes(expected.size(),shapes);
                System.out.printf(java.util.Locale.ROOT,"RECOMMEND_MYSQL candidates=%d concurrency=%d n=%d selects=%d rowsExamined=%d rowsSent=%d sumSelectMs=%.3f matchingRootLocks=%d rowWaits=%d sumRowWaitMs=%d%n",expected.size(),concurrency,times.size(),delta.statements(),delta.examined(),delta.sent(),delta.statementPicos()/1e9,delta.roots(),delta.waits(),delta.waitMillis());
                assertEquals(0,delta.roots(),"pure recommendation reads must not take exclusive task locks");
                assertTrue(delta.statements()>0 && delta.examined()>0 && delta.sent()>0 && delta.statementPicos()>0,"missing statement observations");assertTrue(delta.waits()>=0 && delta.waitMillis()>=0);
                assertEquals(0,es.reportStages("hot",expected.size(),concurrency));assertEquals(0,invalid);assertEquals(0,degraded);assertEquals(before+1,encodes.get());
            }
            // Distinct authorized tasks AND texts per cold request; no test-only cache reset, no shared producer.
            for(int lanes:List.of(1,5,10)){
                var tasks=new java.util.ArrayList<String>();
                for(int i=0;i<30;i++){
                    String key=task+"-cold-"+lanes+"-"+i;
                    jdbc.update("INSERT INTO agent_task_meta(task_id,owner_jiacn,tenant_id,client_id) VALUES(?,?,?,?)",key,OWNER,TENANT,CLIENT);
                    roots.executeWithLockedTaskRootInOwnerScope(TENANT,CLIENT,OWNER,key,r->reqs.captureOnCreate(SCOPE,key,"合同审查 "+key,"核对合同条款"));
                    jdbc.update("INSERT INTO agent_personal_workspace_task_file_link(relation_id,owner_jiacn,task_id,file_id,file_version,link_role,link_state,relation_revision,created_at,tenant_id,client_id) VALUES(?,?,?,?,1,'INPUT','ACTIVE',1,1,?,?)",key,OWNER,key,"file-"+expected.size(),TENANT,CLIENT);
                    tasks.add(key);
                }
                es.resetStages();int start=encodes.get(),invalid=0,degraded=0;var times=new java.util.ArrayList<Long>();
                try(var pool=java.util.concurrent.Executors.newFixedThreadPool(lanes)){
                    for(int base=0;base<tasks.size();base+=lanes){var gate=new java.util.concurrent.CyclicBarrier(lanes);var pending=new java.util.ArrayList<java.util.concurrent.Future<Observation>>();
                        for(String key:tasks.subList(base,base+lanes))pending.add(pool.submit(()->{gate.await(15,java.util.concurrent.TimeUnit.SECONDS);return probe.run(key,"rec-"+key);}));
                        for(var f:pending){var x=f.get(25,java.util.concurrent.TimeUnit.SECONDS);times.add(x.nanos());if(x.invalid())invalid++;if(x.degraded())degraded++;}
                    }
                }
                times.sort(Long::compare);int calls=encodes.get()-start;
                System.out.printf(java.util.Locale.ROOT,"RECOMMEND_DISTINCT_COLD candidates=%d concurrency=%d n=%d encodes=%d degraded=%d invalid=%d p50ms=%.3f p95ms=%.3f maxms=%.3f%n",expected.size(),lanes,times.size(),calls,degraded,invalid,times.get(14)/1e6,times.get(28)/1e6,times.getLast()/1e6);
                assertEquals(calls,es.reportStages("cold",expected.size(),lanes));assertEquals(30,calls+degraded);assertEquals(0,invalid);
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
            if(directController!=null)factory.registerSingleton("directAssignmentController",directController);
            if(recommendationController!=null)factory.registerSingleton("recommendationController",recommendationController);
            if(initialSelectionController!=null)factory.registerSingleton("initialSelectionController",initialSelectionController);
            if(independentQuoteController!=null)factory.registerSingleton("independentQuoteController",independentQuoteController);
            factory.registerSingleton("walletController", new cn.jia.economy.api.EconomyWalletController(walletGate, walletReads));
            factory.registerSingleton("assignmentController", new AgentTaskDeliberationOperationController(operationReads, assignment));
            factory.registerSingleton("cancellationController", new AgentTaskCancellationController(cancellation));
            factory.registerSingleton("decisionController", new AgentTaskDeliveryReviewController(reviews,
                    new HumanSenderIdentityResolver(mock(cn.jia.user.service.UserService.class)),
                    new TenantScopeResolver(), reviewSources));
        });
        httpContext.refresh();
        installDispatcher(context,httpContext);
        httpServer.start(); origin = "http://127.0.0.1:" + httpServer.getConnector().getLocalPort();
    }
    private static void installDispatcher(org.apache.catalina.Context context, AnnotationConfigWebApplicationContext httpContext) {
        Tomcat.addServlet(context, "dispatcher", new DispatcherServlet(httpContext)).setLoadOnStartup(1);
        context.addServletMappingDecoded("/", "dispatcher");
        addHttpFilter(context, "requestId", new RequestIdFilter());
        addHttpFilter(context, "contextLifecycle", new cn.jia.core.filter.EsContextFilter());
        addHttpFilter(context, "security", new DelegatingFilterProxy("springSecurityFilterChain", httpContext));
        addHttpFilter(context, "authenticatedContext", new cn.jia.oauth.filter.EsSecurityContextFilter(
                new cn.jia.core.security.FixedTenantAdmissionPolicy(TENANT, CLIENT)));
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
