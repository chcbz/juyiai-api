package cn.jia.economy.service.impl;

import cn.jia.economy.config.EconomyMySqlTestFixture;
import cn.jia.economy.config.EconomyPreviewGate;
import cn.jia.economy.config.EconomyPreviewProperties;
import cn.jia.economy.config.EconomySchemaInitializer;
import cn.jia.economy.config.TaskPricingTestFixture;
import cn.jia.economy.pricing.TaskPricingService;
import cn.jia.economy.pricing.TaskWorkload;
import cn.jia.economy.pricing.GroupMeanPricing;
import cn.jia.economy.pricing.MeasuredTaskRepricing;
import cn.jia.economy.pricing.TaskEstimateFeatures;
import cn.jia.economy.task.TaskBenchmarkAdmission;
import cn.jia.economy.usage.BenchmarkSampleEvidence;
import cn.jia.economy.usage.ConfirmedSampleEvidence;
import cn.jia.economy.exception.EconomyPostingException;
import cn.jia.economy.mapper.EconomyLedgerMapper;
import cn.jia.economy.mapper.EconomyTaskBillingMapper;
import cn.jia.economy.pricing.ModelTokenPrice;
import cn.jia.economy.pricing.TaskQuoteCalculator;
import cn.jia.economy.service.EconomyScope;
import cn.jia.economy.task.GroupBudgetQuotes;
import cn.jia.economy.task.TaskQuoteBatches;
import cn.jia.economy.task.TaskBillingException;
import cn.jia.economy.task.TaskBillingService;
import cn.jia.economy.task.ScopedTaskQuoteSnapshot;
import cn.jia.economy.task.TaskBillingService.Binding;
import cn.jia.economy.task.TaskBillingService.Charge;
import cn.jia.economy.task.TaskBillingService.Quote;
import cn.jia.economy.task.TaskBillingService.State;
import cn.jia.economy.task.TaskBillingService.Task;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static cn.jia.economy.task.TaskBillingException.Reason.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real SQL, Spring mandatory transactions, production posting and task-root lock ownership. */
@EnabledIfEnvironmentVariable(named = EconomyMySqlTestFixture.URL_ENV, matches = ".+")
class TaskBillingServiceMySqlTest {
    private static final EconomyScope SCOPE = new EconomyScope("Tenant-A", "Client-A");
    private static final Task TASK = new Task(SCOPE, "owner-1", "task-1");
    private static final Binding BINDING = new Binding(1, "requirement-sha", "input-sha", "agent-1", "recommendation-1");
    private final EconomyMySqlTestFixture fixture = new EconomyMySqlTestFixture();
    private TaskBillingService billing;
    private TaskBenchmarkAdmission scopedAdmissions;
    private cn.jia.economy.mapper.GroupBaselineMaintenanceMapper maintenance;
    private cn.jia.economy.mapper.GroupBaselineMapper baselines;
    private cn.jia.economy.mapper.ConfirmedSampleMapper originals;
    private TransactionTemplate tx;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() throws Exception {
        fixture.start();
        var db = fixture.newDatabase("taskbilling");
        jdbc = db.jdbc();
        new EconomySchemaInitializer(jdbc).afterPropertiesSet();
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("db/economy-task-quote-preparation-v1.sql"),new org.springframework.core.io.ClassPathResource("db/economy-task-quote-features-v2.sql")).execute(jdbc.getDataSource());
        // Models the caller's existing task row, not a product billing lock table.
        jdbc.execute("CREATE TABLE test_task_root (id VARCHAR(100) PRIMARY KEY, state VARCHAR(24) NOT NULL) ENGINE=InnoDB");
        jdbc.update("INSERT INTO test_task_root VALUES ('task-1','OPEN'),('task-2','OPEN')");
        jdbc.update("""
                INSERT INTO economy_account(account_id,owner_type,owner_id,purpose,currency,balance_wen,
                    allow_negative,status,version,tenant_id,client_id,create_time,update_time)
                VALUES('wallet','USER','payer-1','AVAILABLE','WEN',100,0,'ACTIVE',0,'Tenant-A','Client-A',1,1)
                """);
        var config = new org.apache.ibatis.session.Configuration();
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(cn.jia.economy.mapper.ConfirmedSampleMapper.class);
        config.addMapper(EconomyLedgerMapper.class);
        config.addMapper(EconomyTaskBillingMapper.class);
        config.addMapper(cn.jia.economy.mapper.TaskBenchmarkMapper.class);
        config.addMapper(cn.jia.economy.mapper.GroupBaselineMaintenanceMapper.class);
        config.addMapper(cn.jia.economy.mapper.GroupBaselineMapper.class);
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(db.dataSource());
        factory.setConfiguration(config);
        var session = new SqlSessionTemplate(factory.getObject());
        originals=session.getMapper(cn.jia.economy.mapper.ConfirmedSampleMapper.class);
        maintenance=session.getMapper(cn.jia.economy.mapper.GroupBaselineMaintenanceMapper.class);
        baselines=session.getMapper(cn.jia.economy.mapper.GroupBaselineMapper.class);
        var manager = new DataSourceTransactionManager(db.dataSource());
        tx = new TransactionTemplate(manager);
        scopedAdmissions=transactional(new TaskBenchmarkAdmissionService(session.getMapper(cn.jia.economy.mapper.TaskBenchmarkMapper.class),new ObjectMapper()));
        var ledger = session.getMapper(EconomyLedgerMapper.class);
        var gate = new EconomyPreviewGate(new EconomyPreviewProperties(true,
                List.of(new EconomyPreviewProperties.AllowedScope(SCOPE.tenantId(), SCOPE.clientId()))));
        var target = new TaskBillingServiceImpl(session.getMapper(EconomyTaskBillingMapper.class), ledger,
                new EconomyPostingServiceImpl(ledger, manager, gate), new ObjectMapper(),
                TaskPricingTestFixture.install(jdbc, SCOPE,
                        List.of("0", "0.004", "0.1", "0.3", "0.4", "0.5", "0.500001", "0.8", "999999999999999999999").stream().map(TaskBillingServiceMySqlTest::price).toList()));
        var proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        billing = (TaskBillingService) proxy.getProxy();
    }

    @AfterEach
    void close() { fixture.close(); }

    @Test
    void requiresCallerTransactionEvenForZeroPrice() {
        assertThrows(IllegalTransactionStateException.class, () -> billing.capture(TASK));
        assertThrows(IllegalTransactionStateException.class, () -> billing.quote(TASK, BINDING, "payer-1", workload(), route("0")));
        assertEquals(0, count("economy_task_quote"));
    }

    @Test
    void freezesQuotePricePayerAndStructuredSnapshot() throws Exception {
        Quote first = quote(TASK, BINDING, "0.500001");
        Quote replay = inTask(TASK, () -> billing.quote(TASK, BINDING, "payer-1", null, null));
        assertEquals(first, replay);
        assertEquals(51, first.amountWen());
        var snapshot = new ObjectMapper().readTree(first.snapshotJson());
        assertEquals("0.500001", snapshot.get("estimatedYuan").asText());
        assertEquals("MODEL_TOKEN_ESTIMATE", snapshot.get("ruleId").asText());
        assertEquals(1_000_000L, snapshot.get("tokens").get("INPUT").asLong());
        assertEquals("synthetic-test-price", snapshot.get("modelPrice").get("basis").asText());
        assertEquals(1L, snapshot.get("workload").get("deliverables").asLong());
        assertEquals(1, count("economy_task_quote"));
        assertReason(CHARGE_CONFLICT, () -> inTask(TASK, () -> billing.quote(TASK, BINDING, "other-payer", workload(), route("0"))));
        Binding next = new Binding(1, "requirement-sha", "input-sha", "agent-1", "recommendation-2");
        assertEquals(10, quote(TASK, next, "0.1").amountWen());
    }

    @Test
    void unknownPriceDoesNotPersistFreeQuoteOrTouchWallet() {
        jdbc.update("INSERT INTO economy_task_model_tariff VALUES(?,?,?,?,?,?,?)", SCOPE.tenantId(), SCOPE.clientId(),
                route("unknown"), "test-channel", "standard", "[]", 1L);
        var missing = assertThrows(TaskPricingService.Unavailable.class, () -> quote(TASK, BINDING, "unknown"));
        assertEquals(TaskPricingService.Unavailable.Reason.MODEL_PRICE_MISSING, missing.reason());
        assertEquals(0, count("economy_task_quote"));
        assertEquals(0, count("economy_transaction"));
        assertEquals(100, wallet());
    }

    @Test
    void unavailableCandidateDoesNotMarkSharedQuoteTransactionRollbackOnly() {
        Quote available = inTask(TASK, () -> {
            var first = billing.quote(TASK, BINDING, "payer-1", workload(), route("0.1"));
            var second = new Binding(1, "requirement-sha", "input-sha", "unpriced-agent", "recommendation-1");
            assertThrows(TaskPricingService.Unavailable.class,
                    () -> billing.quote(TASK, second, "payer-1", workload(), route("missing")));
            return first;
        });
        assertEquals(10, available.amountWen());
        assertEquals(1, count("economy_task_quote"));
        assertEquals(100, wallet());
        assertEquals(0, count("economy_transaction"));
    }

    @Test
    void malformedCatalogRollsBackEarlierQuoteInSameTransaction() {
        jdbc.update("UPDATE economy_model_price SET price_data_json='broken' WHERE model=?", route("0.3"));
        assertThrows(RuntimeException.class, () -> inTask(TASK, () -> {
            billing.quote(TASK, BINDING, "payer-1", workload(), route("0.1"));
            var second = new Binding(1, "requirement-sha", "input-sha", "other-agent", "recommendation-1");
            return billing.quote(TASK, second, "payer-1", workload(), route("0.3"));
        }));
        assertEquals(0, count("economy_task_quote"));
        assertEquals(100, wallet());
    }

    @Test
    void savedQuoteReplaysEvenWhenCatalogTablesAreUnavailable() {
        Quote first = quote(TASK, BINDING, "0.1");
        jdbc.execute("DROP TABLE economy_task_model_tariff");
        jdbc.execute("DROP TABLE economy_model_price_current");
        jdbc.execute("DROP TABLE economy_model_price");
        assertEquals(first, inTask(TASK, () -> billing.quote(TASK, BINDING, "payer-1", null, null)));
    }

    @Test
    void amountOverflowIsUnavailableWithoutPersistingQuote() {
        var overflow = assertThrows(TaskPricingService.Unavailable.class, () -> quote(TASK, BINDING, "999999999999999999999"));
        assertEquals(TaskPricingService.Unavailable.Reason.QUOTE_AMOUNT_OVERFLOW, overflow.reason());
        assertEquals(0, count("economy_task_quote"));
        assertEquals(100, wallet());
    }

    @Test
    void snapshotPreservesToolIdentityAndRoundsOnlyWholeTaskTotal() throws Exception {
        jdbc.update("INSERT INTO economy_tool_price VALUES(?,?,?,?,?,?,?,?)", SCOPE.tenantId(), SCOPE.clientId(),
                "test-channel", "render", "image", "tool-v2", "{\"basis\":\"synthetic-test-price\",\"yuanPerUnit\":\"0.004\"}", 1L);
        jdbc.update("INSERT INTO economy_tool_price_current VALUES(?,?,?,?,?,?,?)", SCOPE.tenantId(), SCOPE.clientId(),
                "test-channel", "render", "image", "tool-v2", 1L);
        jdbc.update("UPDATE economy_task_model_tariff SET tools_json=? WHERE model=?",
                "[{\"channel\":\"test-channel\",\"tool\":\"render\",\"unit\":\"image\",\"unitsPerDeliverable\":\"1\"}]", route("0.004"));
        var workload = workload();
        Quote quote = inTask(TASK, () -> billing.quote(TASK, BINDING, "payer-1", workload, route("0.004")));
        assertEquals(1, quote.amountWen());
        var tree = new ObjectMapper().readTree(quote.snapshotJson());
        assertTrue(tree.get("tools").isArray());
        assertEquals("render", tree.get("tools").get(0).get("key").get("tool").asText());
        assertEquals("tool-v2", tree.get("tools").get(0).get("price").get("version").asText());
        assertEquals("0.008", tree.get("estimatedYuan").asText());
    }

    @Test
    void concurrentAcceptanceAcrossTasksSharesIncomeAccountWithoutLockUpgrade() throws Exception {
        Task other = new Task(SCOPE, TASK.ownerJiacn(), "task-2");
        reserve(TASK, quote(TASK, BINDING, "0.3"));
        reserve(other, quote(other, BINDING, "0.4"));
        var results = race(() -> inTask(TASK, () -> billing.capture(TASK)),
                () -> inTask(other, () -> billing.capture(other)));
        for (Object result : results) assertEquals(State.CAPTURED, assertInstanceOf(Charge.class, result).state());
        assertEquals(70L, jdbc.queryForObject("SELECT balance_wen FROM economy_account WHERE purpose='TASK_FEE'", Long.class));
        assertEquals(30, wallet());
        assertEquals(4, count("economy_transaction"));
    }

    @Test
    void rejectsChangedRequirementMaterialsCandidateRecommendationAndOtherOwner() {
        Quote quote = quote(TASK, BINDING, "0.5");
        for (Binding changed : List.of(new Binding(2, "requirement-sha", "input-sha", "agent-1", "recommendation-1"),
                new Binding(1, "other", "input-sha", "agent-1", "recommendation-1"),
                new Binding(1, "requirement-sha", "other", "agent-1", "recommendation-1"),
                new Binding(1, "requirement-sha", "input-sha", "other", "recommendation-1"),
                new Binding(1, "requirement-sha", "input-sha", "agent-1", "other"))) {
            assertReason(QUOTE_BINDING_CHANGED, () -> inTask(TASK, () -> billing.reserve(TASK, quote.quoteId(), changed, "payer-1")));
        }
        Task other = new Task(SCOPE, "other-owner", TASK.taskId());
        assertReason(QUOTE_NOT_FOUND, () -> inTask(other, () -> billing.reserve(other, quote.quoteId(), BINDING, "payer-1")));
        assertEquals(100, wallet());
        assertEquals(0, count("economy_task_charge"));
    }

    @Test
    void reservationRequiresQuotedWalletPrincipalNotJustTaskOwner() {
        Quote quote = quote(TASK, BINDING, "0.5");
        for (String unauthorized : java.util.Arrays.asList("other-payer", TASK.ownerJiacn(), "", null)) {
            assertReason(QUOTE_PAYER_MISMATCH, () -> inTask(TASK,
                    () -> billing.reserve(TASK, quote.quoteId(), BINDING, unauthorized)));
        }
        assertEquals(100, wallet());
        assertEquals(0, count("economy_task_charge"));
        assertEquals(0, count("economy_transaction"));
        assertEquals(0, count("economy_escrow"));
        assertEquals(State.HELD, reserve(TASK, quote).state());
        assertEquals(50, wallet());
    }

    @Test
    void reservationReplayStillRequiresTheOriginalPayerAndPreservesHeldFunds() {
        Quote quote = quote(TASK, BINDING, "0.5");
        Charge held = reserve(TASK, quote);
        assertReason(QUOTE_PAYER_MISMATCH, () -> inTask(TASK,
                () -> billing.reserve(TASK, quote.quoteId(), BINDING, "other-payer")));
        assertEquals(held, reserve(TASK, quote));
        assertEquals(50, wallet());
        assertEquals(1, count("economy_task_charge"));
        assertEquals(1, count("economy_transaction"));
    }

    @Test
    void replayReadsOriginalReservationWithoutReopeningSettledCharge() {
        Quote quote = quote(TASK, BINDING, "0.5");
        assertReason(RESERVATION_REQUIRED, () -> inTask(TASK,
                () -> billing.reservation(TASK, quote.quoteId(), "payer-1", BINDING.recommendationId())));
        Charge held = reserve(TASK, quote);
        assertEquals(held, inTask(TASK, () -> billing.reservation(TASK, quote.quoteId(), "payer-1", BINDING.recommendationId())));
        assertReason(QUOTE_PAYER_MISMATCH, () -> inTask(TASK,
                () -> billing.reservation(TASK, quote.quoteId(), "other-payer", BINDING.recommendationId())));
        assertReason(CHARGE_CONFLICT, () -> inTask(TASK,
                () -> billing.reservation(TASK, "other-quote", "payer-1", BINDING.recommendationId())));
        assertReason(QUOTE_BINDING_CHANGED, () -> inTask(TASK,
                () -> billing.reservation(TASK, quote.quoteId(), "payer-1", "different-recommendation")));
        Charge released = inTask(TASK, () -> billing.release(TASK, true));
        assertEquals(released, inTask(TASK, () -> billing.reservation(TASK, quote.quoteId(), "payer-1", BINDING.recommendationId())));
        assertEquals(100, wallet()); assertEquals(2, count("economy_transaction"));
    }

    @Test
    void zeroQuoteDoesNotBypassPayerAuthorization() {
        Quote quote = quote(TASK, BINDING, "0");
        assertReason(QUOTE_PAYER_MISMATCH, () -> inTask(TASK,
                () -> billing.reserve(TASK, quote.quoteId(), BINDING, "other-payer")));
        assertEquals(0, count("economy_task_charge"));
        assertEquals(State.HELD, reserve(TASK, quote).state());
        assertReason(QUOTE_PAYER_MISMATCH, () -> inTask(TASK,
                () -> billing.reserve(TASK, quote.quoteId(), BINDING, "other-payer")));
        assertEquals(0, count("economy_transaction"));
        assertEquals(100, wallet());
    }

    @Test
    void reserveAndAcceptAreFixedIdempotentAndSeparateFromModelCost() {
        Quote quote = quote(TASK, BINDING, "0.5");
        Charge held = reserve(TASK, quote);
        assertEquals(held, reserve(TASK, quote));
        assertEquals(50, wallet());
        Charge captured = inTask(TASK, () -> billing.capture(TASK));
        assertEquals(State.CAPTURED, captured.state());
        assertEquals(captured, inTask(TASK, () -> billing.capture(TASK)));
        assertEquals(50, wallet());
        assertEquals(50L, jdbc.queryForObject("SELECT balance_wen FROM economy_account WHERE purpose='TASK_FEE'", Long.class));
        assertEquals(List.of("RESERVE_BOUNTY", "CAPTURE_TASK"), jdbc.queryForList(
                "SELECT business_type FROM economy_transaction ORDER BY id", String.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM economy_account WHERE purpose='MODEL_COST'", Integer.class));
        assertReason(CHARGE_CONFLICT, () -> inTask(TASK, () -> billing.release(TASK, true)));
        assertEquals(4, count("economy_entry"));
    }

    @Test
    void cancellationRefundsAllOnceAndCannotBeReReserved() {
        Quote quote = quote(TASK, BINDING, "0.8");
        reserve(TASK, quote);
        Charge released = inTask(TASK, () -> billing.release(TASK, true));
        assertEquals(State.RELEASED, released.state());
        assertEquals(released, inTask(TASK, () -> billing.release(TASK, true)));
        assertEquals(100, wallet());
        assertReason(CHARGE_CONFLICT, () -> inTask(TASK, () -> billing.capture(TASK)));
        assertReason(CHARGE_CONFLICT, () -> reserve(TASK, quote));
        assertEquals(2, count("economy_transaction"));
    }

    @Test
    void zeroQuoteStillHasLifecycleWithoutZeroJournals() {
        Quote quote = quote(TASK, BINDING, "0");
        assertEquals(State.HELD, reserve(TASK, quote).state());
        assertEquals(State.CAPTURED, inTask(TASK, () -> billing.capture(TASK)).state());
        Task other = new Task(SCOPE, TASK.ownerJiacn(), "task-2");
        reserve(other, quote(other, BINDING, "0"));
        assertEquals(State.RELEASED, inTask(other, () -> billing.release(other, true)).state());
        assertEquals(0, count("economy_transaction"));
        assertEquals(0, count("economy_escrow"));
    }

    @Test
    void absentChargeIsNotLegacyFree() {
        assertReason(RESERVATION_REQUIRED, () -> inTask(TASK, () -> billing.capture(TASK)));
        assertReason(RESERVATION_REQUIRED, () -> inTask(TASK, () -> billing.release(TASK, true)));
        assertNull(inTask(TASK, () -> billing.release(TASK, false)));
        jdbc.update("""
                INSERT INTO economy_task_charge(tenant_id,client_id,owner_jiacn,task_id,amount_wen,state,created_at,updated_at)
                VALUES('Tenant-A','Client-A','owner-1','task-1',0,'LEGACY_FREE',1,1)
                """);
        assertEquals(State.LEGACY_FREE, inTask(TASK, () -> billing.capture(TASK)).state());
        assertEquals(State.LEGACY_FREE, inTask(TASK, () -> billing.release(TASK, true)).state());
        assertEquals(0, count("economy_transaction"));
    }

    @Test
    void outerAssignmentFailureRollsBackChargeJournalAccountsAndBusinessTogether() {
        Quote quote = quote(TASK, BINDING, "0.5");
        assertThrows(IllegalStateException.class, () -> inTask(TASK, () -> {
            billing.reserve(TASK, quote.quoteId(), BINDING, "payer-1");
            jdbc.update("UPDATE test_task_root SET state='ASSIGNED' WHERE id='task-1'");
            throw new IllegalStateException("assignment persistence failed");
        }));
        assertEquals("OPEN", taskState());
        assertEquals(100, wallet());
        assertEquals(0, count("economy_task_charge"));
        assertEquals(0, count("economy_transaction"));
        assertEquals(0, count("economy_escrow_funding_lot"));
        assertEquals(1, count("economy_account"));
        assertEquals(State.HELD, reserve(TASK, quote).state());
    }

    @Test
    void outerAcceptanceOrCancellationFailureKeepsReservation() {
        reserve(TASK, quote(TASK, BINDING, "0.5"));
        for (boolean capture : List.of(true, false)) {
            assertThrows(IllegalStateException.class, () -> inTask(TASK, () -> {
                if (capture) billing.capture(TASK); else billing.release(TASK, true);
                jdbc.update("UPDATE test_task_root SET state='DONE' WHERE id='task-1'");
                throw new IllegalStateException("business transition failed");
            }));
            assertEquals("OPEN", taskState());
            assertEquals("HELD", jdbc.queryForObject("SELECT state FROM economy_task_charge", String.class));
            assertEquals(50, wallet());
            assertEquals(1, count("economy_transaction"));
        }
    }

    @Test
    void differentTasksCanCreateQuotesConcurrentlyWithoutMissingRowGapLocks() throws Exception {
        Task other = new Task(SCOPE, TASK.ownerJiacn(), "task-2");
        var results = race(() -> quote(TASK, BINDING, "0.3"), () -> quote(other, BINDING, "0.4"));
        for (Object result : results) assertInstanceOf(Quote.class, result, result::toString);
        assertEquals(2, count("economy_task_quote"));
        assertEquals(0, count("economy_task_charge"));
        assertEquals(100, wallet());
    }

    @Test
    void sameTaskConcurrentReservationReplaysOneCharge() throws Exception {
        Quote quote = quote(TASK, BINDING, "0.8");
        List<Object> results = race(() -> reserve(TASK, quote), () -> reserve(TASK, quote));
        assertInstanceOf(Charge.class, results.get(0));
        assertEquals(results.get(0), results.get(1));
        assertEquals(20, wallet());
        assertEquals(1, count("economy_transaction"));
    }

    @Test
    void differentTasksCannotOverspendSharedWallet() throws Exception {
        Task other = new Task(SCOPE, TASK.ownerJiacn(), "task-2");
        Quote a = quote(TASK, BINDING, "0.8"), b = quote(other, BINDING, "0.8");
        List<Object> results = race(() -> reserve(TASK, a), () -> reserve(other, b));
        assertEquals(1, results.stream().filter(Charge.class::isInstance).count());
        Object error = results.stream().filter(Throwable.class::isInstance).findFirst().orElseThrow();
        EconomyPostingException failure = assertInstanceOf(EconomyPostingException.class, error, error::toString);
        assertEquals(EconomyPostingException.Reason.INSUFFICIENT_FUNDS, failure.reason());
        assertEquals(20, wallet());
        assertEquals(1, count("economy_task_charge"));
        assertEquals(1, count("economy_transaction"));
    }

    @Test
    void acceptanceCancellationRaceHasOneTerminalJournal() throws Exception {
        reserve(TASK, quote(TASK, BINDING, "0.8"));
        List<Object> results = race(() -> inTask(TASK, () -> billing.capture(TASK)),
                () -> inTask(TASK, () -> billing.release(TASK, true)));
        assertEquals(1, results.stream().filter(Charge.class::isInstance).count());
        TaskBillingException conflict = (TaskBillingException) results.stream().filter(Throwable.class::isInstance).findFirst().orElseThrow();
        assertEquals(CHARGE_CONFLICT, conflict.reason());
        Charge terminal = (Charge) results.stream().filter(Charge.class::isInstance).findFirst().orElseThrow();
        assertEquals(terminal.state() == State.CAPTURED ? 20 : 100, wallet());
        assertEquals(2, count("economy_transaction"));
        assertEquals(4, count("economy_entry"));
    }

    private void installGroupSchema() {
        var ddl = new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("db/economy-group-budget-v1.sql"));
        ddl.execute(jdbc.getDataSource());
        var triggers = new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("db/economy-group-budget-price-invalidation-v1.sql"));
        triggers.setSeparator("$$"); triggers.execute(jdbc.getDataSource());
    }
    @Test void rosterSnapshotKeepsUnprovenMembersAcrossPageBoundaryButRejectsLostClaims(){
        installGroupSchema();String key="a".repeat(64),policy="b".repeat(64);
        jdbc.update("INSERT INTO economy_budget_group_head(tenant_id,client_id,group_sha,generation,status,book_epoch,attempt,policy_sha,next_at,updated_at) VALUES(?,?,?,1,'DIRTY',1,0,?,CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3))",SCOPE.tenantId(),SCOPE.clientId(),key,policy);
        var w=maintenance.due(1).getFirst();String claim=java.util.UUID.randomUUID().toString();assertEquals(1,maintenance.claim(w,claim,60));
        var rows=new java.util.ArrayList<Object[]>();var ids=new java.util.TreeSet<String>();
        for(int i=0;i<131;i++){
            String id=GroupPublicationEnrollment.digest("sample-"+i);if(i!=0)ids.add(id);
            rows.add(new Object[]{SCOPE.tenantId(),SCOPE.clientId(),key,id,"t-"+i,id,key,policy,i!=0});
        }
        jdbc.batchUpdate("INSERT INTO economy_budget_group_member VALUES(?,?,?,?,'owner',?,1,?,?,?,?,CURRENT_TIMESTAMP(3))",rows);
        var roster=new GroupRosterSnapshot(maintenance);
        var pins=tx.execute(st->roster.freeze(w,claim));assertEquals(List.copyOf(ids),pins.stream().map(p->p.sampleId()).toList());
        assertTrue(pins.stream().allMatch(p->p.sourceTask().startsWith("t-")&&policy.equals(p.approvalSha())));
        assertEquals(1,maintenance.waitForInput(w,claim));assertThrows(cn.jia.economy.task.TaskBudgetEstimator.Stale.class,()->tx.execute(st->roster.freeze(w,claim)));
    }
    @Test void competingPriceCommitPreventsLateWorkerPublicationAndNewQuotes() throws Exception {
        installGroupSchema();String key="a".repeat(64), policy="c".repeat(64);
        jdbc.update("INSERT INTO economy_budget_domain_epoch VALUES(?,?,7)",SCOPE.tenantId(),SCOPE.clientId());
        jdbc.update("INSERT INTO economy_budget_group_head(tenant_id,client_id,group_sha,generation,status,book_epoch,attempt,policy_sha,next_at,updated_at) VALUES(?,?,?,3,'DIRTY',7,0,?,CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3))",SCOPE.tenantId(),SCOPE.clientId(),key,policy);
        var w=maintenance.due(2).getFirst();String claim=java.util.UUID.randomUUID().toString();
        assertEquals(1,maintenance.claim(w,claim,60));assertEquals(0,maintenance.claim(w,claim,60));
        var pub=new GroupBaselinePublisher(maintenance,new ObjectMapper());
        var rate=new cn.jia.economy.pricing.MeasuredTaskRepricing.ExplicitZero("included");
        var book=cn.jia.economy.pricing.GroupTariffSnapshot.capture(new cn.jia.economy.pricing.MeasuredTaskRepricing.Reference("r1",Map.of("generate",Map.of("tool",rate))));
        var fact=new cn.jia.economy.pricing.MeasuredTaskRepricing.Fact(new cn.jia.economy.pricing.MeasuredTaskRepricing.Operation("image","call1"),"generate","tool",key,cn.jia.economy.pricing.MeasuredTaskRepricing.Zero.EXPLICIT_ZERO);
        var prepared=pub.calculate(w,claim,book,List.of(new GroupBaselinePublisher.Eligible(key,1,key,key,key,key,List.of(fact))),1,policy);
        tx.executeWithoutResult(st->pub.publish(prepared));
        var reader=new GroupBudgetQuotesService(baselines);var k=new GroupBudgetQuotes.Key(key);
        var mean=(GroupBudgetQuotes.Mean)reader.prepare(SCOPE,java.util.Set.of(k)).get(k);assertEquals(0,mean.amountWen());
        // Keep this connection open while a separate connection commits maintenance; emulate preparation then signing.
        var executor=Executors.newSingleThreadExecutor();try {
            tx.executeWithoutResult(st->{assertEquals(7L,maintenance.readDomainEpoch(SCOPE));
                var other=executor.submit(()->jdbc.update("DELETE FROM economy_model_price_current WHERE tenant_id=? AND client_id=? AND model=?",SCOPE.tenantId(),SCOPE.clientId(),route("0.1")));
                try{assertEquals(1,other.get(10,TimeUnit.SECONDS));}catch(Exception e){throw new AssertionError(e);}
                assertThrows(cn.jia.economy.task.TaskBudgetEstimator.Stale.class,()->reader.lockSelected(SCOPE,List.of(mean.pin())));
            });
        } finally {executor.shutdownNow();}
        assertThrows(cn.jia.economy.task.TaskBudgetEstimator.Stale.class,()->tx.executeWithoutResult(st->pub.publish(prepared)));
        assertInstanceOf(GroupBudgetQuotes.Fixed.class,reader.prepare(SCOPE,java.util.Set.of(k)).get(k));
        assertEquals(1,count("economy_budget_group_revision"));assertEquals(1,count("economy_budget_group_build_evidence"));
    }
    private void installPublicationSchema(){
        installGroupSchema();for(var f:List.of("economy-usage-capture.sql","economy-task-benchmark-case-v1.sql","economy-task-benchmark-closure-v1.sql","economy-task-benchmark-review-v1.sql","economy-task-benchmark-sponsorship-v1.sql")){
            new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("db/"+f)).execute(jdbc.getDataSource());}
        var sql=new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("db/economy-group-budget-source-invalidation-v1.sql"));sql.setSeparator("$$");sql.execute(jdbc.getDataSource());
        var approvals=new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("db/economy-group-budget-approval-v1.sql"));approvals.setSeparator("$$");approvals.execute(jdbc.getDataSource());
    }
    private String importTestApproval(Object raw,ObjectMapper json){String body=json.writeValueAsString(raw),sha=GroupPublicationEnrollment.digest(body);jdbc.update("INSERT INTO economy_budget_publish_approval(approval_sha,authority_json) VALUES(?,?)",sha,body);return sha;}
    @SuppressWarnings("unchecked") private <T> T transactional(T target){var proxy=new ProxyFactory(target);proxy.setProxyTargetClass(true);proxy.addAdvice(new TransactionInterceptor(tx.getTransactionManager(),new AnnotationTransactionAttributeSource()));return (T)proxy.getProxy();}
    @Test void approvedStudyRepricesStoredRosterAndWithdrawnStudyImmediatelyStopsNewOffers() throws Exception { verifyReviewedReprice(false,false); }
    @Test void concurrentPriceWithdrawalDuringReviewedRepriceCannotPublishObsoleteMean() throws Exception { verifyReviewedReprice(true,false); }
    @Test void unchangedGroupsRemainQuotableAcrossRealPublication() throws Exception { verifyReviewedReprice(false,true); }
    private void verifyReviewedReprice(boolean changeBook,boolean batchReads) throws Exception {
        installPublicationSchema();var ddl=new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("db/economy-group-study-v1.sql"));ddl.setSeparator("$$");ddl.execute(jdbc.getDataSource());
        String t=SCOPE.tenantId(),c=SCOPE.clientId(),a="a".repeat(64),b="b".repeat(64);var json=new ObjectMapper();var op="edit";
        var buckets=new GroupMeanPricing.Buckets(op,List.of(new GroupMeanPricing.Bucket("one",Map.of(GroupMeanPricing.Dimension.INPUT_FILE_COUNT,new GroupMeanPricing.Range(1,2)))));
        int minSupport=changeBook?1:2;String delayed=null;
        var policy=new GroupMeanPricing.Policy("v","rule","config",buckets,minSupport);var config=new GroupMeanPricing.Configuration("ctx","1","tool","capture",List.of(
            new GroupMeanPricing.Route("MODEL_RESPONSE","INFERENCE","test-channel",route("0.1"),"standard","basis"),new GroupMeanPricing.Route("NATIVE_PROVIDER_CALL",op,"test-channel",route("0.5"),"standard","basis")));
        var g=policy.classify(op,Map.of(GroupMeanPricing.Dimension.INPUT_FILE_COUNT,1L),config).orElseThrow();var key=GroupMeanPricing.key(g);
        var v=new java.util.ArrayList<>(java.util.Collections.nCopies(1024,0f));v.set(0,1f);
        var features=new TaskEstimateFeatures(TaskEstimateFeatures.VERSION,"fixture",a,b,a,new TaskEstimateFeatures.Identity("fixture","model",1024),v,List.of(new TaskEstimateFeatures.File("INPUT","image/png",32,1)),null);
        // The two associated responses are fixture inputs, not provider traffic or an accuracy observation.
        var evidence=org.mockito.Mockito.mock(BenchmarkSampleEvidence.class);var decoder=org.mockito.Mockito.mock(ConfirmedSampleEvidence.Decoder.class);
        var ops=config.routes().stream().map(r->new ConfirmedSampleEvidence.Operation(1,"capture",1,r.kind(),"1",new MeasuredTaskRepricing.Fact(new MeasuredTaskRepricing.Operation(r.kind(),"one"),r.kind(),r.component(),a,new MeasuredTaskRepricing.Tokens("basis",Map.of(ModelTokenPrice.Category.INPUT,1_000_000L))))).toList();
        var enroll=transactional(new GroupPublicationEnrollment(maintenance,json));
        for(int n=0;n<2;n++){
            var task=n==0?TASK:new TaskBillingService.Task(SCOPE,TASK.ownerJiacn(),"race-task-2");String caseId="case-"+n;
            var manifest=new TaskBenchmarkAdmission.Manifest("benchmark-case-v1",task,caseId,"agent","test","review",new TaskBillingService.MeasuredGroup("ver",a,b,"1",a,"capture"),features,new TaskBenchmarkAdmission.GroupInput(op,config));
            var raw=json.writeValueAsString(manifest);String sample=GroupPublicationEnrollment.digest(raw);
            var review=json.writeValueAsString(new BenchmarkSampleEvidence.Review("benchmark-review-v1",caseId,sample,"terminal",a,"ACCEPTED","test","artifact"));String rev=GroupPublicationEnrollment.digest(review);
            jdbc.update("INSERT INTO economy_task_benchmark_case(tenant_id,client_id,owner_jiacn,task_id,benchmark_case_id,manifest_json,manifest_sha) VALUES(?,?,?,?,?,?,?)",t,c,task.ownerJiacn(),task.taskId(),caseId,raw,sample);
            jdbc.update("INSERT INTO economy_task_cost_sample(sample_id,tenant_id,client_id,owner_jiacn,task_id,benchmark_case_id,sample_definition_version,initial_requirement_sha256,initial_input_snapshot_sha256,initial_feature_sha256,lifecycle_state,evidence_revision,sample_state) VALUES(?,?,?,?,?,?,'TASK_LIFECYCLE_V1',?,?,?,'ACCEPTED',1,'MATCHED')",sample,t,c,task.ownerJiacn(),task.taskId(),caseId,a,b,features.digest());
            jdbc.update("INSERT INTO economy_task_benchmark_closure VALUES(?,?,1,1,1)",sample,"terminal");jdbc.update("INSERT INTO economy_task_benchmark_review VALUES(?,?,?,false)",sample,rev,review);
            String approval=importTestApproval(new GroupPublicationEnrollment.Approval("GROUP_AGGREGATE","TRAIN",t,c,key,policy.sha(),task.ownerJiacn(),task.taskId(),caseId,sample,1,sample,rev,"fixture"),json);assertThrows(GroupPublicationEnrollment.NotApproved.class,()->enroll.register(new EconomyScope(t,"different-client"),approval));
            if(!changeBook && !batchReads && n==1)delayed=approval;else enroll.register(SCOPE,approval);
            org.mockito.Mockito.when(evidence.measureReviewed(task,caseId,sample,"terminal",rev,decoder)).thenReturn(new ConfirmedSampleEvidence.Measured(new ConfirmedSampleEvidence.Assessment(sample,1,"terminal",1,ConfirmedSampleEvidence.Verdict.MATCHED),ops));
        }
        var study=importTestApproval(new GroupStudyPolicy.Receipt("GROUP_HOLDOUT_RELEASE_A",t,c,"fixture only",a,b,policy.sha(),key,"rule","config",minSupport,buckets,g,2,2,BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ZERO,BigDecimal.ZERO),json);jdbc.update("INSERT INTO economy_budget_group_study VALUES(?,?,?,?)",t,c,key,study);
        var reading=new CountDownLatch(1);var release=new CountDownLatch(1);
        // Interpose only on the second access to the already-frozen book, after the first real cost was calculated.
        var publisher=new GroupBaselinePublisher(maintenance,json){
            @Override Prepared calculate(cn.jia.economy.mapper.GroupBaselineMaintenanceMapper.Work w,String claim,cn.jia.economy.pricing.GroupTariffSnapshot tariff,List<Eligible> members,int min,String approval){
                if(!changeBook && !batchReads)return super.calculate(w,claim,tariff,members,min,approval);
                assertEquals(2,members.size());assertNotEquals(members.get(0).sampleId(),members.get(1).sampleId());
                var observed=org.mockito.Mockito.spy(tariff);var calls=new java.util.concurrent.atomic.AtomicInteger();
                org.mockito.Mockito.doAnswer(inv->{if(calls.incrementAndGet()==2){reading.countDown();assertTrue(release.await(20,TimeUnit.SECONDS));}return tariff.reference();}).when(observed).reference();
                var calculated=super.calculate(w,claim,observed,members,min,approval);assertEquals(2,calls.get());assertEquals(0,calculated.total().compareTo(new BigDecimal("1.2")));return calculated;
            }
        };
        var service=new SharedGroupMaintenanceService(maintenance,transactional(new GroupRosterSnapshot(maintenance)),transactional(new GroupTariffReader(TaskPricingTestFixture.service(jdbc),maintenance)),transactional(new GroupReviewedUsage(maintenance,enroll,originals,evidence)),transactional(publisher),json);
        var reader=new GroupBudgetQuotesService(baselines);var k=new GroupBudgetQuotes.Key(key);
        if(batchReads){
            // Synthetic, already active neighbours in the SAME domain. Only k is rebuilt from the two reviewed members.
            var keys=new java.util.LinkedHashSet<GroupBudgetQuotes.Key>();keys.add(k);
            long epoch=maintenance.readDomainEpoch(SCOPE);
            for(int i=1;i<108;i++){
                var neighbour=GroupPublicationEnrollment.digest("neighbour-"+i);keys.add(new GroupBudgetQuotes.Key(neighbour));
                jdbc.update("INSERT INTO economy_budget_group_head(tenant_id,client_id,group_sha,generation,active_revision,status,tariff_sha,book_epoch,attempt,policy_sha,updated_at) VALUES(?,?,?,1,1,'ACTIVE',?,?,1,?,CURRENT_TIMESTAMP(3))",t,c,neighbour,a,epoch,policy.sha());
                jdbc.update("INSERT INTO economy_budget_group_revision VALUES(?,?,?,1,?,?,?,2,0.60,?,CURRENT_TIMESTAMP(3))",t,c,neighbour,epoch,a,policy.sha(),b);
            }
            var pool=Executors.newSingleThreadExecutor();try{
                var job=pool.submit(()->service.runDue(t,c,decoder));assertTrue(reading.await(20,TimeUnit.SECONDS));
                int fixed=0,mean=0;boolean published=false;var times=new java.util.ArrayList<Long>();
                for(int round=0;round<32;round++){
                    if(round==1)release.countDown();if(round==31)job.get(20,TimeUnit.SECONDS);
                    long begin=System.nanoTime();var batch=reader.prepare(SCOPE,keys);times.add(System.nanoTime()-begin);
                    assertEquals(keys,batch.keySet());
                    for(var entry:batch.entrySet()){
                        if(entry.getKey().equals(k)&&entry.getValue() instanceof GroupBudgetQuotes.Fixed){assertFalse(published);fixed++;continue;}
                        var value=assertInstanceOf(GroupBudgetQuotes.Mean.class,entry.getValue());
                        boolean changed=entry.getKey().equals(k);assertEquals(changed?60:30,value.amountWen());if(changed)published=true;mean++;
                    }
                    if(round==0)assertEquals(1,fixed);if(round==31)assertTrue(published);
                }
                var first=times.removeFirst();var last=times.removeLast();times.sort(Long::compare);
                System.out.printf(java.util.Locale.ROOT,"SHARED_REPRICE_READER groups=108 batches=32 prePublishMs=%.3f racing30P95Ms=%.3f postPublishMs=%.3f fixed=%d mean=%d%n",first/1e6,times.get(28)/1e6,last/1e6,fixed,mean);
                assertEquals(32*108,mean+fixed);assertEquals(1,count("economy_budget_group_build_evidence"));
            }finally{release.countDown();pool.shutdownNow();}return;
        }
        if(changeBook){
            var pool=Executors.newSingleThreadExecutor();try{
                long epoch=maintenance.readDomainEpoch(SCOPE);
                var active=pool.submit(()->service.runDue(t,c,decoder));assertTrue(reading.await(20,TimeUnit.SECONDS));
                assertInstanceOf(GroupBudgetQuotes.Fixed.class,reader.prepare(SCOPE,java.util.Set.of(k)).get(k));
                assertEquals(1,jdbc.update("DELETE FROM economy_model_price_current WHERE tenant_id=? AND client_id=? AND model=?",t,c,route("0.1")));
                assertEquals(epoch+1,maintenance.readDomainEpoch(SCOPE));release.countDown();active.get(20,TimeUnit.SECONDS);
                assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM economy_budget_group_head WHERE status='DIRTY' AND active_revision IS NULL AND claim_id IS NULL",Integer.class));
                assertEquals(0,count("economy_budget_group_revision"));assertEquals(0,count("economy_budget_group_build_evidence"));
                service.runDue(t,c,decoder);assertEquals("WAIT_INPUT",jdbc.queryForObject("SELECT status FROM economy_budget_group_head",String.class));
                assertInstanceOf(GroupBudgetQuotes.Fixed.class,reader.prepare(SCOPE,java.util.Set.of(k)).get(k));
            } finally {release.countDown();pool.shutdownNow();}return;
        }
        long generation=maintenance.dueFor(t,c).getFirst().generation();
        for(int poll=0;poll<10;poll++)service.runDue(t,c,decoder);
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM economy_budget_group_head WHERE generation=? AND status='WAIT_INPUT' AND attempt=1 AND next_at IS NULL AND active_revision IS NULL",Integer.class,generation));
        assertEquals(0,count("economy_budget_group_revision"));assertInstanceOf(GroupBudgetQuotes.Fixed.class,reader.prepare(SCOPE,java.util.Set.of(k)).get(k));
        assertNotNull(delayed);enroll.register(SCOPE,delayed);enroll.register(SCOPE,delayed);
        assertEquals(generation+1,maintenance.dueFor(t,c).getFirst().generation());
        for(int poll=0;poll<10;poll++)service.runDue(t,c,decoder);
        assertEquals(1,count("economy_budget_group_revision"));assertEquals(1,count("economy_budget_group_build_evidence"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM economy_budget_group_head WHERE status='ACTIVE' AND generation=? AND active_revision=generation AND attempt=1",Integer.class,generation+1));
        assertEquals(2,jdbc.queryForObject("SELECT sample_count FROM economy_budget_group_revision",Integer.class));
        assertEquals(60,assertInstanceOf(GroupBudgetQuotes.Mean.class,reader.prepare(SCOPE,java.util.Set.of(k)).get(k)).amountWen());
        assertEquals(study,jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(roster_json,'$.studyApprovalSha')) FROM economy_budget_group_build_evidence",String.class));
        jdbc.update("UPDATE economy_budget_publish_approval SET withdrawn=TRUE WHERE approval_sha=?",study);assertInstanceOf(GroupBudgetQuotes.Fixed.class,reader.prepare(SCOPE,java.util.Set.of(k)).get(k));
    }
    @Test void transientStudyReadFailureDoesNotStarveNextDueGroup(){
        installPublicationSchema();var ddl=new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("db/economy-group-study-v1.sql"));ddl.setSeparator("$$");ddl.execute(jdbc.getDataSource());
        var t=SCOPE.tenantId();var c=SCOPE.clientId();var first="1".repeat(64);var second="2".repeat(64);
        for(var group:List.of(first,second))jdbc.update("INSERT INTO economy_budget_group_head(tenant_id,client_id,group_sha,generation,status,book_epoch,attempt,policy_sha,next_at,updated_at) VALUES(?,?,?,1,'DIRTY',1,0,?,'2020-01-01',CURRENT_TIMESTAMP(3))",t,c,group,"a".repeat(64));
        var order=maintenance.dueFor(t,c);assertEquals(List.of(first,second),order.stream().map(w->w.groupSha()).toList());
        var fault=org.mockito.Mockito.mock(cn.jia.economy.mapper.GroupBaselineMaintenanceMapper.class,org.mockito.AdditionalAnswers.delegatesTo(maintenance));
        org.mockito.Mockito.doThrow(new org.springframework.dao.TransientDataAccessResourceException("fixture")).when(fault).study(order.getFirst());
        var service=new SharedGroupMaintenanceService(fault,null,null,null,null,new ObjectMapper());service.runDue(t,c,null);
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM economy_budget_group_head WHERE group_sha=? AND status='RETRY' AND attempt=1 AND next_at IS NOT NULL AND claim_id IS NULL",Integer.class,first));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM economy_budget_group_head WHERE group_sha=? AND status='WAIT_INPUT' AND attempt=1 AND next_at IS NULL AND claim_id IS NULL",Integer.class,second));
        jdbc.update("UPDATE economy_budget_group_head SET next_at=CURRENT_TIMESTAMP(3) WHERE group_sha=?",first);service.runDue(t,c,null);
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM economy_budget_group_head WHERE group_sha=? AND status='WAIT_INPUT' AND attempt=2 AND next_at IS NULL",Integer.class,first));assertEquals(0,count("economy_budget_group_revision"));
    }
    @Test void absentOrWithdrawnStudyCannotPublishAndExpiredFifthClaimDoesNotRunAgain(){
        installPublicationSchema();var ddl=new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("db/economy-group-study-v1.sql"));ddl.setSeparator("$$");ddl.execute(jdbc.getDataSource());
        var t=SCOPE.tenantId();var c=SCOPE.clientId();String k="d".repeat(64),policy="a".repeat(64),raw="{}",sha=GroupPublicationEnrollment.digest(raw);
        jdbc.update("INSERT INTO economy_budget_group_head(tenant_id,client_id,group_sha,generation,status,book_epoch,attempt,policy_sha,next_at,updated_at) VALUES(?,?,?,1,'DIRTY',1,0,?,CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3))",t,c,k,policy);
        var service=new SharedGroupMaintenanceService(maintenance,null,null,null,null,new ObjectMapper());
        service.runDue(t,c,null);assertEquals("WAIT_INPUT",jdbc.queryForObject("SELECT status FROM economy_budget_group_head",String.class));
        jdbc.update("INSERT INTO economy_budget_publish_approval(approval_sha,authority_json) VALUES(?,?)",sha,raw);
        jdbc.update("INSERT INTO economy_budget_group_study VALUES(?,?,?,?)",t,c,k,sha);
        var w=maintenance.dueFor(t,c).getFirst();assertEquals(2,w.generation());assertEquals(raw,maintenance.study(w).body());
        jdbc.update("UPDATE economy_budget_publish_approval SET withdrawn=true WHERE approval_sha=?",sha);assertNull(maintenance.study(w));assertEquals(0,maintenance.claim(w,"superseded",90));
        jdbc.update("UPDATE economy_budget_group_head SET status='BUILDING',attempt=5,claim_id='dead',next_at=NULL,lease_until=CURRENT_TIMESTAMP(3)-INTERVAL 5 SECOND");service.runDue(t,c,null);
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM economy_budget_group_head WHERE status='WAIT_INPUT' AND attempt=5 AND claim_id IS NULL",Integer.class));
        assertEquals(0,count("economy_budget_group_revision"));
    }
    @Test void onlyOwnedTrainingPublicationApprovalCanEnrollAndReplayDoesNotAdvanceGeneration(){
        installPublicationSchema();
        new cn.jia.economy.config.ScopedCostSampleSchemaInitializer(jdbc).afterPropertiesSet();
        var scopedMigration=new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("db/economy-scoped-group-source-invalidation-v1.sql"));
        scopedMigration.setSeparator("$$");scopedMigration.execute(jdbc.getDataSource());
        var json=new ObjectMapper();var enrollment=new GroupPublicationEnrollment(maintenance,json);
        String t=SCOPE.tenantId(),c=SCOPE.clientId(), sample=GroupPublicationEnrollment.digest("{}"),policy="f".repeat(64),key="d".repeat(64);
        String review=json.writeValueAsString(java.util.Map.of("caseId","case","decision","ACCEPTED","manifestSha",sample));
        String rev=GroupPublicationEnrollment.digest(review);
        jdbc.update("INSERT INTO economy_task_benchmark_case(tenant_id,client_id,owner_jiacn,task_id,benchmark_case_id,manifest_json,manifest_sha) VALUES(?,?,'owner','task','case','{}',?)",t,c,sample);
        jdbc.update("INSERT INTO economy_task_cost_sample(sample_id,tenant_id,client_id,owner_jiacn,task_id,benchmark_case_id,sample_definition_version,initial_requirement_sha256,initial_input_snapshot_sha256,initial_feature_sha256,lifecycle_state,evidence_revision,sample_state) VALUES(?,?,?,'owner','task','case','TASK_LIFECYCLE_V1',?,?,?,'ACCEPTED',1,'MATCHED')",sample,t,c,policy,policy,policy);
        jdbc.update("INSERT INTO economy_task_benchmark_closure VALUES(?,?,1,1,1)",sample,policy);
        jdbc.update("INSERT INTO economy_task_benchmark_review VALUES(?,?,?,false)",sample,rev,review);
        var refusal=GroupPublicationEnrollment.NotApproved.class;
        assertThrows(refusal,()->tx.executeWithoutResult(st->enrollment.register(SCOPE,policy)));
        for(var fields:List.of(List.of("TRAIN_ONLY","TRAIN","owner"),List.of("GROUP_AGGREGATE","HOLDOUT","owner"),List.of("GROUP_AGGREGATE","TRAIN","foreign"),List.of("GROUP_AGGREGATE","TRAIN","owner"))){
            String raw=json.writeValueAsString(new GroupPublicationEnrollment.Approval(fields.get(0),fields.get(1),t,c,key,policy,fields.get(2),"task","case",sample,1,sample,rev,"fixture"));
            String sha=GroupPublicationEnrollment.digest(raw);jdbc.update("INSERT INTO economy_budget_publish_approval(approval_sha,authority_json) VALUES(?,?)",sha,raw);
            Runnable register=()->tx.executeWithoutResult(st->enrollment.register(SCOPE,sha));
            if(!fields.equals(List.of("GROUP_AGGREGATE","TRAIN","owner"))){assertThrows(refusal,register::run);assertEquals(0,count("economy_budget_group_head"));continue;}
            register.run();register.run();assertEquals(1,count("economy_budget_group_member"));assertEquals(1,jdbc.queryForObject("SELECT generation FROM economy_budget_group_head WHERE status='DIRTY' AND active_revision IS NULL",Integer.class));
            jdbc.update("UPDATE economy_budget_publish_approval SET withdrawn=true WHERE approval_sha=?",sha);assertThrows(refusal,register::run);
        }
    }
    @Test void sourceWithdrawalOrEvidenceChangeRevokesAllAndOnlyRegisteredGroupsInSameCommit(){
        installPublicationSchema();
        new cn.jia.economy.config.ScopedCostSampleSchemaInitializer(jdbc).afterPropertiesSet();
        var scopedMigration=new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("db/economy-scoped-group-source-invalidation-v1.sql"));
        scopedMigration.setSeparator("$$");scopedMigration.execute(jdbc.getDataSource());
        String original="{\"purpose\":\"GROUP_AGGREGATE\",\"approvalRef\":\"test-only\"}";jdbc.update("INSERT INTO economy_budget_publish_approval(approval_sha,authority_json) VALUES(SHA2(?,256),?)",original,original);
        String approval=jdbc.queryForObject("SELECT approval_sha FROM economy_budget_publish_approval",String.class);
        String t=SCOPE.tenantId(),c=SCOPE.clientId(),sample="a".repeat(64),p="b".repeat(64),rev="c".repeat(64);
        jdbc.update("INSERT INTO economy_task_benchmark_case(tenant_id,client_id,owner_jiacn,task_id,benchmark_case_id,manifest_json,manifest_sha) VALUES(?,?,'owner','task','case','{}',?)",t,c,sample);
        jdbc.update("INSERT INTO economy_task_cost_sample(sample_id,tenant_id,client_id,owner_jiacn,task_id,benchmark_case_id,sample_definition_version,initial_requirement_sha256,initial_input_snapshot_sha256,initial_feature_sha256,lifecycle_state,evidence_revision,sample_state) VALUES(?,?,?,'owner','task','case','TASK_LIFECYCLE_V1',?,?,?,'ACCEPTED',1,'MATCHED')",sample,t,c,p,p,p);
        jdbc.update("INSERT INTO economy_task_benchmark_closure VALUES(?,?,1,1,1)",sample,p);jdbc.update("INSERT INTO economy_task_benchmark_review VALUES(?,?,'{}',false)",sample,rev);
        for(String k:List.of("d","e","f")){String key=k.repeat(64);
            jdbc.update("INSERT INTO economy_budget_group_head(tenant_id,client_id,group_sha,generation,active_revision,status,tariff_sha,book_epoch,attempt,policy_sha,updated_at) VALUES(?,?,?,1,1,'ACTIVE',?,1,0,?,CURRENT_TIMESTAMP(3))",t,c,key,p,p);
            if(!k.equals("f"))jdbc.update("INSERT INTO economy_budget_group_member VALUES(?,?,?,?,'owner','task',1,?,?,?,1,CURRENT_TIMESTAMP(3))",t,c,key,sample,sample,rev,approval);
        }
        tx.executeWithoutResult(st->{jdbc.update("UPDATE economy_task_benchmark_case SET retired=true");assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM economy_budget_group_head WHERE status='DIRTY' AND active_revision IS NULL",Integer.class));st.setRollbackOnly();});
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM economy_budget_group_head WHERE status='ACTIVE'",Integer.class));
        for(String change:List.of("owner_jiacn='another-owner'","tenant_id='another-tenant'","client_id='another-client'","task_id='new-task'","quote_id='unexpected-confirmed-quote'","sample_definition_version='OTHER'","initial_requirement_sha256=REPEAT('1',64)","initial_input_snapshot_sha256=REPEAT('2',64)","initial_feature_sha256=REPEAT('3',64)")){
            tx.executeWithoutResult(st->{assertEquals(1,jdbc.update("UPDATE economy_task_cost_sample SET "+change+" WHERE sample_id=?",sample));
                assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM economy_budget_group_head WHERE generation=2 AND status='DIRTY' AND active_revision IS NULL",Integer.class));st.setRollbackOnly();});
            assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM economy_budget_group_head WHERE status='ACTIVE' AND generation=1",Integer.class));
        }
        jdbc.update("UPDATE economy_task_benchmark_review SET withdrawn=true");jdbc.update("UPDATE economy_task_cost_sample SET evidence_revision=evidence_revision+1");
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM economy_budget_group_head WHERE status='DIRTY' AND generation=3 AND active_revision IS NULL",Integer.class));
        jdbc.update("UPDATE economy_budget_publish_approval SET withdrawn=true");assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE economy_budget_publish_approval SET withdrawn=false"));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM economy_budget_group_head WHERE generation=4 AND status='DIRTY'",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM economy_budget_group_head WHERE status='ACTIVE' AND generation=1",Integer.class));
    }
    @Test void pricePointerChangeWithdrawsOldBaselineAtomicallyIncludingRawMaintenanceSql() {
        installGroupSchema();
        String model=route("0.1"), key="a".repeat(64), tenant=SCOPE.tenantId(), client=SCOPE.clientId();
        jdbc.update("INSERT INTO economy_budget_domain_epoch VALUES(?,?,7)",tenant,client);
        jdbc.update("INSERT INTO economy_budget_group_head(tenant_id,client_id,group_sha,generation,active_revision,status,tariff_sha,book_epoch,attempt,policy_sha,updated_at) VALUES(?,?,?,3,3,'ACTIVE',?,7,0,?,CURRENT_TIMESTAMP(3))",tenant,client,key,"b".repeat(64),"c".repeat(64));
        String where=" WHERE tenant_id=? AND client_id=? AND model=?";
        // Version bytes cannot be edited behind the pointer, even after a pointer is removed.
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE economy_model_price SET price_data_json='{}' WHERE model=?",model));
        jdbc.update("INSERT INTO economy_tool_price VALUES(?,?,'tool-channel','render','image','v1','{}',1)",tenant,client);
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE economy_tool_price SET price_data_json='[]' WHERE tool='render'"));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("DELETE FROM economy_tool_price WHERE tool='render'"));
        assertEquals(1,jdbc.update("UPDATE economy_model_price_current SET updated_at=updated_at+1"+where,tenant,client,model));
        assertEquals(7L,jdbc.queryForObject("SELECT book_epoch FROM economy_budget_domain_epoch",Long.class));
        tx.executeWithoutResult(st -> { assertEquals(1,jdbc.update("DELETE FROM economy_model_price_current"+where,tenant,client,model));
            assertEquals("DIRTY",jdbc.queryForObject("SELECT status FROM economy_budget_group_head",String.class));st.setRollbackOnly(); });
        assertEquals("ACTIVE",jdbc.queryForObject("SELECT status FROM economy_budget_group_head",String.class));
        assertEquals(7L,jdbc.queryForObject("SELECT book_epoch FROM economy_budget_domain_epoch",Long.class));
        assertEquals(1,jdbc.update("DELETE FROM economy_model_price_current"+where,tenant,client,model));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("DELETE FROM economy_model_price WHERE model=?",model));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM economy_budget_group_head WHERE generation=4 AND book_epoch=8 AND status='DIRTY' AND active_revision IS NULL AND claim_id IS NULL",Integer.class));
    }

    @Test void inlineMeanAndFixedBatchReserveOnlyTheWinningSample() {
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("db/economy-usage-capture.sql")).execute(jdbc.getDataSource());
        var group=new TaskBillingService.MeasuredGroup("dispatch","a".repeat(64),"b".repeat(64),"1","c".repeat(64),"capture");
        var pin=new GroupBudgetQuotes.Pin(new GroupBudgetQuotes.Key("d".repeat(64)),1,2,2,"e".repeat(64),"f".repeat(64),2,new BigDecimal("0.59"));
        var facts=new TaskBillingService.LocalQuoteFacts("v1","edit","small");
        var writes=new java.util.ArrayList<TaskBillingService.OfferWrite>();
        for(int i=0;i<108;i++) {
            var binding=new Binding(1,"1".repeat(64),"2".repeat(64),"agent-"+i,"batch");
            writes.add(i%2==0 ? new TaskBillingService.MeanWrite(binding,new GroupBudgetQuotes.Mean(pin,30),facts,group,null) :
                new TaskBillingService.FixedWrite(binding,new GroupBudgetQuotes.Fixed(TaskQuoteBatches.Reason.NO_BASELINE)));
        }
        var receipts=inTask(TASK,()->billing.storeOffersBatch(TASK,"payer-1",writes));
        assertEquals(108,receipts.stream().map(TaskBillingService.StoredQuote::quoteId).distinct().count());
        for(int i=0;i<108;i++) assertEquals(i%2==0?30:1,receipts.get(i).amountWen());
        var id=receipts.get(0).quoteId();assertTrue(id.startsWith("tq_g1_"));
        assertEquals(jdbc.queryForObject("SELECT snapshot_json FROM economy_task_quote WHERE quote_id=?",String.class,id),
            jdbc.queryForObject("SELECT snapshot_json FROM economy_task_quote_resolved WHERE quote_id=?",String.class,id));
        assertEquals(100,wallet());assertEquals(0,count("economy_task_cost_sample"));
        var held=inTask(TASK,()->billing.reserve(TASK,id,writes.get(0).binding(),"payer-1"));
        assertEquals(held,inTask(TASK,()->billing.reserve(TASK,id,writes.get(0).binding(),"payer-1")));
        assertNull(inTask(TASK,()->billing.heldInitialCommitment(TASK,"agent-0")));
        assertEquals(70,wallet());assertEquals(1,count("economy_task_cost_sample"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM economy_task_cost_sample WHERE quote_id=? AND history_group_sha256 IS NULL AND sample_state='WAITING_EVIDENCE'",Integer.class,id));
    }

    private static ScopedTaskQuoteSnapshot scopedSnapshot(String agent,boolean mean,boolean zero) {
        String h="a".repeat(64);var binding=new Binding(1,"1".repeat(64),"2".repeat(64),agent,"scope-batch");
        var scope=new ScopedTaskQuoteSnapshot.ScopePin(1,binding.requirementSha256(),"3".repeat(64),binding.inputSnapshotDigest());
        var evidence=mean?new ScopedTaskQuoteSnapshot.MeanEvidence(h,List.of("small"),h,List.of(h),h,h,1,1,1,h,2,zero?BigDecimal.ZERO:new BigDecimal("0.59")):null;
        var basis=new ScopedTaskQuoteSnapshot.Basis(mean?"GROUP_MEAN":"FIXED_FALLBACK",evidence,mean?null:TaskQuoteBatches.Reason.COST_INPUT_MISSING);
        var profile=mean?new ScopedTaskQuoteSnapshot.PricingProfilePin("install","runtime",1,1,h,"profile",1,h,h):null;
        return new ScopedTaskQuoteSnapshot(ScopedTaskQuoteSnapshot.SCHEMA,binding,scope,basis,profile,null,null,basis.amountWen());
    }
    private void usageSchema(){new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("db/economy-usage-capture.sql")).execute(jdbc.getDataSource());}

    @Test void scopedFixedQuotesPersistFullBatchReserveOneSampleAndSettleOriginalPrice() {
        usageSchema();var json=new ObjectMapper();var writes=new java.util.ArrayList<TaskBillingService.OfferWrite>();
        for(int i=0;i<108;i++)writes.add(new TaskBillingService.ScopedWrite(scopedSnapshot("agent-"+i,i%2==0,false)));
        var stored=inTask(TASK,()->billing.storeOffersBatch(TASK,"payer-1",writes));
        assertEquals(108,stored.size());assertEquals(108,count("economy_task_quote"));assertEquals(0,count("economy_task_cost_sample"));
        for(int i=0;i<108;i++){
            assertTrue(stored.get(i).quoteId().startsWith("tq_s1_"));assertEquals(i%2==0?30:1,stored.get(i).amountWen());
            String raw=jdbc.queryForObject("SELECT snapshot_json FROM economy_task_quote WHERE quote_id=?",String.class,stored.get(i).quoteId());
            assertEquals(raw,jdbc.queryForObject("SELECT snapshot_json FROM economy_task_quote_resolved WHERE quote_id=?",String.class,stored.get(i).quoteId()));
            assertEquals(((TaskBillingService.ScopedWrite)writes.get(i)).snapshot(),json.readValue(raw,ScopedTaskQuoteSnapshot.class));
            assertFalse(json.readTree(raw).has("preparation"));
        }
        var snapshot=((TaskBillingService.ScopedWrite)writes.getFirst()).snapshot();String id=stored.getFirst().quoteId();
        var held=inTask(TASK,()->billing.reserve(TASK,id,snapshot.binding(),"payer-1"));
        assertEquals(held,inTask(TASK,()->billing.reserve(TASK,id,snapshot.binding(),"payer-1")));
        assertEquals(70,wallet());assertEquals(1,count("economy_task_cost_sample"));
        assertNull(inTask(TASK,()->billing.heldInitialCommitment(TASK,"agent-0")));
        String sample=jdbc.queryForObject("SELECT sample_id FROM economy_task_cost_sample",String.class);
        assertEquals(snapshot.featureSha(),jdbc.queryForObject("SELECT initial_feature_sha256 FROM economy_task_cost_sample",String.class));
        assertNull(jdbc.queryForObject("SELECT history_group_sha256 FROM economy_task_cost_sample",String.class));
        assertNull(originals.confirmed(TASK,sample).groupVersion());
        assertEquals(State.CAPTURED,inTask(TASK,()->billing.capture(TASK)).state());
        assertEquals(30,inTask(TASK,()->billing.reservation(TASK,id,"payer-1","scope-batch")).amountWen());
        assertNotNull(originals.confirmed(TASK,sample));assertEquals(70,wallet());
    }
    @Test void scopedZeroAndOneWenUseSameSchemaAndOnlyRealExecutionCommitmentIsReturned() {
        usageSchema();var zero=scopedSnapshot("agent",true,true);var h="a".repeat(64);
        var execution=new TaskBillingService.MeasuredGroup("dispatch",h,h,"1",h,"capture");
        var snapshot=new ScopedTaskQuoteSnapshot(zero.schema(),zero.binding(),zero.scopePin(),zero.basis(),zero.pricingProfilePin(),h,execution,0);
        var id=inTask(TASK,()->billing.storeOffersBatch(TASK,"payer-1",List.of(new TaskBillingService.ScopedWrite(snapshot)))).getFirst().quoteId();
        inTask(TASK,()->billing.reserve(TASK,id,snapshot.binding(),"payer-1"));assertEquals(100,wallet());
        assertEquals(new TaskBillingService.InitialCommitment(h,execution),inTask(TASK,()->billing.heldInitialCommitment(TASK,"agent")));
        assertNull(inTask(TASK,()->billing.heldInitialCommitment(TASK,"other")));
        String sample=jdbc.queryForObject("SELECT sample_id FROM economy_task_cost_sample",String.class);
        assertEquals(execution.executionConfigSha256(),originals.confirmed(TASK,sample).executionConfig());
        var other=new Task(SCOPE,"owner-1","task-2");var fixed=scopedSnapshot("fallback",false,false);
        var fixedId=inTask(other,()->billing.storeOffersBatch(other,"payer-1",List.of(new TaskBillingService.ScopedWrite(fixed)))).getFirst().quoteId();
        inTask(other,()->billing.reserve(other,fixedId,fixed.binding(),"payer-1"));assertEquals(99,wallet());assertEquals(2,count("economy_task_cost_sample"));
        inTask(other,()->billing.release(other,true));assertEquals(100,wallet());
    }
    @Test void scopedSnapshotsRejectCorruptionBeforeMoneyAndLateChunkFailureRollsBackAllQuotes() {
        usageSchema();var snapshot=scopedSnapshot("agent",true,false);
        var h="a".repeat(64);
        var group=new GroupMeanPricing.ScopeGroup("f",h,List.of(new GroupMeanPricing.ScopeSlot(0,"text.summary","1",h,List.of())),List.of("small"),Map.of("strategy","native"));
        var pin=new GroupBudgetQuotes.Pin(new GroupBudgetQuotes.Key(group.key()),1,1,1,h,h,2,new BigDecimal("0.59"));
        assertEquals(group.scopeShapeSha(),ScopedTaskQuoteSnapshot.MeanEvidence.from(group,new GroupBudgetQuotes.Mean(pin,30)).scopeShapeSha());
        assertThrows(IllegalArgumentException.class,()->ScopedTaskQuoteSnapshot.MeanEvidence.from(group,new GroupBudgetQuotes.Mean(pin,31)));
        assertThrows(IllegalArgumentException.class,()->new ScopedTaskQuoteSnapshot.Basis("FIXED_FALLBACK",null,TaskQuoteBatches.Reason.USAGE_INCOMPLETE));
        assertThrows(IllegalArgumentException.class,()->new ScopedTaskQuoteSnapshot.MeanEvidence(h,List.of("small"),h,List.of(h),h,h,1,1,1,h,2,new BigDecimal("0.0000000000000000000000000000001")));
        assertThrows(IllegalArgumentException.class,()->new ScopedTaskQuoteSnapshot(snapshot.schema(),snapshot.binding(),snapshot.scopePin(),snapshot.basis(),snapshot.pricingProfilePin(),null,null,1));
        assertThrows(IllegalArgumentException.class,()->new ScopedTaskQuoteSnapshot(snapshot.schema(),snapshot.binding(),snapshot.scopePin(),snapshot.basis(),null,null,null,30));
        var writes=new java.util.ArrayList<TaskBillingService.OfferWrite>();
        for(int i=0;i<107;i++)writes.add(new TaskBillingService.ScopedWrite(scopedSnapshot("agent-"+i,false,false)));
        writes.add(writes.getFirst());
        assertThrows(org.springframework.dao.DataAccessException.class,()->inTask(TASK,()->billing.storeOffersBatch(TASK,"payer-1",writes)));
        assertEquals(0,count("economy_task_quote"));assertEquals(100,wallet());
        String id=inTask(TASK,()->billing.storeOffersBatch(TASK,"payer-1",List.of(new TaskBillingService.ScopedWrite(snapshot)))).getFirst().quoteId();
        assertThrows(TaskBillingException.class,()->inTask(TASK,()->billing.reserve(TASK,id,snapshot.binding(),"wrong-payer")));
        var wrong=new Binding(2,snapshot.binding().requirementSha256(),snapshot.binding().inputSnapshotDigest(),"agent","scope-batch");
        assertThrows(TaskBillingException.class,()->inTask(TASK,()->billing.reserve(TASK,id,wrong,"payer-1")));
        String original=jdbc.queryForObject("SELECT snapshot_json FROM economy_task_quote WHERE quote_id=?",String.class,id);
        jdbc.update("UPDATE economy_task_quote SET snapshot_json=JSON_REMOVE(snapshot_json,'$.schema') WHERE quote_id=?",id);
        assertThrows(IllegalStateException.class,()->inTask(TASK,()->billing.reserve(TASK,id,snapshot.binding(),"payer-1")));
        jdbc.update("UPDATE economy_task_quote SET snapshot_json=JSON_SET(?, '$.schema', 'UNKNOWN_SCOPE') WHERE quote_id=?",original,id);
        assertThrows(IllegalStateException.class,()->inTask(TASK,()->billing.reserve(TASK,id,snapshot.binding(),"payer-1")));
        jdbc.update("UPDATE economy_task_quote SET snapshot_json=?,amount_wen=1 WHERE quote_id=?",original,id);
        assertThrows(IllegalStateException.class,()->inTask(TASK,()->billing.reserve(TASK,id,snapshot.binding(),"payer-1")));
        assertEquals(100,wallet());assertEquals(0,count("economy_task_charge"));assertEquals(0,count("economy_task_cost_sample"));
    }

    private record PaidCase(cn.jia.economy.task.ScopedBenchmarkManifest manifest,String sha,
                            ScopedTaskQuoteSnapshot quote,String quoteId) {
        TaskBenchmarkAdmission.ObservedScopeGroup observed(){return new TaskBenchmarkAdmission.ObservedScopeGroup(manifest.scopePin(),manifest.group());}
    }
    private PaidCase scopedPaidCase(cn.jia.economy.task.ScopedBenchmarkManifest.Purpose purpose,boolean zero){
        return scopedPaidCase(purpose,zero,Map.<String,Object>of("strategy","native-summary","routes",List.of("text-model")));
    }
    private PaidCase scopedPaidCase(cn.jia.economy.task.ScopedBenchmarkManifest.Purpose purpose,boolean zero,Map<String,Object> config){return scopedPaidCase(purpose,zero,config,3);}
    private PaidCase scopedPaidCase(cn.jia.economy.task.ScopedBenchmarkManifest.Purpose purpose,boolean zero,Map<String,Object> config,int minSupport){return scopedPaidCase(purpose,zero,config,minSupport,true);}
    private PaidCase scopedPaidCase(cn.jia.economy.task.ScopedBenchmarkManifest.Purpose purpose,boolean zero,Map<String,Object> config,int minSupport,boolean storeQuote){
        usageSchema();new cn.jia.economy.config.ScopedCostSampleSchemaInitializer(jdbc).afterPropertiesSet();
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("db/economy-task-benchmark-case-v1.sql")).execute(jdbc.getDataSource());
        var quote=scopedSnapshot("agent",zero,zero);
        var slots=List.of(new GroupMeanPricing.ScopeSlot(0,"text.summary","1","a".repeat(64),List.of()));
        var input=new GroupMeanPricing.ScopeInput(slots,List.of("short"),cn.jia.core.util.CanonicalValueEncoding.digest(config));
        var policy=new GroupMeanPricing.ScopedPolicy("f1",input.specShas(),input.scopeShapeSha(),"config-1",GroupMeanPricing.ScopedPolicy.definitionsSha(input.specShas()),minSupport);
        var group=new GroupMeanPricing.ScopeGroup("f1",policy.sha(),slots,List.of("short"),config);
        var manifest=new cn.jia.economy.task.ScopedBenchmarkManifest("SCOPED_BENCHMARK_V1",TASK,"case-1","agent","fixture-authority","review complete output",purpose,quote.scopePin(),policy,slots,List.of("short"),config,group.key());
        String body=new ObjectMapper().writeValueAsString(manifest),sha=GroupPublicationEnrollment.digest(body);
        jdbc.update("INSERT INTO economy_task_benchmark_case(tenant_id,client_id,owner_jiacn,task_id,benchmark_case_id,manifest_json,manifest_sha) VALUES(?,?,?,?,?,?,?)",SCOPE.tenantId(),SCOPE.clientId(),TASK.ownerJiacn(),TASK.taskId(),manifest.caseId(),body,sha);
        String id=storeQuote?inTask(TASK,()->billing.storeOffersBatch(TASK,"payer-1",List.of(new TaskBillingService.ScopedWrite(quote)))).getFirst().quoteId():null;
        return new PaidCase(manifest,sha,quote,id);
    }
    private TaskBenchmarkAdmission.ScopedEnrollment preparePaid(PaidCase c){return scopedAdmissions.prepareScopedPaid(TASK,c.manifest().caseId(),c.sha(),"agent",c.observed());}
    private Charge reservePaid(PaidCase c){return inTask(TASK,()->billing.reserve(TASK,c.quoteId(),c.quote().binding(),"payer-1",preparePaid(c)));}

    private record PaidPublication(PaidCase source,String sample,String review,String approval) {}
    private PaidPublication scopedPublication(cn.jia.economy.task.ScopedBenchmarkManifest.Purpose purpose){
        var c=scopedPaidCase(purpose,false);reservePaid(c);installPublicationSchema();
        var migration=new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("db/economy-scoped-group-source-invalidation-v1.sql"));
        migration.setSeparator("$$");migration.execute(jdbc.getDataSource());
        String sample=jdbc.queryForObject("SELECT sample_id FROM economy_task_cost_sample",String.class);
        jdbc.update("UPDATE economy_task_cost_sample SET lifecycle_state='ACCEPTED',sample_state='MATCHED'");
        // Synthetic closure/review: verifies enrollment/withdrawal only, not usage completeness or actual review.
        jdbc.update("INSERT INTO economy_task_benchmark_closure VALUES(?,?,1,1,1)",sample,"a".repeat(64));
        var json=new ObjectMapper();String review=json.writeValueAsString(Map.of("caseId",c.manifest().caseId(),"decision","ACCEPTED","manifestSha",c.sha()));
        String reviewSha=GroupPublicationEnrollment.digest(review);
        jdbc.update("INSERT INTO economy_task_benchmark_review VALUES(?,?,?,false)",sample,reviewSha,review);
        var approval=new GroupPublicationEnrollment.Approval("GROUP_AGGREGATE","TRAIN",SCOPE.tenantId(),SCOPE.clientId(),c.manifest().groupSha(),c.manifest().policy().sha(),TASK.ownerJiacn(),TASK.taskId(),c.manifest().caseId(),sample,1,c.sha(),reviewSha,"publication-fixture");
        return new PaidPublication(c,sample,reviewSha,importTestApproval(approval,json));
    }
    private void registerPaid(PaidPublication p){
        tx.executeWithoutResult(st->new GroupPublicationEnrollment(maintenance,new ObjectMapper()).register(SCOPE,p.approval()));
    }
    @Test void scopedPublicationUsesOriginalPaidIdentityAndSurvivesSettlement(){
        var p=scopedPublication(cn.jia.economy.task.ScopedBenchmarkManifest.Purpose.TRAIN);
        assertNotEquals(p.sample(),p.source().sha());registerPaid(p);registerPaid(p);
        assertEquals(1,count("economy_budget_group_member"));
        assertEquals(1,jdbc.queryForObject("SELECT generation FROM economy_budget_group_head",Integer.class));
        assertEquals(State.CAPTURED,inTask(TASK,()->billing.capture(TASK)).state());
        registerPaid(p);assertEquals(1,count("economy_budget_group_member"));
        assertEquals(p.sample(),jdbc.queryForObject("SELECT sample_id FROM economy_budget_group_member",String.class));
        assertEquals(p.source().sha(),jdbc.queryForObject("SELECT manifest_sha FROM economy_budget_group_member",String.class));
        assertEquals(0,count("economy_budget_group_revision")); // Enrollment is not statistical publication.
    }
    @Test void scopedPublicationCannotRelabelHoldoutAsTraining(){
        var p=scopedPublication(cn.jia.economy.task.ScopedBenchmarkManifest.Purpose.HOLDOUT);
        assertThrows(GroupPublicationEnrollment.NotApproved.class,()->registerPaid(p));
        assertEquals(0,count("economy_budget_group_member"));assertEquals(0,count("economy_budget_group_head"));
    }
    @Test void scopedPublicationRequiresOriginalScopePolicyGroupAndQuotePins(){
        var p=scopedPublication(cn.jia.economy.task.ScopedBenchmarkManifest.Purpose.TRAIN);
        for(String change:List.of("scope_sha256=REPEAT('0',64)","scope_shape_sha256=REPEAT('0',64)",
                "scoped_group_sha256=REPEAT('0',64)","scoped_policy_sha256=REPEAT('0',64)",
                "initial_requirement_sha256=REPEAT('0',64)","initial_input_snapshot_sha256=REPEAT('0',64)",
                "initial_feature_sha256=REPEAT('0',64)","evidence_revision=2","quote_id=NULL")){
            tx.executeWithoutResult(st->{jdbc.update("UPDATE economy_task_cost_sample SET "+change);
                assertThrows(GroupPublicationEnrollment.NotApproved.class,()->new GroupPublicationEnrollment(maintenance,new ObjectMapper()).register(SCOPE,p.approval()));
                assertEquals(0,count("economy_budget_group_member"));st.setRollbackOnly();});
        }
        var c=p.source();var bad=new GroupPublicationEnrollment.Approval("GROUP_AGGREGATE","TRAIN",SCOPE.tenantId(),SCOPE.clientId(),"0".repeat(64),c.manifest().policy().sha(),TASK.ownerJiacn(),TASK.taskId(),c.manifest().caseId(),p.sample(),1,c.sha(),p.review(),"wrong-group");
        String badSha=importTestApproval(bad,new ObjectMapper());
        assertThrows(GroupPublicationEnrollment.NotApproved.class,()->tx.executeWithoutResult(st->new GroupPublicationEnrollment(maintenance,new ObjectMapper()).register(SCOPE,badSha)));
        registerPaid(p);assertEquals(1,count("economy_budget_group_member"));
    }
    @Test void scopedPublicationCaseAndScopeWithdrawalInvalidatesRealSampleMembership(){
        var p=scopedPublication(cn.jia.economy.task.ScopedBenchmarkManifest.Purpose.TRAIN);registerPaid(p);
        for(String change:List.of("UPDATE economy_task_benchmark_case SET retired=TRUE",
                "DELETE FROM economy_task_benchmark_case",
                "UPDATE economy_task_cost_sample SET scope_sha256=REPEAT('0',64)",
                "UPDATE economy_task_cost_sample SET scope_shape_sha256=REPEAT('0',64)",
                "UPDATE economy_task_cost_sample SET scoped_group_sha256=REPEAT('0',64)",
                "UPDATE economy_task_cost_sample SET scoped_policy_sha256=REPEAT('0',64)",
                "UPDATE economy_task_benchmark_review SET withdrawn=TRUE",
                "UPDATE economy_budget_publish_approval SET withdrawn=TRUE")){
            tx.executeWithoutResult(st->{jdbc.update("UPDATE economy_budget_group_head SET status='ACTIVE',active_revision=1,tariff_sha=REPEAT('a',64)");
                jdbc.update(change);assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM economy_budget_group_head WHERE status='DIRTY' AND active_revision IS NULL AND generation=2",Integer.class));
                st.setRollbackOnly();});
            assertEquals(1,jdbc.queryForObject("SELECT generation FROM economy_budget_group_head",Integer.class));
        }
        jdbc.update("UPDATE economy_task_benchmark_case SET retired=TRUE");
        assertThrows(GroupPublicationEnrollment.NotApproved.class,()->registerPaid(p));
        assertEquals(2,jdbc.queryForObject("SELECT generation FROM economy_budget_group_head",Integer.class));
    }

    private record ScopedReview(PaidCase source,String sample,String review,String fact,String measurement) {}
    private ScopedReview scopedReviewedSource(){
        var measured=Map.<String,Object>of("fixture","independently-measured-config");
        return scopedReviewedSource(Map.of("measurement",measured,"routes",List.of("fixture-only")),3);
    }
    private ScopedReview scopedReviewedSource(Map<String,Object> config,int minSupport){return scopedReviewedSource(config,minSupport,false);}
    private ScopedReview scopedReviewedSource(Map<String,Object> config,int minSupport,boolean sponsored){
        var measured=config.get("measurement");var c=scopedPaidCase(cn.jia.economy.task.ScopedBenchmarkManifest.Purpose.TRAIN,false,config,minSupport,!sponsored);
        installPublicationSchema();
        if(sponsored){
            var authority=new TaskBenchmarkAdmission.SponsorshipAuthority("benchmark-sponsorship-v1",TASK,c.manifest().caseId(),c.sha(),"agent","fixture-action","a".repeat(64),"b".repeat(64),"synthetic sponsor only");
            String raw=new ObjectMapper().writeValueAsString(authority);
            jdbc.update("INSERT INTO economy_task_benchmark_sponsorship(tenant_id,client_id,owner_jiacn,task_id,authority_json,authority_sha) VALUES(?,?,?,?,?,SHA2(?,256))",SCOPE.tenantId(),SCOPE.clientId(),TASK.ownerJiacn(),TASK.taskId(),raw,raw);
            inTask(TASK,()->scopedAdmissions.sponsorScope(TASK,"agent",authority.actionId(),authority.assignmentSha256(),authority.initialIntentSha256(),policy->c.observed()));
            assertTrue(inTask(TASK,()->scopedAdmissions.settleSponsorship(TASK,TaskBenchmarkAdmission.SponsorshipState.ACCEPTED)));
            var ddl=new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("db/economy-scoped-sponsor-source-invalidation-v1.sql"));ddl.setSeparator("$$");ddl.execute(jdbc.getDataSource());
        }else reservePaid(c);
        String sample=jdbc.queryForObject("SELECT sample_id FROM economy_task_cost_sample",String.class),fact="a".repeat(64),membership="b".repeat(64);
        jdbc.update("UPDATE economy_task_cost_sample SET lifecycle_state='ACCEPTED'");
        jdbc.update("INSERT INTO economy_task_benchmark_closure VALUES(?,?,2,2,1)",sample,fact);
        jdbc.update("INSERT INTO economy_task_membership_receipt VALUES(?,?,?,?,?,'ACCEPTED',1,1,2,2,?,2,2,?,1)",SCOPE.tenantId(),SCOPE.clientId(),TASK.ownerJiacn(),TASK.taskId(),fact,membership,membership);
        var review=new BenchmarkSampleEvidence.Review("benchmark-review-v1",c.manifest().caseId(),c.sha(),fact,membership,"ACCEPTED","test-reviewer","test-deliverable");
        String raw=new ObjectMapper().writeValueAsString(review),sha=GroupPublicationEnrollment.digest(raw);
        jdbc.update("INSERT INTO economy_task_benchmark_review VALUES(?,?,?,false)",sample,sha,raw);
        for(int i=1;i<=2;i++){
            String capture=Integer.toString(i).repeat(64),exec="execution-"+i,run="run-"+i;
            jdbc.update("INSERT INTO economy_task_membership_received_run VALUES(?,?,?,?,?,?,?,?)",SCOPE.tenantId(),SCOPE.clientId(),fact,i,exec,run,capture,membership);
            jdbc.update("INSERT INTO economy_task_cost_sample_member VALUES(?,?,?,?)",sample,exec,run,capture);
            jdbc.update("INSERT INTO economy_usage_capture(tenant_id,client_id,owner_jiacn,task_id,agent_id,execution_id,run_id,capture_id,input_revision,projection_revision,coverage_state) VALUES(?,?,?,?,?,?,?,?,1,1,'COMPLETE')",SCOPE.tenantId(),SCOPE.clientId(),TASK.ownerJiacn(),TASK.taskId(),"agent",exec,run,capture);
            long pk=jdbc.queryForObject("SELECT id FROM economy_usage_capture WHERE execution_id=?",Long.class,exec);
            // Synthetic adapter bytes exercise two keyset pages; native parsing is covered by Agent tests.
            for(int n=1;n<=65;n++)jdbc.update("INSERT INTO economy_usage_capture_record(capture_pk,record_id,revision_key,source_revision,kind,evidence_sha256,binding_sha256,evidence_json,is_associated_head,association_state) VALUES(?,?,SHA2('1',256),'1','RESPONSE',?,?,'{}',1,'ASSOCIATED')",pk,String.format("%064x",n),membership,membership);
        }
        return new ScopedReview(c,sample,sha,fact,cn.jia.core.util.CanonicalValueEncoding.digest(measured));
    }
    private ConfirmedSampleEvidence.Decoder scopedDecoder(ScopedReview source,boolean mixed){
        var decoder=org.mockito.Mockito.mock(ConfirmedSampleEvidence.Decoder.class);
        org.mockito.Mockito.when(decoder.openScoped(org.mockito.ArgumentMatchers.eq(TASK),org.mockito.ArgumentMatchers.any())).thenAnswer(call->{
            ConfirmedSampleEvidence.ScopedExecution e=call.getArgument(1);var fold=org.mockito.Mockito.mock(ConfirmedSampleEvidence.ScopedFold.class);
            var ref=new cn.jia.economy.pricing.CostRouteReference("MODEL_RESPONSE","INFERENCE","actual-source","actual-model","basis");
            var fact=new MeasuredTaskRepricing.Fact(new MeasuredTaskRepricing.Operation("actual-source",e.execution()),ref.kind(),ref.component(),"c".repeat(64),new MeasuredTaskRepricing.Tokens("basis",Map.of(ModelTokenPrice.Category.INPUT,10L)));
            var op=new ConfirmedSampleEvidence.ScopedOperation(ref,new ConfirmedSampleEvidence.Operation(e.memberRevision(),e.captureId(),e.captureRevision(),"d".repeat(64),"1",fact));
            var seen=new java.util.ArrayList<String>();
            org.mockito.Mockito.doAnswer(inv->{cn.jia.economy.usage.UsageCaptureProjection.Row row=inv.getArgument(0);seen.add(row.recordId());return null;}).when(fold).accept(org.mockito.ArgumentMatchers.any());
            org.mockito.Mockito.when(fold.finish()).thenAnswer(inv->{assertEquals(65,seen.size());assertEquals(65,new java.util.HashSet<>(seen).size());assertEquals(String.format("%064x",65),seen.getLast());return new ConfirmedSampleEvidence.ScopedCapture(mixed&&e.memberRevision()==2?"0".repeat(64):source.measurement(),List.of(op));});return fold;
        });return decoder;
    }
    private ConfirmedSampleEvidence.ScopedMeasured measureScoped(ScopedReview p,ConfirmedSampleEvidence.Decoder decoder){
        return tx.execute(st->new ConfirmedSampleEvidenceReader(originals,new ObjectMapper()).measureScopedReviewed(TASK,p.sample(),p.source().manifest().caseId(),p.source().sha(),p.fact(),p.review(),decoder));
    }
    @Test void scopedReviewedPaidSampleUsesActualIdentityAfterCaptureWithoutLegacyDispatchFields(){
        var p=scopedReviewedSource();var decoder=scopedDecoder(p,false);
        assertEquals(State.CAPTURED,inTask(TASK,()->billing.capture(TASK)).state());
        var measured=measureScoped(p,decoder);assertNotNull(measured);assertEquals(ConfirmedSampleEvidence.Verdict.MATCHED,measured.assessment().verdict());
        assertEquals(p.sample(),measured.assessment().sampleId());assertNotEquals(p.source().sha(),p.sample());
        assertEquals(2,measured.operations().size());assertEquals(2,measured.assessment().members());
        assertEquals(0,count("economy_usage_capture_dispatch_group"));
        org.mockito.Mockito.verify(decoder,org.mockito.Mockito.never()).coversAllOperations(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
    }
    @Test void scopedReviewedOriginalReviewClosureAndMembershipCannotBeBypassed(){
        var p=scopedReviewedSource();var decoder=scopedDecoder(p,false);
        for(String sql:List.of("UPDATE economy_task_benchmark_case SET retired=TRUE","UPDATE economy_task_benchmark_review SET withdrawn=TRUE",
                "UPDATE economy_task_benchmark_closure SET completed=FALSE","UPDATE economy_task_membership_receipt SET membership_verified=FALSE",
                "UPDATE economy_task_membership_receipt SET member_count=1","UPDATE economy_task_membership_receipt SET membership_revision=1",
                "UPDATE economy_task_membership_receipt SET set_digest=REPEAT('0',64)","UPDATE economy_task_cost_sample SET lifecycle_state='CANCELLED'")){
            org.mockito.Mockito.clearInvocations(decoder);
            tx.executeWithoutResult(st->{jdbc.update(sql);assertNull(measureScoped(p,decoder));org.mockito.Mockito.verifyNoInteractions(decoder);st.setRollbackOnly();});
        }
        assertNotNull(measureScoped(p,decoder));
    }
    @Test void scopedReviewedMixedOrMissingMemberNeverReturnsPartialTaskCost(){
        var p=scopedReviewedSource();var mixed=measureScoped(p,scopedDecoder(p,true));
        assertEquals(ConfirmedSampleEvidence.Verdict.MIXED_EXECUTION_CONFIG,mixed.assessment().verdict());assertTrue(mixed.operations().isEmpty());
        var decoder=scopedDecoder(p,false);
        for(String sql:List.of("DELETE FROM economy_task_cost_sample_member WHERE execution_id='execution-2'",
                "UPDATE economy_usage_capture SET input_revision=2 WHERE execution_id='execution-2'",
                "UPDATE economy_usage_capture SET coverage_state='INCOMPLETE' WHERE execution_id='execution-2'")){
            tx.executeWithoutResult(st->{jdbc.update(sql);var result=measureScoped(p,decoder);assertNotEquals(ConfirmedSampleEvidence.Verdict.MATCHED,result.assessment().verdict());assertTrue(result.operations().isEmpty());st.setRollbackOnly();});
        }
        jdbc.update("UPDATE economy_task_cost_sample SET scoped_policy_sha256=REPEAT('0',64)");
        assertThrows(IllegalStateException.class,()->measureScoped(p,decoder));
    }

    private record ScopedWorker(ScopedReview source,ConfirmedSampleEvidence.Decoder decoder,
                                java.util.List<cn.jia.economy.pricing.CostRouteReference> refs,String study) {}
    private ScopedWorker scopedWorkerFixture(int minSupport){return scopedWorkerFixture(minSupport,false);}
    private ScopedWorker scopedWorkerFixture(int minSupport,boolean sponsored){
        var a=new cn.jia.economy.pricing.CostRouteReference("MODEL_RESPONSE","INFERENCE","provider","model-a","basis");
        var b=new cn.jia.economy.pricing.CostRouteReference("MODEL_RESPONSE","INFERENCE","provider","model-b","basis");var refs=List.of(a,b);
        var measurement=Map.<String,Object>of("executionStrategyVersion","native","contextPolicyId","context","contextPolicyVersion","a".repeat(64),
                "toolPolicyVersion","b".repeat(64),"capturePolicyVersion","fixture-adapter","routeRefs",refs.stream().map(cn.jia.economy.pricing.CostRouteReference::canonical).toList(),
                "skillPackages",List.of(Map.of("skillKey","summary","skillVersion","1","packageSha","c".repeat(64))));
        var routes=List.of(Map.of("reference",a.canonical(),"pricing",new cn.jia.economy.pricing.CostPricingRoute.TokenRoute("test-channel",route("0.1"),"standard","basis").canonical()),
                Map.of("reference",b.canonical(),"pricing",new cn.jia.economy.pricing.CostPricingRoute.TokenRoute("test-channel",route("0.5"),"standard","basis").canonical()));
        var p=scopedReviewedSource(Map.of("measurement",measurement,"routes",routes),minSupport,sponsored);var m=p.source().manifest();var json=new ObjectMapper();
        var publication=new GroupPublicationEnrollment.Approval("GROUP_AGGREGATE","TRAIN",SCOPE.tenantId(),SCOPE.clientId(),m.groupSha(),m.policy().sha(),TASK.ownerJiacn(),TASK.taskId(),m.caseId(),p.sample(),1,p.source().sha(),p.review(),"synthetic-publish-approval");
        transactional(new GroupPublicationEnrollment(maintenance,json)).register(SCOPE,importTestApproval(publication,json));
        var ddl=new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("db/economy-group-study-v1.sql"));ddl.setSeparator("$$");ddl.execute(jdbc.getDataSource());
        new cn.jia.economy.config.ScopedGroupStudySchemaInitializer(jdbc).afterPropertiesSet();
        var receipt=new DatabaseScopedGroupPolicies.Receipt(DatabaseScopedGroupPolicies.PURPOSE,SCOPE.tenantId(),SCOPE.clientId(),"synthetic-not-accuracy-proof","a".repeat(64),"b".repeat(64),m.policy(),m.policy().sha(),m.group().input().key(),m.groupSha(),m.scopeShape(),m.bucketIds(),m.compatibleConfig(),2,2,BigDecimal.ONE,BigDecimal.ZERO,BigDecimal.ZERO);
        String study=importTestApproval(receipt,json);
        jdbc.update("INSERT INTO economy_budget_group_study(tenant_id,client_id,group_sha,approval_sha,scope_input_sha) VALUES(?,?,?,?,?)",SCOPE.tenantId(),SCOPE.clientId(),m.groupSha(),study,m.group().input().key());
        // Only the independent native evidence adapter is substituted. Real reader, original SQL,
        // transaction snapshots, review/closure verification, study, current tariff and publisher run.
        var decoder=org.mockito.Mockito.mock(ConfirmedSampleEvidence.Decoder.class);
        org.mockito.Mockito.when(decoder.openScoped(org.mockito.ArgumentMatchers.eq(TASK),org.mockito.ArgumentMatchers.any())).thenAnswer(call->{
            ConfirmedSampleEvidence.ScopedExecution execution=call.getArgument(1);var resource=refs.get((int)execution.memberRevision()-1);var seen=new java.util.ArrayList<String>();
            return new ConfirmedSampleEvidence.ScopedFold(){
                public void accept(cn.jia.economy.usage.UsageCaptureProjection.Row row){seen.add(row.recordId());}
                public ConfirmedSampleEvidence.ScopedCapture finish(){
                    assertEquals(65,seen.size());assertEquals(65,new java.util.HashSet<>(seen).size());
                    var fact=new MeasuredTaskRepricing.Fact(new MeasuredTaskRepricing.Operation("actual",execution.execution()),resource.kind(),resource.component(),"e".repeat(64),new MeasuredTaskRepricing.Tokens("basis",Map.of(ModelTokenPrice.Category.INPUT,1_000_000L)));
                    var operation=new ConfirmedSampleEvidence.Operation(execution.memberRevision(),execution.captureId(),execution.captureRevision(),seen.getFirst(),"1",fact);
                    return new ConfirmedSampleEvidence.ScopedCapture(p.measurement(),List.of(new ConfirmedSampleEvidence.ScopedOperation(resource,operation)));
                }
            };
        });return new ScopedWorker(p,decoder,refs,study);
    }
    private SharedGroupMaintenanceService scopedWorkerService(GroupBaselinePublisher publisher,cn.jia.economy.pricing.TaskReferencePricing prices){
        var json=new ObjectMapper();return new SharedGroupMaintenanceService(maintenance,transactional(new GroupRosterSnapshot(maintenance)),transactional(new GroupTariffReader(prices,maintenance)),
                transactional(new GroupReviewedUsage(maintenance,new GroupPublicationEnrollment(maintenance,json),originals,transactional(new ConfirmedSampleEvidenceReader(originals,json)))),transactional(publisher),json);
    }
    private GroupBudgetQuotes.Decision scopedDecision(ScopedWorker w){var key=new GroupBudgetQuotes.Key(w.source().source().manifest().groupSha());return new GroupBudgetQuotesService(baselines).prepare(SCOPE,java.util.Set.of(key)).get(key);}
    @Test void scopedSponsoredWorkerPublishesWithoutAnyQuoteOrChargeAndRetainsHistoricalFunding(){
        var w=scopedWorkerFixture(1,true);var service=scopedWorkerService(new GroupBaselinePublisher(maintenance,new ObjectMapper()),TaskPricingTestFixture.service(jdbc));
        service.runDue(SCOPE.tenantId(),SCOPE.clientId(),w.decoder());assertEquals(60,assertInstanceOf(GroupBudgetQuotes.Mean.class,scopedDecision(w)).amountWen());
        assertEquals(0,count("economy_task_quote"));assertEquals(0,count("economy_task_charge"));assertEquals(0,count("economy_transaction"));assertEquals(100,wallet());
        assertEquals(w.source().source().sha(),w.source().sample());assertEquals(1,count("economy_budget_group_revision"));
        jdbc.update("UPDATE economy_task_benchmark_sponsorship SET withdrawn=TRUE");
        assertEquals(60,assertInstanceOf(GroupBudgetQuotes.Mean.class,scopedDecision(w)).amountWen());
        assertEquals(ConfirmedSampleEvidence.Verdict.MATCHED,measureScoped(w.source(),w.decoder()).assessment().verdict());
        service.runDue(SCOPE.tenantId(),SCOPE.clientId(),w.decoder());assertEquals(1,count("economy_budget_group_revision"));
    }
    @Test void scopedSponsoredMissingOrUnsettledFundingInvalidatesPublishedBasisAndCannotReadUsage(){
        var w=scopedWorkerFixture(1,true);scopedWorkerService(new GroupBaselinePublisher(maintenance,new ObjectMapper()),TaskPricingTestFixture.service(jdbc)).runDue(SCOPE.tenantId(),SCOPE.clientId(),w.decoder());
        long generation=jdbc.queryForObject("SELECT generation FROM economy_budget_group_head",Long.class);
        for(String change:List.of("DELETE FROM economy_task_benchmark_sponsorship","UPDATE economy_task_benchmark_sponsorship SET state='APPROVED'",
                "UPDATE economy_task_benchmark_sponsorship SET state='ACTIVE'","UPDATE economy_task_benchmark_sponsorship SET state='CANCELLED'")){
            tx.executeWithoutResult(st->{jdbc.update(change);assertInstanceOf(GroupBudgetQuotes.Fixed.class,scopedDecision(w));
                assertEquals(generation+1,jdbc.queryForObject("SELECT generation FROM economy_budget_group_head",Long.class));
                org.mockito.Mockito.clearInvocations(w.decoder());assertNull(measureScoped(w.source(),w.decoder()));org.mockito.Mockito.verifyNoInteractions(w.decoder());
                st.setRollbackOnly();});
        }
        assertInstanceOf(GroupBudgetQuotes.Mean.class,scopedDecision(w));assertEquals(1,count("economy_budget_group_revision"));
    }
    @Test void scopedSponsoredCorruptOrMismatchedAuthorityIsNotFreeSourceEvidence(){
        var w=scopedWorkerFixture(1,true);
        for(String change:List.of("UPDATE economy_task_benchmark_sponsorship SET authority_sha=REPEAT('0',64)",
                "UPDATE economy_task_benchmark_sponsorship SET authority_json=JSON_SET(authority_json,'$.agentId','wrong'),authority_sha=SHA2(authority_json,256)",
                "UPDATE economy_task_benchmark_sponsorship SET authority_json=JSON_SET(authority_json,'$.manifestSha256',REPEAT('0',64)),authority_sha=SHA2(authority_json,256)")){
            tx.executeWithoutResult(st->{jdbc.update(change);org.mockito.Mockito.clearInvocations(w.decoder());
                assertThrows(IllegalStateException.class,()->measureScoped(w.source(),w.decoder()));org.mockito.Mockito.verifyNoInteractions(w.decoder());st.setRollbackOnly();});
        }
        assertEquals(ConfirmedSampleEvidence.Verdict.MATCHED,measureScoped(w.source(),w.decoder()).assessment().verdict());
    }
    @Test void scopedSponsoredAuthorityChangeBetweenReadAndPublishRejectsStaleGeneration(){
        var w=scopedWorkerFixture(1,true);var json=new ObjectMapper();
        var publisher=new GroupBaselinePublisher(maintenance,json){
            @Override Prepared calculateScoped(cn.jia.economy.mapper.GroupBaselineMaintenanceMapper.Work work,String claim,cn.jia.economy.pricing.GroupTariffSnapshot.Scoped tariff,List<ScopedEligible> members,int support,String approval){
                var prepared=super.calculateScoped(work,claim,tariff,members,support,approval);
                jdbc.update("UPDATE economy_task_benchmark_sponsorship SET state='CANCELLED'");return prepared;
            }
        };
        scopedWorkerService(publisher,TaskPricingTestFixture.service(jdbc)).runDue(SCOPE.tenantId(),SCOPE.clientId(),w.decoder());
        assertEquals(0,count("economy_budget_group_revision"));assertInstanceOf(GroupBudgetQuotes.Fixed.class,scopedDecision(w));
        scopedWorkerService(new GroupBaselinePublisher(maintenance,json),TaskPricingTestFixture.service(jdbc)).runDue(SCOPE.tenantId(),SCOPE.clientId(),w.decoder());
        assertEquals("WAIT_INPUT",jdbc.queryForObject("SELECT status FROM economy_budget_group_head",String.class));
    }

    @Test void scopedWorkerPublishesDistinctModelCostsAndReviewWithdrawalRevokesNewOffers(){
        var w=scopedWorkerFixture(1);var prices=org.mockito.Mockito.spy(TaskPricingTestFixture.service(jdbc));var service=scopedWorkerService(new GroupBaselinePublisher(maintenance,new ObjectMapper()),prices);
        service.runDue(SCOPE.tenantId(),SCOPE.clientId(),w.decoder());var mean=assertInstanceOf(GroupBudgetQuotes.Mean.class,scopedDecision(w));
        assertEquals(60,mean.amountWen());assertEquals(1,mean.pin().n());assertEquals(0,new BigDecimal("0.6").compareTo(mean.pin().sumYuan()));
        assertEquals(1,count("economy_budget_group_revision"));assertEquals(1,count("economy_budget_group_build_evidence"));
        assertEquals(99,wallet());assertEquals(1,jdbc.queryForObject("SELECT amount_wen FROM economy_task_charge",Long.class));
        org.mockito.Mockito.verify(prices).freezeScoped(org.mockito.ArgumentMatchers.eq(SCOPE),org.mockito.ArgumentMatchers.eq(w.study()),org.mockito.ArgumentMatchers.argThat(map->map.size()==2));
        org.mockito.Mockito.verify(prices,org.mockito.Mockito.never()).freeze(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        service.runDue(SCOPE.tenantId(),SCOPE.clientId(),w.decoder());assertEquals(1,count("economy_budget_group_revision"));
        org.mockito.Mockito.verify(w.decoder(),org.mockito.Mockito.times(2)).openScoped(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        jdbc.update("UPDATE economy_task_benchmark_review SET withdrawn=TRUE");assertInstanceOf(GroupBudgetQuotes.Fixed.class,scopedDecision(w));
        service.runDue(SCOPE.tenantId(),SCOPE.clientId(),w.decoder());assertEquals("WAIT_INPUT",jdbc.queryForObject("SELECT status FROM economy_budget_group_head",String.class));
        assertEquals(1,count("economy_budget_group_revision"));assertEquals(1,jdbc.queryForObject("SELECT amount_wen FROM economy_task_charge",Long.class));
    }
    @Test void scopedWorkerRespectsApprovedSupportWithoutReadingAnyUsage(){
        var w=scopedWorkerFixture(3);var prices=org.mockito.Mockito.spy(TaskPricingTestFixture.service(jdbc));
        scopedWorkerService(new GroupBaselinePublisher(maintenance,new ObjectMapper()),prices).runDue(SCOPE.tenantId(),SCOPE.clientId(),w.decoder());
        assertEquals("WAIT_INPUT",jdbc.queryForObject("SELECT status FROM economy_budget_group_head",String.class));assertEquals(0,count("economy_budget_group_revision"));
        org.mockito.Mockito.verifyNoInteractions(prices,w.decoder());
    }
    @Test void scopedWorkerMissingMemberNeverPublishesPartialOrZeroCost(){
        var w=scopedWorkerFixture(1);jdbc.update("UPDATE economy_usage_capture SET projection_revision=0 WHERE execution_id='execution-2'");
        scopedWorkerService(new GroupBaselinePublisher(maintenance,new ObjectMapper()),TaskPricingTestFixture.service(jdbc)).runDue(SCOPE.tenantId(),SCOPE.clientId(),w.decoder());
        assertEquals("WAIT_INPUT",jdbc.queryForObject("SELECT status FROM economy_budget_group_head",String.class));assertEquals(0,count("economy_budget_group_revision"));assertInstanceOf(GroupBudgetQuotes.Fixed.class,scopedDecision(w));
    }
    @Test void scopedWorkerCommittedPriceChangeRejectsOldBookAndRepricesNextGeneration(){
        var w=scopedWorkerFixture(1);var replacement=new ModelTokenPrice("test-channel",route("0.1"),"standard","2","fixture",Map.of(ModelTokenPrice.Category.INPUT,new BigDecimal("0.4")),null,Map.of());
        TaskPricingTestFixture.insertVersion(jdbc,SCOPE,replacement);
        var publisher=new GroupBaselinePublisher(maintenance,new ObjectMapper()){
            @Override Prepared calculateScoped(cn.jia.economy.mapper.GroupBaselineMaintenanceMapper.Work work,String claim,cn.jia.economy.pricing.GroupTariffSnapshot.Scoped tariff,List<ScopedEligible> members,int support,String approval){
                var prepared=super.calculateScoped(work,claim,tariff,members,support,approval);assertEquals(0,new BigDecimal("0.6").compareTo(prepared.total()));
                jdbc.update("UPDATE economy_model_price_current SET price_version='2' WHERE model=?",route("0.1"));return prepared;
            }
        };
        scopedWorkerService(publisher,TaskPricingTestFixture.service(jdbc)).runDue(SCOPE.tenantId(),SCOPE.clientId(),w.decoder());
        assertEquals(0,count("economy_budget_group_revision"));assertEquals("DIRTY",jdbc.queryForObject("SELECT status FROM economy_budget_group_head",String.class));
        scopedWorkerService(new GroupBaselinePublisher(maintenance,new ObjectMapper()),TaskPricingTestFixture.service(jdbc)).runDue(SCOPE.tenantId(),SCOPE.clientId(),w.decoder());
        assertEquals(90,assertInstanceOf(GroupBudgetQuotes.Mean.class,scopedDecision(w)).amountWen());assertEquals(1,count("economy_budget_group_revision"));
    }

    @Test void scopedPaidAdmissionCreatesExactlyOneQuoteLinkedSampleAndNeverReenrollsReplay(){
        var c=scopedPaidCase(cn.jia.economy.task.ScopedBenchmarkManifest.Purpose.TRAIN,false);
        assertEquals(0,count("economy_task_cost_sample"));var held=reservePaid(c);
        assertEquals(State.HELD,held.state());assertEquals(99,wallet());assertEquals(1,count("economy_task_cost_sample"));
        var row=jdbc.queryForMap("SELECT * FROM economy_task_cost_sample");
        assertEquals(c.quoteId(),row.get("quote_id"));assertEquals("case-1",row.get("benchmark_case_id"));
        assertNotEquals(c.sha(),row.get("sample_id"));assertNull(row.get("history_group_sha256"));
        assertEquals(c.manifest().scopePin().scopeSha256(),row.get("scope_sha256"));
        assertEquals(c.manifest().group().scopeShapeSha(),row.get("scope_shape_sha256"));
        assertEquals(c.manifest().groupSha(),row.get("scoped_group_sha256"));assertEquals(c.manifest().policy().sha(),row.get("scoped_policy_sha256"));
        assertEquals("WAITING_EVIDENCE",row.get("sample_state"));assertNull(inTask(TASK,()->billing.heldInitialCommitment(TASK,"agent")));
        assertThrows(TaskBenchmarkAdmission.NotAdmitted.class,()->inTask(TASK,()->preparePaid(c)));
        jdbc.update("UPDATE economy_task_benchmark_case SET retired=TRUE");
        assertEquals(held,inTask(TASK,()->billing.reserve(TASK,c.quoteId(),c.quote().binding(),"payer-1")));
        assertEquals(1,count("economy_task_cost_sample"));assertEquals(1,count("economy_transaction"));
    }
    @Test void scopedPaidHoldoutZeroKeepsPurposeAndDoesNotInventExecutionOrTrainingApproval(){
        var c=scopedPaidCase(cn.jia.economy.task.ScopedBenchmarkManifest.Purpose.HOLDOUT,true);reservePaid(c);
        assertEquals(100,wallet());assertEquals(0,count("economy_transaction"));assertEquals(1,count("economy_task_charge"));assertEquals(1,count("economy_task_cost_sample"));
        assertEquals("HOLDOUT",jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(manifest_json,'$.purpose')) FROM economy_task_benchmark_case",String.class));
        assertNull(inTask(TASK,()->billing.heldInitialCommitment(TASK,"agent")));
        assertEquals(State.CAPTURED,inTask(TASK,()->billing.capture(TASK)).state());
    }
    @Test void scopedPaidInsufficientFundsDoesNotAdmitSampleOrLeaveCharge(){
        var c=scopedPaidCase(cn.jia.economy.task.ScopedBenchmarkManifest.Purpose.TRAIN,false);
        jdbc.update("UPDATE economy_account SET balance_wen=0 WHERE account_id='wallet'");
        assertThrows(EconomyPostingException.class,()->reservePaid(c));
        assertEquals(0,count("economy_task_charge"));assertEquals(0,count("economy_task_cost_sample"));assertEquals(0,count("economy_transaction"));
        jdbc.update("UPDATE economy_account SET balance_wen=100 WHERE account_id='wallet'");reservePaid(c);assertEquals(1,count("economy_task_cost_sample"));
    }
    @Test void scopedPaidAssignmentFailureRollsBackSampleAndWalletTogether(){
        var c=scopedPaidCase(cn.jia.economy.task.ScopedBenchmarkManifest.Purpose.TRAIN,false);
        assertThrows(IllegalStateException.class,()->inTask(TASK,()->{
            billing.reserve(TASK,c.quoteId(),c.quote().binding(),"payer-1",preparePaid(c));
            jdbc.update("UPDATE test_task_root SET state='ASSIGNED' WHERE id='task-1'");throw new IllegalStateException("assignment failed");
        }));
        assertEquals("OPEN",taskState());assertEquals(100,wallet());assertEquals(0,count("economy_task_cost_sample"));assertEquals(0,count("economy_task_charge"));assertEquals(0,count("economy_transaction"));
        reservePaid(c);assertEquals(1,count("economy_task_cost_sample"));
    }
    @Test void scopedPaidEnrollmentCannotEscapeTransactionOrUseOtherScopeOrCandidate(){
        var c=scopedPaidCase(cn.jia.economy.task.ScopedBenchmarkManifest.Purpose.TRAIN,false);
        assertThrows(IllegalTransactionStateException.class,()->preparePaid(c));
        var escaped=inTask(TASK,()->preparePaid(c));
        assertThrows(TaskBenchmarkAdmission.NotAdmitted.class,()->inTask(TASK,()->billing.reserve(TASK,c.quoteId(),c.quote().binding(),"payer-1",escaped)));
        var otherPin=new ScopedTaskQuoteSnapshot.ScopePin(1,"1".repeat(64),"9".repeat(64),"2".repeat(64));
        assertThrows(TaskBenchmarkAdmission.NotAdmitted.class,()->inTask(TASK,()->scopedAdmissions.prepareScopedPaid(TASK,"case-1",c.sha(),"agent",new TaskBenchmarkAdmission.ObservedScopeGroup(otherPin,c.manifest().group()))));
        assertThrows(TaskBenchmarkAdmission.NotAdmitted.class,()->inTask(TASK,()->scopedAdmissions.prepareScopedPaid(TASK,"case-1",c.sha(),"other-agent",c.observed())));
        assertEquals(100,wallet());assertEquals(0,count("economy_task_charge"));assertEquals(0,count("economy_task_cost_sample"));
    }
    @Test void scopedPaidRefusesLateOrdinaryEnrollmentAndCorruptOrRetiredOriginal(){
        var c=scopedPaidCase(cn.jia.economy.task.ScopedBenchmarkManifest.Purpose.TRAIN,false);
        String original=jdbc.queryForObject("SELECT manifest_json FROM economy_task_benchmark_case",String.class);
        jdbc.update("UPDATE economy_task_benchmark_case SET manifest_json='{}'");
        assertThrows(IllegalStateException.class,()->inTask(TASK,()->preparePaid(c)));
        jdbc.update("UPDATE economy_task_benchmark_case SET manifest_json=?,retired=TRUE",original);
        assertThrows(TaskBenchmarkAdmission.NotAdmitted.class,()->inTask(TASK,()->preparePaid(c)));
        jdbc.update("UPDATE economy_task_benchmark_case SET retired=FALSE");
        inTask(TASK,()->billing.reserve(TASK,c.quoteId(),c.quote().binding(),"payer-1"));
        assertThrows(TaskBenchmarkAdmission.NotAdmitted.class,()->inTask(TASK,()->preparePaid(c)));
        assertEquals(1,count("economy_task_cost_sample"));assertNull(jdbc.queryForObject("SELECT benchmark_case_id FROM economy_task_cost_sample",String.class));
        assertNull(jdbc.queryForObject("SELECT scoped_group_sha256 FROM economy_task_cost_sample",String.class));
    }
    @Test void scopedSampleMigrationResumesWithoutBackfillingOrdinarySamplesAndRejectsDrift(){
        usageSchema();var quote=scopedSnapshot("agent",false,false);
        String id=inTask(TASK,()->billing.storeOffersBatch(TASK,"payer-1",List.of(new TaskBillingService.ScopedWrite(quote)))).getFirst().quoteId();
        inTask(TASK,()->billing.reserve(TASK,id,quote.binding(),"payer-1"));
        jdbc.execute("ALTER TABLE economy_task_cost_sample ADD COLUMN scope_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL FIRST");
        var migration=new cn.jia.economy.config.ScopedCostSampleSchemaInitializer(jdbc);migration.afterPropertiesSet();migration.afterPropertiesSet();
        assertEquals(1,count("economy_task_cost_sample"));assertEquals(99,wallet());
        assertNull(jdbc.queryForObject("SELECT scope_sha256 FROM economy_task_cost_sample",String.class));
        jdbc.execute("ALTER TABLE economy_task_cost_sample DROP COLUMN scope_shape_sha256, MODIFY scoped_group_sha256 CHAR(63) CHARACTER SET ascii COLLATE ascii_bin NULL");
        assertThrows(IllegalStateException.class,migration::afterPropertiesSet);
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='economy_task_cost_sample' AND column_name='scope_shape_sha256'",Integer.class));
        assertEquals(1,count("economy_task_cost_sample"));
    }

    private Quote quote(Task task, Binding binding, String yuan) {
        return inTask(task, () -> billing.quote(task, binding, "payer-1", workload(), route(yuan)));
    }
    private Charge reserve(Task task, Quote quote) {
        return inTask(task, () -> billing.reserve(task, quote.quoteId(), BINDING, "payer-1"));
    }
    private <T> T inTask(Task task, Supplier<T> work) {
        return tx.execute(status -> {
            jdbc.queryForObject("SELECT state FROM test_task_root WHERE id=? FOR UPDATE", String.class, task.taskId());
            return work.get();
        });
    }
    private static TaskWorkload workload() {
        return new TaskWorkload("test", 1_000_000, 0, 1, Map.of());
    }
    private static String route(String yuan) {
        return "test-model-" + yuan;
    }
    private static ModelTokenPrice price(String yuan) {
        return new ModelTokenPrice("test-channel", "test-model-" + yuan, "standard", "1", "synthetic-test-price",
                Map.of(ModelTokenPrice.Category.INPUT, new BigDecimal(yuan)), null, Map.of());
    }
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    private long wallet() { return jdbc.queryForObject("SELECT balance_wen FROM economy_account WHERE account_id='wallet'", Long.class); }
    private String taskState() { return jdbc.queryForObject("SELECT state FROM test_task_root WHERE id='task-1'", String.class); }
    private static void assertReason(TaskBillingException.Reason expected, Runnable work) {
        assertEquals(expected, assertThrows(TaskBillingException.class, work::run).reason());
    }
    private static List<Object> race(Callable<?> a, Callable<?> b) throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var go = new CountDownLatch(1);
        try {
            var futures = List.of(a, b).stream().map(call -> pool.submit(() -> {
                ready.countDown();
                if (!go.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test start timeout");
                try { return call.call(); } catch (RuntimeException failure) { return (Object) failure; }
            })).toList();
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            go.countDown();
            return List.of(futures.get(0).get(20, TimeUnit.SECONDS), futures.get(1).get(20, TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); }
    }
}
