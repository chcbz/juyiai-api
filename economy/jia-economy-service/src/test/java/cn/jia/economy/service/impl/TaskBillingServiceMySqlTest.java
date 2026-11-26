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
        installGroupSchema();for(var f:List.of("economy-usage-capture.sql","economy-task-benchmark-case-v1.sql","economy-task-benchmark-closure-v1.sql","economy-task-benchmark-review-v1.sql")){
            new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("db/"+f)).execute(jdbc.getDataSource());}
        var sql=new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("db/economy-group-budget-source-invalidation-v1.sql"));sql.setSeparator("$$");sql.execute(jdbc.getDataSource());
        var approvals=new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("db/economy-group-budget-approval-v1.sql"));approvals.setSeparator("$$");approvals.execute(jdbc.getDataSource());
    }
    private String importTestApproval(Object raw,ObjectMapper json){String body=json.writeValueAsString(raw),sha=GroupPublicationEnrollment.digest(body);jdbc.update("INSERT INTO economy_budget_publish_approval(approval_sha,authority_json) VALUES(?,?)",sha,body);return sha;}
    @SuppressWarnings("unchecked") private <T> T transactional(T target){var proxy=new ProxyFactory(target);proxy.setProxyTargetClass(true);proxy.addAdvice(new TransactionInterceptor(tx.getTransactionManager(),new AnnotationTransactionAttributeSource()));return (T)proxy.getProxy();}
    @Test void approvedStudyRepricesStoredRosterAndWithdrawnStudyImmediatelyStopsNewOffers(){
        installPublicationSchema();var ddl=new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("db/economy-group-study-v1.sql"));ddl.setSeparator("$$");ddl.execute(jdbc.getDataSource());
        String t=SCOPE.tenantId(),c=SCOPE.clientId(),a="a".repeat(64),b="b".repeat(64);var json=new ObjectMapper();var op="edit";
        var buckets=new GroupMeanPricing.Buckets(op,List.of(new GroupMeanPricing.Bucket("one",Map.of(GroupMeanPricing.Dimension.INPUT_FILE_COUNT,new GroupMeanPricing.Range(1,2)))));
        var policy=new GroupMeanPricing.Policy("v","rule","config",buckets,1);var config=new GroupMeanPricing.Configuration("ctx","1","tool","capture",List.of(
            new GroupMeanPricing.Route("MODEL_RESPONSE","INFERENCE","test-channel",route("0.1"),"standard","basis"),new GroupMeanPricing.Route("NATIVE_PROVIDER_CALL",op,"test-channel",route("0.5"),"standard","basis")));
        var g=policy.classify(op,Map.of(GroupMeanPricing.Dimension.INPUT_FILE_COUNT,1L),config).orElseThrow();var key=GroupMeanPricing.key(g);
        var v=new java.util.ArrayList<>(java.util.Collections.nCopies(1024,0f));v.set(0,1f);
        var features=new TaskEstimateFeatures(TaskEstimateFeatures.VERSION,"fixture",a,b,a,new TaskEstimateFeatures.Identity("fixture","model",1024),v,List.of(new TaskEstimateFeatures.File("INPUT","image/png",32,1)),null);
        var manifest=new TaskBenchmarkAdmission.Manifest("benchmark-case-v1",TASK,"case","agent","test","review",new TaskBillingService.MeasuredGroup("ver",a,b,"1",a,"capture"),features,new TaskBenchmarkAdmission.GroupInput(op,config));
        var raw=json.writeValueAsString(manifest);String sample=GroupPublicationEnrollment.digest(raw);
        var review=json.writeValueAsString(new BenchmarkSampleEvidence.Review("benchmark-review-v1","case",sample,"terminal",a,"ACCEPTED","test","artifact"));String rev=GroupPublicationEnrollment.digest(review);
        jdbc.update("INSERT INTO economy_task_benchmark_case(tenant_id,client_id,owner_jiacn,task_id,benchmark_case_id,manifest_json,manifest_sha) VALUES(?,?,?,?,'case',?,?)",t,c,TASK.ownerJiacn(),TASK.taskId(),raw,sample);
        jdbc.update("INSERT INTO economy_task_cost_sample(sample_id,tenant_id,client_id,owner_jiacn,task_id,benchmark_case_id,sample_definition_version,initial_requirement_sha256,initial_input_snapshot_sha256,initial_feature_sha256,lifecycle_state,evidence_revision,sample_state) VALUES(?,?,?,?,?,'case','TASK_LIFECYCLE_V1',?,?,?,'ACCEPTED',1,'MATCHED')",sample,t,c,TASK.ownerJiacn(),TASK.taskId(),a,b,features.digest());
        jdbc.update("INSERT INTO economy_task_benchmark_closure VALUES(?,?,1,1,1)",sample,"terminal");jdbc.update("INSERT INTO economy_task_benchmark_review VALUES(?,?,?,false)",sample,rev,review);
        var enroll=transactional(new GroupPublicationEnrollment(maintenance,json));String approval=importTestApproval(new GroupPublicationEnrollment.Approval("GROUP_AGGREGATE","TRAIN",t,c,key,policy.sha(),TASK.ownerJiacn(),TASK.taskId(),"case",sample,1,sample,rev,"fixture"),json);enroll.register(approval);
        var study=importTestApproval(new GroupStudyPolicy.Receipt("GROUP_HOLDOUT_RELEASE_A",t,c,"fixture only",a,b,policy.sha(),key,"rule","config",1,buckets,g,2,2,BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ZERO,BigDecimal.ZERO),json);jdbc.update("INSERT INTO economy_budget_group_study VALUES(?,?,?,?)",t,c,key,study);
        // The two associated responses are fixture inputs, not provider traffic or an accuracy observation.
        var evidence=org.mockito.Mockito.mock(BenchmarkSampleEvidence.class);var decoder=org.mockito.Mockito.mock(ConfirmedSampleEvidence.Decoder.class);
        var ops=config.routes().stream().map(r->new ConfirmedSampleEvidence.Operation(1,"capture",1,r.kind(),"1",new MeasuredTaskRepricing.Fact(new MeasuredTaskRepricing.Operation(r.kind(),"one"),r.kind(),r.component(),a,new MeasuredTaskRepricing.Tokens("basis",Map.of(ModelTokenPrice.Category.INPUT,1_000_000L))))).toList();
        org.mockito.Mockito.when(evidence.measureReviewed(TASK,"case",sample,"terminal",rev,decoder)).thenReturn(new ConfirmedSampleEvidence.Measured(new ConfirmedSampleEvidence.Assessment(sample,1,"terminal",1,ConfirmedSampleEvidence.Verdict.MATCHED),ops));
        var service=new SharedGroupMaintenanceService(maintenance,transactional(new GroupRosterSnapshot(maintenance)),transactional(new GroupTariffReader(TaskPricingTestFixture.service(jdbc),maintenance)),transactional(new GroupReviewedUsage(maintenance,enroll,originals,evidence)),transactional(new GroupBaselinePublisher(maintenance,json)),json);
        service.runDue(t,c,decoder);var reader=new GroupBudgetQuotesService(baselines);var k=new GroupBudgetQuotes.Key(key);
        assertEquals(60,assertInstanceOf(GroupBudgetQuotes.Mean.class,reader.prepare(SCOPE,java.util.Set.of(k)).get(k)).amountWen());
        assertEquals(study,jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(roster_json,'$.studyApprovalSha')) FROM economy_budget_group_build_evidence",String.class));
        jdbc.update("UPDATE economy_budget_publish_approval SET withdrawn=TRUE WHERE approval_sha=?",study);assertInstanceOf(GroupBudgetQuotes.Fixed.class,reader.prepare(SCOPE,java.util.Set.of(k)).get(k));
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
        var json=new ObjectMapper();var enrollment=new GroupPublicationEnrollment(maintenance,json);
        String t=SCOPE.tenantId(),c=SCOPE.clientId(), sample=GroupPublicationEnrollment.digest("{}"),policy="f".repeat(64),key="d".repeat(64);
        String review=json.writeValueAsString(java.util.Map.of("caseId","case","decision","ACCEPTED","manifestSha",sample));
        String rev=GroupPublicationEnrollment.digest(review);
        jdbc.update("INSERT INTO economy_task_benchmark_case(tenant_id,client_id,owner_jiacn,task_id,benchmark_case_id,manifest_json,manifest_sha) VALUES(?,?,'owner','task','case','{}',?)",t,c,sample);
        jdbc.update("INSERT INTO economy_task_cost_sample(sample_id,tenant_id,client_id,owner_jiacn,task_id,benchmark_case_id,sample_definition_version,initial_requirement_sha256,initial_input_snapshot_sha256,initial_feature_sha256,lifecycle_state,evidence_revision,sample_state) VALUES(?,?,?,'owner','task','case','TASK_LIFECYCLE_V1',?,?,?,'ACCEPTED',1,'MATCHED')",sample,t,c,policy,policy,policy);
        jdbc.update("INSERT INTO economy_task_benchmark_closure VALUES(?,?,1,1,1)",sample,policy);
        jdbc.update("INSERT INTO economy_task_benchmark_review VALUES(?,?,?,false)",sample,rev,review);
        var refusal=GroupPublicationEnrollment.NotApproved.class;
        assertThrows(refusal,()->tx.executeWithoutResult(st->enrollment.register(policy)));
        for(var fields:List.of(List.of("TRAIN_ONLY","TRAIN","owner"),List.of("GROUP_AGGREGATE","HOLDOUT","owner"),List.of("GROUP_AGGREGATE","TRAIN","foreign"),List.of("GROUP_AGGREGATE","TRAIN","owner"))){
            String raw=json.writeValueAsString(new GroupPublicationEnrollment.Approval(fields.get(0),fields.get(1),t,c,key,policy,fields.get(2),"task","case",sample,1,sample,rev,"fixture"));
            String sha=GroupPublicationEnrollment.digest(raw);jdbc.update("INSERT INTO economy_budget_publish_approval(approval_sha,authority_json) VALUES(?,?)",sha,raw);
            Runnable register=()->tx.executeWithoutResult(st->enrollment.register(sha));
            if(!fields.equals(List.of("GROUP_AGGREGATE","TRAIN","owner"))){assertThrows(refusal,register::run);assertEquals(0,count("economy_budget_group_head"));continue;}
            register.run();register.run();assertEquals(1,count("economy_budget_group_member"));assertEquals(1,jdbc.queryForObject("SELECT generation FROM economy_budget_group_head WHERE status='DIRTY' AND active_revision IS NULL",Integer.class));
            jdbc.update("UPDATE economy_budget_publish_approval SET withdrawn=true WHERE approval_sha=?",sha);assertThrows(refusal,register::run);
        }
    }
    @Test void sourceWithdrawalOrEvidenceChangeRevokesAllAndOnlyRegisteredGroupsInSameCommit(){
        installPublicationSchema();
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
