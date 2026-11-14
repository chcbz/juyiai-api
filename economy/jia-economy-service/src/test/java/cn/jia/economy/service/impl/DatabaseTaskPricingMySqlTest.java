package cn.jia.economy.service.impl;

import cn.jia.economy.config.EconomyMySqlTestFixture;
import cn.jia.economy.config.TaskPricingTestFixture;
import cn.jia.economy.pricing.ModelTokenPrice;
import cn.jia.economy.pricing.TaskWorkload;
import cn.jia.economy.service.EconomyScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Actual MySQL keys and consistent reads, not in-memory catalog approximations. */
@EnabledIfEnvironmentVariable(named = EconomyMySqlTestFixture.URL_ENV, matches = ".+")
class DatabaseTaskPricingMySqlTest {
    private final EconomyMySqlTestFixture fixture = new EconomyMySqlTestFixture();
    private final EconomyScope scope = new EconomyScope("Tenant", "Client");
    private final TaskWorkload work = new TaskWorkload("document", 10000, 1000, 1, Map.of());
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private DatabaseTaskPricingService service;

    private ModelTokenPrice price(String version, String amount) {
        return new ModelTokenPrice("channel", "model", "standard", version, "synthetic",
                Map.of(ModelTokenPrice.Category.INPUT, new BigDecimal(amount), ModelTokenPrice.Category.OUTPUT, BigDecimal.TEN), null, Map.of());
    }
    @BeforeEach void setup() {
        fixture.start(); var db = fixture.newDatabase("pricecatalog"); jdbc = db.jdbc();
        tx = new TransactionTemplate(new DataSourceTransactionManager(db.dataSource()));
        service = TaskPricingTestFixture.install(jdbc, scope, List.of(price("v1", "1.000000000000001")));
    }
    @AfterEach void close() { fixture.close(); }

    @Test void ddlUsesExactKeysAndCurrentVersionForeignKeys() {
        assertEquals("REPEATABLE-READ", jdbc.queryForObject("SELECT @@transaction_isolation", String.class));
        assertTrue(service.find(new EconomyScope("tenant", "Client"), "channel", "model", "standard").isEmpty());
        assertTrue(service.find(scope, "Channel", "model", "standard").isEmpty());
        assertTrue(service.findVersion(scope, "channel", "model", "standard", "V1").isEmpty());
        assertThrows(DataIntegrityViolationException.class, () -> TaskPricingTestFixture.insertVersion(jdbc, scope, price("v1", "2")));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE economy_model_price_current SET price_version='absent'"));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("DELETE FROM economy_model_price"));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("INSERT INTO economy_task_model_tariff SELECT * FROM economy_task_model_tariff"));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("INSERT INTO economy_tool_price_current VALUES('Tenant','Client','tool-channel','tool','call','absent',1)"));
    }

    @Test void committedChangeAffectsNewTransactionsNotInFlightSnapshot() throws Exception {
        try (var executor = Executors.newSingleThreadExecutor()) {
            tx.executeWithoutResult(status -> {
                var before = service.prepare(scope, work, "model");
                try {
                    executor.submit(() -> tx.executeWithoutResult(other -> {
                        TaskPricingTestFixture.insertVersion(jdbc, scope, price("v2", "10"));
                        assertEquals(1, jdbc.update("UPDATE economy_model_price_current SET price_version='v2' WHERE price_version='v1'"));
                    })).get(10, TimeUnit.SECONDS);
                } catch (Exception error) { throw new AssertionError(error); }
                var sameSnapshot = service.prepare(scope, work, "model");
                assertEquals(before.modelPrice(), sameSnapshot.modelPrice());
                assertEquals("v1", sameSnapshot.modelPrice().version());
            });
            assertEquals("v2", tx.execute(status -> service.prepare(scope, work, "model").modelPrice().version()));
            assertEquals(new BigDecimal("1.000000000000001"), service.findVersion(scope,"channel","model","standard","v1")
                    .orElseThrow().yuanPerMillionTokens().get(ModelTokenPrice.Category.INPUT));
        }
    }

    @Test void historyRemainsReadableWithoutCurrentPointer() {
        jdbc.update("DELETE FROM economy_model_price_current");
        assertTrue(service.find(scope,"channel","model","standard").isEmpty());
        assertEquals("v1", service.findVersion(scope,"channel","model","standard","v1").orElseThrow().version());
    }

    @Test void pricesHaveNoCrossScopeFallbackAndCanChangeWithoutServiceRecreation() {
        assertThrows(cn.jia.economy.pricing.TaskPricingService.Unavailable.class,
                () -> service.prepare(new EconomyScope("other", "Client"), work, "model"));
        var old = service.prepare(scope,work,"model");
        tx.executeWithoutResult(status -> {
            TaskPricingTestFixture.insertVersion(jdbc,scope,price("v2","12"));
            jdbc.update("UPDATE economy_model_price_current SET price_version='v2'");
        });
        var updated = service.prepare(scope,work,"model");
        assertEquals("v1", old.modelPrice().version());
        assertEquals("v2", updated.modelPrice().version());
    }

    @Test void toolKeysAndDecimalUnitsComeFromTariff() {
        jdbc.update("INSERT INTO economy_tool_price VALUES('Tenant','Client','tools','render','image','v1',?,1)",
                "{\"basis\":\"synthetic\",\"yuanPerUnit\":\"0.01234567890123456789\"}");
        jdbc.update("INSERT INTO economy_tool_price_current VALUES('Tenant','Client','tools','render','image','v1',1)");
        jdbc.update("UPDATE economy_task_model_tariff SET tools_json=?", "[{\"channel\":\"tools\",\"tool\":\"render\",\"unit\":\"image\",\"unitsPerDeliverable\":\"0.25\"}]");
        var prepared = service.prepare(scope,new TaskWorkload("media",100,1,3,Map.of()),"model");
        var key = new cn.jia.economy.pricing.TaskQuoteCalculator.ToolKey("tools","render","image");
        assertEquals(new BigDecimal("0.75"),prepared.workload().toolUnits().get(key));
        assertEquals(new BigDecimal("0.01234567890123456789"), prepared.toolPrices().get(key).yuanPerUnit());
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("INSERT INTO economy_tool_price SELECT * FROM economy_tool_price"));
    }
}
