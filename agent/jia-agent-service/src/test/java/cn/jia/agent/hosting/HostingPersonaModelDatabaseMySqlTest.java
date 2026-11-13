package cn.jia.agent.hosting;

import cn.jia.agent.mapper.hosting.PersonaModelHostingStore;
import cn.jia.economy.config.EconomyHostingRentSchemaInitializer;
import cn.jia.economy.config.EconomyMySqlTestFixture;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static org.junit.jupiter.api.Assertions.*;

/** Real MySQL configuration reads, FK/transaction boundaries and immutable existing plans. */
@EnabledIfEnvironmentVariable(named = "ECO_V0_W02_MYSQL_URL", matches = ".+")
class HostingPersonaModelDatabaseMySqlTest {
    private EconomyMySqlTestFixture fixture;
    private EconomyMySqlTestFixture.Database db;
    private PersonaModelHostingStore store;

    @BeforeEach void setup() {
        fixture = new EconomyMySqlTestFixture(); fixture.start(); db = fixture.newDatabase("hero_model");
        new EconomyHostingRentSchemaInitializer(db.jdbc()).afterPropertiesSet();
        new ResourceDatabasePopulator(new ClassPathResource("db/agent-persona-model-hosting.sql")).execute(db.dataSource());
        store = new PersonaModelHostingStore(db.jdbc());
    }
    @AfterEach void close() { if (fixture != null) fixture.close(); }

    private void seed() {
        db.jdbc().execute((ConnectionCallback<Void>) connection -> {
            try (var command = connection.createStatement()) {
                command.execute("SET @tenant_id='fixture-tenant', @client_id='fixture-client'");
            }
            new ResourceDatabasePopulator(new FileSystemResource(System.getProperty("cyf.heroConfigSql"))).populate(connection);
            return null;
        });
    }

    @Test void full108SeedIsScopedAndDoesNotCreateLeasesOrCharges() {
        seed();
        assertEquals(108, store.list("fixture-tenant", "fixture-client").size());
        assertEquals(8L, store.list("fixture-tenant", "fixture-client").values().stream().map(PersonaModelHostingStore.Tariff::modelId).distinct().count());
        assertEquals(500L, store.find("fixture-tenant", "fixture-client", "huarong").amountWen());
        assertTrue(store.list("other", "fixture-client").isEmpty());
        assertTrue(store.list("fixture-tenant", "other").isEmpty());
        assertNull(store.find("fixture-tenant", "fixture-client", "Huarong"));
        assertNull(store.find("fixture-tenant", "fixture-client", "huarong "));
        assertEquals(0, db.jdbc().queryForObject("SELECT COUNT(*) FROM economy_hosting_lease", Integer.class));
        assertEquals(0, db.jdbc().queryForObject("SELECT COUNT(*) FROM economy_hosting_rent_quote", Integer.class));
    }

    @Test void committedPriceAndModelChangesAreVisibleWithoutRebuildingReaderAndHistoryStaysImmutable() {
        seed();
        var previous = store.find("fixture-tenant", "fixture-client", "huarong");
        db.jdbc().update("""
                INSERT INTO economy_hosting_rent_plan(tenant_id,client_id,plan_id,plan_version,amount_wen,period_seconds,quote_ttl_seconds,currency,status,create_time)
                VALUES('fixture-tenant','fixture-client','agent-hosting-preview',3000000001,770,2592000,300,'WEN','ACTIVE',1)
                """);
        db.jdbc().update("UPDATE economy_hosting_model_plan SET plan_version=3000000001 WHERE tenant_id=? AND client_id=? AND model_id=?",
                "fixture-tenant", "fixture-client", previous.modelId());
        assertEquals(770, store.find("fixture-tenant", "fixture-client", "huarong").amountWen());
        assertEquals(500, db.jdbc().queryForObject("SELECT amount_wen FROM economy_hosting_rent_plan WHERE tenant_id=? AND client_id=? AND plan_id=? AND plan_version=?",
                Long.class, "fixture-tenant", "fixture-client", previous.planId(), previous.planVersion()));
        var target = store.find("fixture-tenant", "fixture-client", "wuyong");
        db.jdbc().update("UPDATE agent_persona_model SET model_id=? WHERE tenant_id=? AND client_id=? AND persona_code='huarong'",
                target.modelId(), "fixture-tenant", "fixture-client");
        assertEquals(target.modelId(), store.find("fixture-tenant", "fixture-client", "huarong").modelId());
        assertEquals(target.amountWen(), store.find("fixture-tenant", "fixture-client", "huarong").amountWen());
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> db.jdbc().update("UPDATE economy_hosting_rent_plan SET amount_wen=1"));
    }

    @Test void danglingAndCrossScopePointersAreRejectedWithoutCheckConstraints() {
        seed();
        assertThrows(org.springframework.dao.DataAccessException.class, () -> db.jdbc().update(
                "INSERT INTO agent_persona_model VALUES('other','fixture-client','hero','gpt-6-astra')"));
        assertThrows(org.springframework.dao.DataAccessException.class, () -> db.jdbc().update(
                "UPDATE economy_hosting_model_plan SET plan_version=999999999"));
        assertEquals(0, db.jdbc().queryForObject("SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND table_name IN ('agent_persona_model','economy_hosting_model_plan') AND constraint_type='CHECK'", Integer.class));
        assertEquals(108, store.list("fixture-tenant", "fixture-client").size());
    }
}
