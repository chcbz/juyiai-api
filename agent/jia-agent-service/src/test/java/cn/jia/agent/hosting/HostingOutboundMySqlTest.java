package cn.jia.agent.hosting;

import cn.jia.agent.api.HostingOutboundController;
import cn.jia.agent.config.HostingOutboundSecurityConfiguration;
import cn.jia.agent.mapper.hosting.HostingOutboundStore;
import cn.jia.agent.mapper.hosting.HostingOutboundStore.Grant;
import cn.jia.core.config.ApiEnvelopeAdvice;
import cn.jia.core.config.ApiRequestEnvelopeAdvice;
import cn.jia.core.filter.RequestIdFilter;
import cn.jia.core.security.FixedTenantAdmissionPolicy;
import cn.jia.economy.config.EconomyMySqlTestFixture;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import tools.jackson.databind.json.JsonMapper;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Real InnoDB transactions and production HttpSecurity/MVC/codec; only isolated fixture credentials. */
@EnabledIfEnvironmentVariable(named = "ECO_V0_W02_MYSQL_URL", matches = ".+")
class HostingOutboundMySqlTest {
    static final String TOKEN = "rth1_" + "a".repeat(64);
    static final JsonMapper JSON = JsonMapper.builder().build();
    EconomyMySqlTestFixture mysql;
    EconomyMySqlTestFixture.Database db;
    HostingOutboundStore store;
    HostingOutboundExchangeService service;
    TransactionTemplate tx;
    AnnotationConfigWebApplicationContext security;
    MockMvc mvc;
    Grant grant;
    static final Map<String,Object> SUBJECT = Map.of("tenantId", "0", "clientId", "fixture-client",
            "ownerJiacn", "fixture-owner", "canonicalAgentId", "agt_outbound_new");
    @Configuration(proxyBeanMethods = false) @EnableWebSecurity
    @Import(HostingOutboundSecurityConfiguration.class)
    static class Security { }

