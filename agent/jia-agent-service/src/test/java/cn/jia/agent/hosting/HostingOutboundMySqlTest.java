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
}