    @BeforeEach void setup() {
        mysql = new EconomyMySqlTestFixture(); mysql.start(); db = mysql.newDatabase("outbound");
        new ResourceDatabasePopulator(new ClassPathResource("db/agent-hosting-outbound.sql")).execute(db.dataSource());
        var manager = new DataSourceTransactionManager(db.dataSource()); tx = new TransactionTemplate(manager);
        security = new AnnotationConfigWebApplicationContext(); security.setServletContext(new MockServletContext());
        security.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("fixture", Map.of("agent.hosting-outbound.enabled", "true")));
        security.addBeanFactoryPostProcessor(factory -> {
            factory.registerSingleton("jdbc", db.jdbc()); factory.registerSingleton("manager", manager);
            factory.registerSingleton("scope", new FixedTenantAdmissionPolicy("0", "fixture-client"));
        });
        security.register(Security.class, cn.jia.agent.config.HostingOutboundConfiguration.class); security.refresh();
        store = security.getBean(HostingOutboundStore.class); service = security.getBean(HostingOutboundExchangeService.class);
        grant = new Grant("0", "fixture-client", "hca_fixture", "fixture-host", "fixture-owner", "agt_outbound_new",
                "SETUP", HostingOutboundExchangeService.tokenHash(TOKEN), "ACTIVE");
        tx.executeWithoutResult(s -> service.register(grant, 1));
        mvc = MockMvcBuilders.standaloneSetup(new HostingOutboundController(service))
                .setControllerAdvice(new ApiEnvelopeAdvice(), new ApiRequestEnvelopeAdvice())
                .addFilters(new RequestIdFilter(), security.getBean(FilterChainProxy.class)).build();
    }
    @AfterEach void close() { if (security != null) security.close(); if (mysql != null) mysql.close(); }
    static Map<String,Object> command(String id) {
        return Map.of("protocolVersion", 1, "requestId", "request-fixture", "messageType", "hosting.inventory", "messageId", id, "data", SUBJECT);
    }
    static Map<String,Object> result(String command, String id) {
        var data = new LinkedHashMap<String,Object>(SUBJECT); data.put("hostId", "fixture-host");
        return Map.of("protocolVersion", 1, "requestId", "request-fixture", "messageType", "hosting.inventory.result",
                "messageId", id, "correlationId", command, "code", "E0", "msg", "ok", "data", data);
    }
    static Map<String,Object> changed(Map<String,Object> map, String key, Object value) {
        var copy = new LinkedHashMap<>(map); copy.put(key, value); return copy;
    }
    void enqueue(String id) { tx.executeWithoutResult(s -> service.enqueue("0", "fixture-client", "hca_fixture", command(id), 2)); }
    HostingOutboundExchangeService.Reply poll(Map<String,Object> result) {
        return service.exchange(TOKEN, new HostingOutboundExchangeService.Request("hca_fixture", "fixture-host", "fixture-instance-1", result), 3);
    }
    int completed() { return db.jdbc().queryForObject("SELECT count(*) FROM agent_hosting_exchange WHERE completed_at IS NOT NULL", Integer.class); }
    String body(Map<String,Object> result) {
        var data = new LinkedHashMap<String,Object>(); data.put("authorizationId", "hca_fixture"); data.put("hostId", "fixture-host");
        data.put("runtimeInstanceId", "fixture-instance-1"); data.put("result", result);
        return JSON.writeValueAsString(Map.of("protocolVersion", 1, "requestId", "http-request-fixture", "data", data));
    }
    int http(String token, String body) throws Exception {
        return mvc.perform(post(HostingOutboundExchangeService.PATH).header("Authorization", token)
                .contentType("application/json").content(body)).andReturn().getResponse().getStatus();
    }

    @Test void hashesOnlyAndBusinessTransactionRequired() {
        assertArrayEquals(HostingOutboundExchangeService.tokenHash(TOKEN), db.jdbc().queryForObject("SELECT token_hash FROM agent_hosting_authorization", byte[].class));
        assertFalse(grant.toString().contains(TOKEN));
        assertThrows(IllegalStateException.class, () -> service.register(grant, 1));
        assertThrows(IllegalStateException.class, () -> service.enqueue("0", "fixture-client", "hca_fixture", command("one"), 2));
        assertThrows(IllegalStateException.class, () -> service.revoke("0", "fixture-client", "hca_fixture", 2));
        byte[] hash = grant.tokenHash(); hash[0] ^= 1; assertFalse(Arrays.equals(hash, grant.tokenHash()));
    }
    @Test void fifoReplayAndLostAcknowledgmentAreIdempotent() {
        enqueue("one"); enqueue("two"); enqueue("one");
        assertEquals(command("one"), poll(null).command());
        assertThrows(HostingRentApplicationException.class, () -> poll(result("two", "r2")));
        assertEquals(0, completed());
        var r = result("one", "r1");
        assertEquals("r1", poll(r).acknowledgedMessageId());
        assertEquals(command("two"), poll(r).command()); assertEquals(1, completed());
        assertNull(poll(result("two", "r2")).command()); assertEquals(2, completed());
        assertEquals("r1", poll(r).acknowledgedMessageId());
    }
    @Test void wrongIdentityTokenHostAndRevocationCannotDeliverOrComplete() {
        enqueue("one"); var request = new HostingOutboundExchangeService.Request("hca_fixture", "fixture-host", "instance", result("one", "r1"));
        assertThrows(HostingRentApplicationException.class, () -> service.exchange("rth1_" + "b".repeat(64), request, 3));
        assertThrows(HostingRentApplicationException.class, () -> service.exchange(TOKEN,
                new HostingOutboundExchangeService.Request("hca_fixture", "other-host", "instance", null), 3));
        for (var policy : List.of(new FixedTenantAdmissionPolicy("other", "fixture-client"), new FixedTenantAdmissionPolicy("0", "other"))) {
            var other = new HostingOutboundExchangeService(store, policy, new DataSourceTransactionManager(db.dataSource()));
            assertThrows(HostingRentApplicationException.class, () -> other.exchange(TOKEN, request, 3));
        }
        tx.executeWithoutResult(s -> service.revoke("0", "fixture-client", "hca_fixture", 4));
        assertThrows(HostingRentApplicationException.class, () -> poll(null));
        assertThrows(HostingRentApplicationException.class, () -> poll(request.result())); assertEquals(0, completed());
    }
    @Test void setupCannotExecuteOrCrossSubjectsAndCommandIdsCannotChange() {
        enqueue("one");
        for (var message : List.of(changed(command("one"), "requestId", "changed"),
                changed(command("two"), "messageType", "hosting.prepare"),
                changed(command("two"), "data", changed(SUBJECT, "canonicalAgentId", "other")))) {
            assertThrows(HostingRentApplicationException.class, () -> tx.executeWithoutResult(s -> service.enqueue("0", "fixture-client", "hca_fixture", message, 2)));
        }
        assertEquals(command("one"), poll(null).command());
    }
    @Test void wrongCorrelationSubjectHostAndChangedReplayDoNotAdvance() {
        enqueue("one"); var r = result("one", "r1");
        for (var invalid : List.of(changed(r, "correlationId", "unknown"), changed(r, "requestId", "wrong"),
                changed(r, "data", changed(SUBJECT, "hostId", "wrong")),
                changed(r, "data", Map.of("tenantId", "0", "clientId", "fixture-client", "ownerJiacn", "other", "canonicalAgentId", "agt_outbound_new", "hostId", "fixture-host")))) {
            assertThrows(HostingRentApplicationException.class, () -> poll(invalid)); assertEquals(0, completed());
        }
        poll(r);
        assertThrows(HostingRentApplicationException.class, () -> poll(changed(r, "messageId", "different-result")));
        assertEquals(1, completed());
    }
    @Test void partitionsApplyBeforeFifoLimitAndSameHostDoesNotShareAuthority() {
        var other = new Grant("0", "fixture-client", "other", "fixture-host", "other-owner", "other-agent", "SETUP",
                HostingOutboundExchangeService.tokenHash("rth1_" + "b".repeat(64)), "ACTIVE");
        tx.executeWithoutResult(s -> { service.register(other, 1); service.enqueue("0", "fixture-client", "other",
                changed(command("one"), "data", Map.of("tenantId", "0", "clientId", "fixture-client", "ownerJiacn", "other-owner", "canonicalAgentId", "other-agent")), 2); });
        assertNull(poll(null).command()); enqueue("one"); assertEquals(command("one"), poll(null).command());
        poll(result("one", "r1")); assertEquals(1, completed());
        assertEquals(1, db.jdbc().queryForObject("SELECT count(*) FROM agent_hosting_exchange WHERE authorization_id='other' AND completed_at IS NULL", Integer.class));
    }
    @Test void rollbackDoesNotPersistReceiptOrIssueAnHttpAck() {
        enqueue("one");
        var failingStore = new HostingOutboundStore(db.jdbc());
        // Force an actual commit failure, after the service has produced its candidate reply.
        var manager = new DataSourceTransactionManager(db.dataSource()) {
            @Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status) {
                throw new org.springframework.transaction.TransactionSystemException("fixture commit failed");
            }
        };
        manager.setRollbackOnCommitFailure(true);
        var failing = new HostingOutboundExchangeService(failingStore, new FixedTenantAdmissionPolicy("0", "fixture-client"), manager);
        assertThrows(org.springframework.transaction.TransactionSystemException.class, () -> failing.exchange(TOKEN,
                new HostingOutboundExchangeService.Request("hca_fixture", "fixture-host", "instance", result("one", "r1")), 3));
        assertEquals(0, completed()); assertEquals(command("one"), poll(null).command());
    }
    @Test void concurrentDuplicateResultsCompleteOnce() throws Exception {
        enqueue("one"); var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<HostingOutboundExchangeService.Reply> call = () -> { start.await(); return poll(result("one", "r1")); };
            var a = pool.submit(call); var b = pool.submit(call); start.countDown();
            assertEquals("r1", a.get().acknowledgedMessageId()); assertEquals("r1", b.get().acknowledgedMessageId());
        }
        assertEquals(1, completed());
    }
    @Test void revokeWinsAgainstWaitingPollAndReceipt() throws Exception {
        enqueue("one");
        var started = new CountDownLatch(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var futures = new ArrayList<Future<HostingOutboundExchangeService.Reply>>();
            tx.executeWithoutResult(status -> {
                store.lock("0", "fixture-client", "hca_fixture");
                futures.add(pool.submit(() -> { started.countDown(); return poll(null); }));
                futures.add(pool.submit(() -> { started.countDown(); return poll(result("one", "r1")); }));
                try { started.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                service.revoke("0", "fixture-client", "hca_fixture", 4);
            });
            for (var future : futures) assertInstanceOf(HostingRentApplicationException.class,
                    assertThrows(ExecutionException.class, future::get).getCause());
        }
        assertEquals(0, completed());
    }
    @Test void reusedResultIdForDifferentCommandIsPermanentConflict() throws Exception {
        enqueue("one"); enqueue("two"); poll(result("one", "same-result"));
        assertEquals(409, http("RuntimeHosting " + TOKEN, body(result("two", "same-result"))));
        assertEquals(1, completed()); assertEquals(command("two"), poll(null).command());
    }
    @Test void disabledFeatureNeedsNoDatabaseAndStillRejectsCredentialFallback() throws Exception {
        try (var context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.register(Security.class, cn.jia.agent.config.HostingOutboundConfiguration.class, HostingOutboundController.class);
            context.refresh();
            assertEquals(0, context.getBeansOfType(HostingOutboundStore.class).size());
            assertEquals(0, context.getBeansOfType(HostingOutboundExchangeService.class).size());
            assertEquals(0, context.getBeansOfType(HostingOutboundController.class).size());
            var disabled = MockMvcBuilders.standaloneSetup(new Object()).addFilters(new RequestIdFilter(), context.getBean(FilterChainProxy.class)).build();
            assertEquals(403, disabled.perform(post("/user/profile").header("Authorization", "RuntimeHosting " + TOKEN))
                    .andReturn().getResponse().getStatus());
        }
    }
    @Test void mvcEnvelopePreservesMandatoryNullsAndRejectsMalformedWire() throws Exception {
        var response = mvc.perform(post(HostingOutboundExchangeService.PATH).header("Authorization", "RuntimeHosting " + TOKEN)
                .contentType("application/json").content(body(null))).andReturn().getResponse();
        assertEquals(200, response.getStatus(), response.getContentAsString());
        var data = JSON.readTree(response.getContentAsString()).get("data");
        assertTrue(data.has("command")); assertTrue(data.get("command").isNull());
        assertTrue(data.has("acknowledgedMessageId")); assertTrue(data.get("acknowledgedMessageId").isNull());
        assertTrue(response.getHeader("Cache-Control").contains("no-store"));
        assertEquals("http-request-fixture", response.getHeader("X-Request-Id"));
        assertEquals(400, http("RuntimeHosting " + TOKEN, body(null).replace("\"result\":null", "\"result\":null,\"result\":null")));
        assertEquals(400, http("RuntimeHosting " + TOKEN, body(null) + "{}"));
        assertEquals(400, http("RuntimeHosting " + TOKEN, "{}"));
        assertEquals(200, http("RuntimeHosting " + TOKEN, body(null).replace("\"result\":null", "\"result\":null,\"futureHint\":true")));
    }
    @Test void actualSecurityChainRejectsCredentialConfusionAndAlternateRoutes() throws Exception {
        for (String auth : List.of("Bearer " + TOKEN, "AgentRuntime " + TOKEN, "RuntimeHosting " + TOKEN + " ", "RuntimeHosting invalid"))
            assertEquals(403, http(auth, body(null)));
        for (var req : List.of(post("/agent/runtime/v1/sessions"), post("/user/profile"), get(HostingOutboundExchangeService.PATH),
                post(HostingOutboundExchangeService.PATH + "?token=x"), post(HostingOutboundExchangeService.PATH).header("Origin", "https://invalid.example"),
                post(HostingOutboundExchangeService.PATH).header("X-Agent-Id", "other"),
                post(HostingOutboundExchangeService.PATH).header("Authorization", "Bearer other"))) {
            assertEquals(403, mvc.perform(req.header("Authorization", "RuntimeHosting " + TOKEN)
                    .contentType("application/json").content(body(null))).andReturn().getResponse().getStatus());
        }
    }
    static final HostingRentHttp.Actor ACTOR = new HostingRentHttp.Actor("fixture-login", "0", "fixture-client", "fixture-owner");
    static final String SETUP_TOKEN = "rth1_" + "c".repeat(64);
    static final String KEY = "00000000-0000-4000-8000-000000000010";
    static Map<String,String> setupBody() { return Map.of("mode", "local", "hostId", "fixture-host", "tokenSha256",
            java.util.HexFormat.of().formatHex(HostingOutboundExchangeService.tokenHash(SETUP_TOKEN)), "controlTokenSha256",
            java.util.HexFormat.of().formatHex(HostingOutboundExchangeService.tokenHash("rth1_" + "d".repeat(64)))); }
    record Invitation(HostingRentApplicationService app, HostingRentOwnerResolver owners,
            cn.jia.agent.mapper.AgentHostingRentBindingMapper roots, cn.jia.economy.service.EconomyPostingService posting,
            cn.jia.agent.dao.AgentPersonaBindingDao bindings, ManagedHostingProvisioner oldProvider) { }
    Invitation invitation() throws Exception {
        new ResourceDatabasePopulator(new ClassPathResource("db/economy-v0-foundation.sql"),
                new ClassPathResource("db/economy-v0-hosting-rent.sql")).execute(db.dataSource());
        var config = new com.baomidou.mybatisplus.core.MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
        config.addMapper(cn.jia.economy.mapper.EconomyHostingRentMapper.class); config.addMapper(cn.jia.economy.mapper.EconomyLedgerMapper.class);
        var factory = new com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean(); factory.setDataSource(db.dataSource()); factory.setConfiguration(config);
        var template = new org.mybatis.spring.SqlSessionTemplate(factory.getObject());
        var rent = template.getMapper(cn.jia.economy.mapper.EconomyHostingRentMapper.class);
        var posting = mock(cn.jia.economy.service.EconomyPostingService.class);
        var manager = new DataSourceTransactionManager(db.dataSource());
        var ledger = new cn.jia.economy.service.impl.HostingRentLedgerServiceImpl(rent,
                template.getMapper(cn.jia.economy.mapper.EconomyLedgerMapper.class), posting, manager);
        var owners = mock(HostingRentOwnerResolver.class); when(owners.requireOwner(any())).thenAnswer(call -> ((HostingRentHttp.Actor)call.getArgument(0)).ownerJiacn());
        var roots = mock(cn.jia.agent.mapper.AgentHostingRentBindingMapper.class);
        when(roots.lockPersona("wuyong")).thenReturn(new cn.jia.agent.entity.AgentPersonaEntity().setPersonaCode("wuyong").setActive(true).setSystemAgent(false));
        var bindings = mock(cn.jia.agent.dao.AgentPersonaBindingDao.class);
        var providers = mock(org.springframework.beans.factory.ObjectProvider.class);
        var provider = mock(ManagedHostingProvisioner.class); when(providers.getIfAvailable()).thenReturn(provider);
        var app = new HostingRentApplicationService(new cn.jia.agent.config.AgentHostingRentProperties(true, "1", "500", "2592000", "1", "50"),
                new cn.jia.economy.config.EconomyPreviewGate(new cn.jia.economy.config.EconomyPreviewProperties(true,
                        List.of(new cn.jia.economy.config.EconomyPreviewProperties.AllowedScope("0", "fixture-client")))), owners, rent,
                ledger, mock(cn.jia.economy.hosting.HostingRentRenewalService.class), roots, bindings, mock(cn.jia.agent.dao.AgentRuntimeDao.class),
                mock(cn.jia.agent.service.AgentIdentityService.class), providers, mock(HostingRentReconciler.class), manager, true);
        app.setHostingSetups(security.getBean(HostingSetupService.class));
        return new Invitation(app, owners, roots, posting, bindings, provider);
    }
    HostingOutboundExchangeService.Reply setupPoll(HostingSetupService.View target, Map<String,Object> result) {
        return service.exchange(SETUP_TOKEN, new HostingOutboundExchangeService.Request(target.setupId(), "fixture-host", "fixture-instance-1", result), 5);
    }
    Map<String,Object> setupResult(Map<String,Object> command, Map<String,Object> additions) {
        @SuppressWarnings("unchecked") var subject = (Map<String,Object>)command.get("data");
        var data = new LinkedHashMap<>(subject); data.put("hostId", "fixture-host"); data.putAll(additions);
        return Map.of("protocolVersion", 1, "requestId", command.get("requestId"), "messageType", command.get("messageType") + ".result",
                "messageId", "result-" + command.get("messageId"), "correlationId", command.get("messageId"), "code", "E0", "msg", "ok", "data", data);
    }
    void deliverInventory(HostingSetupService.View target, boolean actualTarget, boolean corruptDigest) throws Exception {
        var command = setupPoll(target, null).command();
        var skills = new ArrayList<Map<String,Object>>();
        for (String name : List.of("alpha", "beta")) {
            var row = new LinkedHashMap<String,Object>(); row.put("skillKey", name); row.put("source", "NATIVE"); row.put("revision", "d".repeat(64));
            row.put("enabled", name.equals("alpha")); row.put("installation", null); skills.add(row);
        }
        var data = new LinkedHashMap<String,Object>(); data.put("inventoryVersion", "1"); data.put("installedSkillCount", "2"); data.put("skills", skills);
        data.put("inventorySha256", corruptDigest ? "f".repeat(64) : java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(skills))));
        if (actualTarget) data.put("inventorySource", "TARGET");
        while (command != null) {
            command = setupPoll(target, setupResult(command, "hosting.capabilities".equals(command.get("messageType")) ? Map.of("available", true) : "hosting.target".equals(command.get("messageType")) ? Map.of("outcome", "TARGET_READY") : data)).command();
        }
    }
    @Test void setupCreatesOnlyRestrictedAuthorityAndPublicReplayMetadata() throws Exception {
        var f = invitation(); var created = f.app().startSetup(ACTOR, "wuyong", KEY, setupBody());
        assertEquals("WAITING_INVENTORY", created.status()); assertEquals("SETUP", created.authorization().purpose());
        assertEquals(created, f.app().startSetup(ACTOR, "wuyong", KEY, setupBody()));
        assertFalse(JSON.writeValueAsString(created).contains(SETUP_TOKEN));
        assertEquals(1, db.jdbc().queryForObject("SELECT count(*) FROM agent_hosting_setup", Integer.class));
        assertEquals(3, db.jdbc().queryForObject("SELECT count(*) FROM agent_hosting_exchange WHERE authorization_id=?", Integer.class, created.setupId()));
        assertEquals(0, db.jdbc().queryForObject("SELECT count(*) FROM economy_hosting_lease", Integer.class));
        verifyNoInteractions(f.bindings(), f.posting(), f.oldProvider());
        var changed = new HashMap<>(setupBody()); changed.put("hostId", "other-host");
        assertEquals(409, assertThrows(HostingRentApplicationException.class, () -> f.app().startSetup(ACTOR, "wuyong", KEY, changed)).status());
    }
    @Test void realQuoteUsesTargetSkillCountIncludingDisabledAndNeverCallsOldProvider() throws Exception {
        var f = invitation(); var setup = f.app().startSetup(ACTOR, "wuyong", KEY, setupBody());
        var request = Map.of("purpose", "INITIAL", "setupId", setup.setupId());
        String quoteKey = UUID.randomUUID().toString();
        assertEquals("HOSTING_SETUP_INVENTORY_PENDING", assertThrows(HostingRentApplicationException.class,
                () -> f.app().quote(ACTOR, "wuyong", quoteKey, request)).code());
        assertEquals(0, db.jdbc().queryForObject("SELECT count(*) FROM economy_hosting_rent_quote", Integer.class));
        deliverInventory(setup, true, false);
        assertEquals("INVENTORY_READY", f.app().lookupSetup(ACTOR, "wuyong", setup.setupId()).status());
        var quote = f.app().quote(ACTOR, "wuyong", quoteKey, request);
        assertEquals(setup.agentId(), quote.agentId()); assertEquals("agent-self-hosted", quote.planId());
        assertEquals("2", quote.initialSkillCount()); assertEquals("100", quote.initialSkillAmountWen());
        assertEquals("50", quote.recurringAmountWen()); assertEquals("150", quote.amountWen());
        assertEquals(quote, f.app().quote(ACTOR, "wuyong", quoteKey, request));
        assertEquals(1, db.jdbc().queryForObject("SELECT count(*) FROM agent_hosting_setup_quote", Integer.class));
        assertEquals(1, db.jdbc().queryForObject("SELECT count(*) FROM economy_hosting_rent_quote", Integer.class));
        // A new quote key cannot reuse the completed first observation indefinitely.
        assertEquals("HOSTING_SETUP_INVENTORY_PENDING", assertThrows(HostingRentApplicationException.class,
                () -> f.app().quote(ACTOR, "wuyong", UUID.randomUUID().toString(), request)).code());
        assertEquals(1, db.jdbc().queryForObject("SELECT count(*) FROM economy_hosting_rent_quote", Integer.class));
        verifyNoInteractions(f.oldProvider(), f.bindings(), f.posting());
        var confirm = Map.of("mode", "local", "hostingAction", "INITIAL", "agentId", quote.agentId(), "quoteId", quote.quoteId(),
                "expectedPlanVersion", quote.planVersion(), "expectedAmountWen", quote.amountWen(), "expectedPeriodSeconds", quote.periodSeconds());
        assertEquals("HOSTING_SETUP_INVENTORY_PENDING", assertThrows(HostingRentApplicationException.class,
                () -> f.app().bind(ACTOR, "wuyong", UUID.randomUUID().toString(), confirm)).code());
        assertEquals(0, db.jdbc().queryForObject("SELECT count(*) FROM economy_hosting_provisioning_intent", Integer.class));
        verifyNoInteractions(f.oldProvider(), f.bindings(), f.posting());
    }
    @Test void unknownLegacyOrCorruptInventoryCannotBecomeAZeroQuote() throws Exception {
        var f = invitation(); var setup = f.app().startSetup(ACTOR, "wuyong", KEY, setupBody());
        deliverInventory(setup, false, false);
        assertEquals("WAITING_INVENTORY", f.app().lookupSetup(ACTOR, "wuyong", setup.setupId()).status());
        assertThrows(HostingRentApplicationException.class, () -> f.app().quote(ACTOR, "wuyong", UUID.randomUUID().toString(), Map.of("purpose", "INITIAL", "setupId", setup.setupId())));
        // Invalid digest cannot be used even when a producer asserts a real target observation.
        var storeSetup = security.getBean(cn.jia.agent.mapper.hosting.HostingSetupStore.class);
        var row = tx.execute(s -> storeSetup.find("0", "fixture-client", setup.setupId()));
        assertNotNull(row);
        var resultJson = db.jdbc().queryForObject("SELECT result_json FROM agent_hosting_exchange WHERE authorization_id=? AND message_id='setup-inventory'", String.class, setup.setupId());
        var invalid = HostingOutboundExchangeService.parse(resultJson);
        @SuppressWarnings("unchecked") var fields = new LinkedHashMap<>((Map<String,Object>)invalid.get("data"));
        fields.put("inventorySource", "TARGET"); fields.put("inventorySha256", "f".repeat(64));
        db.jdbc().update("UPDATE agent_hosting_exchange SET result_json=? WHERE authorization_id=? AND message_id='setup-inventory'", JSON.writeValueAsString(changed(invalid, "data", fields)), setup.setupId());
        assertEquals("WAITING_INVENTORY", f.app().lookupSetup(ACTOR, "wuyong", setup.setupId()).status());
        assertEquals(0, db.jdbc().queryForObject("SELECT count(*) FROM economy_hosting_rent_quote", Integer.class));
    }
    @Test void unprovenOwnerAndCrossOwnerCannotCreateReadQuoteOrCancelSetup() throws Exception {
        var f = invitation(); var setup = f.app().startSetup(ACTOR, "wuyong", KEY, setupBody());
        var other = new HostingRentHttp.Actor("other-login", "0", "fixture-client", "other-owner");
        assertThrows(HostingRentApplicationException.class, () -> f.app().lookupSetup(other, "wuyong", setup.setupId()));
        assertThrows(HostingRentApplicationException.class, () -> f.app().cancelSetup(other, "wuyong", setup.setupId()));
        assertThrows(HostingRentApplicationException.class, () -> f.app().quote(other, "wuyong", UUID.randomUUID().toString(), Map.of("purpose", "INITIAL", "setupId", setup.setupId())));
        doThrow(new HostingRentApplicationException(403, "HOSTING_RENT_OWNER_UNPROVEN")).when(f.owners()).requireOwner(ACTOR);
        assertThrows(HostingRentApplicationException.class, () -> f.app().startSetup(ACTOR, "wuyong", UUID.randomUUID().toString(), setupBody()));
        assertEquals(1, db.jdbc().queryForObject("SELECT count(*) FROM agent_hosting_setup", Integer.class));
    }
    @Test void cancelledSetupRevokesPollingWithoutRemoteCleanupOrNewQuote() throws Exception {
        var f = invitation(); var setup = f.app().startSetup(ACTOR, "wuyong", KEY, setupBody());
        deliverInventory(setup, true, false);
        assertEquals("REVOKED", f.app().cancelSetup(ACTOR, "wuyong", setup.setupId()).status());
        assertEquals("REVOKED", f.app().cancelSetup(ACTOR, "wuyong", setup.setupId()).status());
        assertEquals("REVOKED", f.app().lookupSetup(ACTOR, "wuyong", setup.setupId()).status());
        assertThrows(HostingRentApplicationException.class, () -> setupPoll(setup, null));
        assertThrows(HostingRentApplicationException.class, () -> f.app().quote(ACTOR, "wuyong", UUID.randomUUID().toString(), Map.of("purpose", "INITIAL", "setupId", setup.setupId())));
        verifyNoInteractions(f.oldProvider(), f.posting(), f.bindings());
    }
    @Test void managedCatalogCannotBeRelabeledAndSetupRollbackIncludesGrantAndCommands() throws Exception {
        var f = invitation();
        var setups = new HostingSetupService(security.getBean(cn.jia.agent.mapper.hosting.HostingSetupStore.class), store, service,
                new FixedTenantAdmissionPolicy("0", "fixture-client"), new cn.jia.agent.config.HostingSetupProperties(Map.of("fixture-host",
                new cn.jia.agent.config.HostingSetupProperties.ManagedHost("0", "fixture-client", "fixture-owner"))));
        f.app().setHostingSetups(setups);
        assertThrows(HostingRentApplicationException.class, () -> f.app().startSetup(ACTOR, "wuyong", KEY, setupBody()));
        var managed = new HashMap<>(setupBody()); managed.put("mode", "server");
        var created = f.app().startSetup(ACTOR, "wuyong", KEY, managed);
        String quoteKey = UUID.randomUUID().toString(); var quoteRequest = Map.of("purpose", "INITIAL", "setupId", created.setupId());
        assertEquals("HOSTING_SETUP_INVENTORY_PENDING", assertThrows(HostingRentApplicationException.class,
                () -> f.app().quote(ACTOR, "wuyong", quoteKey, quoteRequest)).code());
        deliverInventory(created, true, false);
        var quote = f.app().quote(ACTOR, "wuyong", quoteKey, quoteRequest);
        assertEquals("agent-hosting-preview", quote.planId()); assertEquals("600", quote.amountWen());
        assertEquals("500", quote.recurringAmountWen());
        int grants = db.jdbc().queryForObject("SELECT count(*) FROM agent_hosting_authorization", Integer.class);
        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(status -> {
            setups.create(ACTOR, "wuyong", UUID.randomUUID().toString(), new byte[32], "agt_other", "server", "fixture-host", "e".repeat(64), "f".repeat(64), 5);
            throw new IllegalStateException("fixture rollback");
        }));
        assertEquals(grants, db.jdbc().queryForObject("SELECT count(*) FROM agent_hosting_authorization", Integer.class));
        assertEquals(1, db.jdbc().queryForObject("SELECT count(*) FROM agent_hosting_setup", Integer.class));
    }
    @Test @EnabledIfEnvironmentVariable(named = "CYF_GSS_RUNTIME_TEST_SOURCE", matches = ".+")
    void setupHttpUsesExistingUserBoundaryAndNeverReturnsBearer() throws Exception {
        var f = invitation();
        var userMvc = MockMvcBuilders.standaloneSetup(new cn.jia.agent.api.AgentHostingRentController(f.app()))
                .setControllerAdvice(new ApiEnvelopeAdvice(), new ApiRequestEnvelopeAdvice()).addFilters(new RequestIdFilter(), security.getBean(FilterChainProxy.class)).build();
        var jwt = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("fixture").claim("tenant_id", "0").header("alg", "none")
                .subject("fixture-login").claim("jiacn", "fixture-owner").claim("client_id", "fixture-client").build();
        var auth = new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(jwt); auth.setAuthenticated(true);
        var fixture = JSON.readTree(Files.readString(Path.of(System.getenv("CYF_GSS_RUNTIME_TEST_SOURCE"), "test/fixtures/hosting-activation-v1.json")));
        String body = fixture.get("registrationRequest").toString();
        assertEquals(JSON.valueToTree(setupBody()), fixture.get("registrationRequest").get("data"));
        var response = userMvc.perform(post("/agent/personas/wuyong/hosting-setups").principal(auth).header("Idempotency-Key", KEY)
                .contentType("application/json").content(body)).andReturn().getResponse();
        assertEquals(200, response.getStatus(), response.getContentAsString());
        var actual = JSON.readTree(response.getContentAsString());
        var data = (tools.jackson.databind.node.ObjectNode)actual.get("data");
        String setupId = data.get("setupId").asString(), agentId = data.get("agentId").asString();
        var authorization = (tools.jackson.databind.node.ObjectNode)data.get("authorization");
        assertEquals(setupId, authorization.get("authorizationId").asString());
        assertEquals(agentId, authorization.get("canonicalAgentId").asString());
        // Normalize only server-generated opaque IDs, not the wire shape or authority fields.
        data.put("setupId", "hca_setup_fixture"); data.put("agentId", "agt_setup_fixture");
        authorization.put("authorizationId", "hca_setup_fixture"); authorization.put("canonicalAgentId", "agt_setup_fixture");
        assertEquals(fixture.get("registrationResponse"), actual);
        assertFalse(response.getContentAsString().contains(SETUP_TOKEN)); assertTrue(response.getHeader("Cache-Control").contains("no-store"));
        assertEquals(401, userMvc.perform(post("/agent/personas/wuyong/hosting-setups").header("Idempotency-Key", KEY)
                .contentType("application/json").content(body)).andReturn().getResponse().getStatus());
        assertEquals(403, userMvc.perform(post("/agent/personas/wuyong/hosting-setups").header("Authorization", "RuntimeHosting " + TOKEN)
                .contentType("application/json").content(body)).andReturn().getResponse().getStatus());
    }

    @Test @EnabledIfEnvironmentVariable(named = "CYF_HOSTING_NODE_FIXTURE", matches = ".+")
    void actualNodeWorkerUsesJavaMvcAndMysqlAndReplaysLostAck() throws Exception {
        enqueue("inventory-1");
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var failure = new AtomicReference<Throwable>(); var receipts = new ArrayList<String>();
        server.createContext("/", exchange -> {
            try {
                byte[] bytes = exchange.getRequestBody().readAllBytes();
                var req = request(org.springframework.http.HttpMethod.valueOf(exchange.getRequestMethod()), exchange.getRequestURI()).content(bytes);
                exchange.getRequestHeaders().forEach((k, v) -> req.header(k, v.toArray()));
                var response = mvc.perform(req).andReturn().getResponse();
                assertEquals(200, response.getStatus(), response.getContentAsString());
                var result = JSON.readTree(bytes).get("data").get("result");
                if (!result.isNull()) { receipts.add(result.toString()); if (receipts.size() == 1) return; }
                for (String name : response.getHeaderNames()) exchange.getResponseHeaders().put(name, new ArrayList<>(response.getHeaders(name)));
                byte[] output = response.getContentAsByteArray(); exchange.sendResponseHeaders(response.getStatus(), output.length);
                exchange.getResponseBody().write(output);
            } catch (Throwable error) { failure.compareAndSet(null, error); }
            finally { exchange.close(); }
        });
        Path log = Path.of(System.getenv("CYF_HOSTING_NODE_LOG")); Process node = null;
        try {
            server.start();
            node = new ProcessBuilder(System.getenv("CYF_HOSTING_NODE"), System.getenv("CYF_HOSTING_NODE_FIXTURE"),
                    "http://127.0.0.1:" + server.getAddress().getPort()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            assertEquals(0, node.waitFor(), () -> { try { return Files.readString(log); } catch (Exception e) { return e.toString(); } });
            assertNull(failure.get()); assertEquals(2, receipts.size()); assertEquals(receipts.get(0), receipts.get(1)); assertEquals(1, completed());
        } finally { if (node != null && node.isAlive()) { node.destroy(); node.waitFor(); } server.stop(0); }
    }
    @Test void activationIssuesOnlyExactSeparateControlAndReleaseRevokesBothWithoutHostIo() throws Exception {
        var f = invitation(); var view = f.app().startSetup(ACTOR, "wuyong", KEY, setupBody());
        var setups = security.getBean(HostingSetupService.class);
        assertNull(setupPoll(view, null).controlAuthorization());
        tx.executeWithoutResult(status -> {
            var setup = setups.find(ACTOR, "wuyong", view.setupId());
            setups.activate(ACTOR, setup, "123", "lease-activation", 10);
        });
        var metadata = setupPoll(view, null).controlAuthorization();
        assertNotNull(metadata); assertEquals("CONTROL", metadata.purpose());
        assertNotEquals(view.setupId(), metadata.authorizationId());
        assertEquals(view.agentId(), metadata.canonicalAgentId());
        assertEquals(metadata, setupPoll(view, null).controlAuthorization());
        assertThrows(HostingRentApplicationException.class, () -> f.app().cancelSetup(ACTOR, "wuyong", view.setupId()));
        assertThrows(HostingRentApplicationException.class, () -> service.exchange(SETUP_TOKEN,
                new HostingOutboundExchangeService.Request(metadata.authorizationId(), "fixture-host", "instance", null), 11));
        assertNull(service.exchange("rth1_" + "d".repeat(64),
                new HostingOutboundExchangeService.Request(metadata.authorizationId(), "fixture-host", "instance", null), 11).command());
        var adapter = security.getBean(HostingOutboundProvisioning.class);
        var request = new LinkedHashMap<String,Object>();
        request.putAll(SUBJECT); request.put("canonicalAgentId", view.agentId());
        request.put("protocol", "runtime-hosting-v1"); request.put("method", "prepare");
        request.put("bindingId", "123"); request.put("leaseId", "lease-activation");
        assertNull(adapter.exchange(request, "other-host"));
        assertNull(adapter.exchange(request, "fixture-host")); // queued, not ready
        assertNull(adapter.exchange(request, "fixture-host")); // exact replay, not a second command
        assertEquals(1, db.jdbc().queryForObject("SELECT count(*) FROM agent_hosting_exchange WHERE authorization_id=?", Integer.class, metadata.authorizationId()));
        tx.executeWithoutResult(status -> setups.revokeLease("0", "fixture-client", "fixture-owner", view.agentId(), "123", "lease-activation", 12));
        assertThrows(HostingRentApplicationException.class, () -> setupPoll(view, null));
        assertFalse(adapter.controls("0", "fixture-client", "fixture-owner", view.agentId()));
        assertNull(adapter.exchange(request, "fixture-host"));
        verifyNoInteractions(f.posting(), f.oldProvider());
    }
    @Test void cancellationSeesActivationCommittedAfterItsRepeatableReadSnapshot() throws Exception {
        var f = invitation(); var view = f.app().startSetup(ACTOR, "wuyong", KEY, setupBody());
        var setups = security.getBean(HostingSetupService.class);
        var setupStore = security.getBean(cn.jia.agent.mapper.hosting.HostingSetupStore.class);
        var snapshot = new CountDownLatch(1); var activated = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var cancellation = pool.submit(() -> tx.execute(status -> {
                assertNotNull(setupStore.find("0", "fixture-client", view.setupId()));
                snapshot.countDown();
                try { assertTrue(activated.await(30, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                return assertThrows(HostingRentApplicationException.class,
                        () -> setups.revoke(ACTOR, "wuyong", view.setupId(), 12)).code();
            }));
            try {
                assertTrue(snapshot.await(30, TimeUnit.SECONDS));
                tx.executeWithoutResult(status -> setups.activate(ACTOR,
                        setups.find(ACTOR, "wuyong", view.setupId()), "123", "snapshot-lease", 10));
            } finally { activated.countDown(); }
            assertEquals("HOSTING_SETUP_ALREADY_ACTIVATED", cancellation.get(30, TimeUnit.SECONDS));
        }
        assertNotNull(setupPoll(view, null).controlAuthorization());
        assertEquals("ACTIVE", tx.execute(status -> store.lock("0", "fixture-client", view.setupId()).status()));
    }

    @Test void cancelledSetupCannotActivateOrPublishControlAuthority() throws Exception {
        var f = invitation(); var view = f.app().startSetup(ACTOR, "wuyong", KEY, setupBody());
        var setups = security.getBean(HostingSetupService.class);
        f.app().cancelSetup(ACTOR, "wuyong", view.setupId());
        assertThrows(HostingRentApplicationException.class, () -> tx.executeWithoutResult(status ->
                setups.activate(ACTOR, setups.find(ACTOR, "wuyong", view.setupId()), "123", "cancelled-lease", 10)));
        assertEquals(0, db.jdbc().queryForObject("SELECT count(*) FROM agent_hosting_setup_activation", Integer.class));
        assertEquals(0, db.jdbc().queryForObject("SELECT count(*) FROM agent_hosting_authorization WHERE purpose='CONTROL'", Integer.class));
    }

    @Test void rolledBackReserveCannotPublishControlAuthority() throws Exception {
        var f = invitation(); var view = f.app().startSetup(ACTOR, "wuyong", KEY, setupBody());
        var setups = security.getBean(HostingSetupService.class);
        tx.executeWithoutResult(status -> {
            setups.activate(ACTOR, setups.find(ACTOR, "wuyong", view.setupId()), "123", "rolled-back-lease", 10);
            status.setRollbackOnly();
        });
        assertNull(setupPoll(view, null).controlAuthorization());
        assertEquals(0, db.jdbc().queryForObject("SELECT count(*) FROM agent_hosting_setup_activation", Integer.class));
        assertEquals(0, db.jdbc().queryForObject("SELECT count(*) FROM agent_hosting_authorization WHERE purpose='CONTROL'", Integer.class));
    }

    @Test void sharedTargetEnvelopeIsPermittedOnlyForTheExactSetupSubject() throws Exception {
        var bytes = new ClassPathResource("hosting/hosting-activation-v1.json").getInputStream().readAllBytes();
        var fixture = HostingOutboundExchangeService.parse(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        @SuppressWarnings("unchecked") var response = (Map<String,Object>)fixture.get("response");
        @SuppressWarnings("unchecked") var data = (Map<String,Object>)response.get("data");
        @SuppressWarnings("unchecked") var command = (Map<String,Object>)data.get("command");
        tx.executeWithoutResult(status -> service.enqueue("0", "fixture-client", "hca_fixture", command, 2));
        assertEquals(data, JSON.convertValue(poll(null), new tools.jackson.core.type.TypeReference<Map<String,Object>>() {}));
    }

}
