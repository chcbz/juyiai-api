package cn.jia.agent.skill;

import cn.jia.agent.config.skill.CapabilityQuoteSpecSchemaInitializer;
import cn.jia.agent.mapper.skill.*;
import cn.jia.agent.service.PositionAuthority;
import cn.jia.agent.skill.quote.CapabilityQuoteSpec;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real original publication -> MyBatis -> native JSON/FKs -> commit/rollback. No app database. */
class CapabilityQuotePublicationMySqlTest extends SkillManagementMySqlFixture {
    @TempDir Path packagesRoot;
    private String database;
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private SkillPackagePublicationService publisher;
    private PositionAuthority authority;
    private CapabilityQuoteSpecMapper specs;
    private CapabilityQuotePublication publication;
    private final PositionAuthority.Scope actor=new PositionAuthority.Scope("0","client","owner");
    private final PositionAuthority.Grant grant=new PositionAuthority.Grant("appointment",1,1,"agent",1,null,true);
    static final String DECLARATION="""
        {"schema":"CAPABILITY_QUOTE_SPEC_V1","capabilityId":"text.summary","version":"1",
         "fields":{"deliverables.words":{"type":"INTEGER","unit":"unit/words","required":true,"min":"1","max":"10000"}},
         "costDimensions":[{"key":"size","source":"DELIVERABLE","selector":"words","type":"INTEGER","unit":"unit/words"}],
         "buckets":[{"id":"short","ranges":{"size":{"lo":"1","hi":"1001"}}}]}
        """;
    @BeforeEach void setup() throws Exception {
        database="cyf_capability_"+UUID.randomUUID().toString().replace("-","");
        admin.execute("CREATE DATABASE `"+database+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
        var source=source(database);jdbc=new JdbcTemplate(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        new ResourceDatabasePopulator(new ClassPathResource("db/economy-v0-skill-marketplace.sql")).execute(source);
        new CapabilityQuoteSpecSchemaInitializer(jdbc).afterPropertiesSet();
        var config=new MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);
        config.addMapper(SkillCatalogMapper.class);config.addMapper(CapabilityQuoteSpecMapper.class);
        var factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(source);factory.setConfiguration(config);
        var session=new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
        specs=session.getMapper(CapabilityQuoteSpecMapper.class);publication=new CapabilityQuotePublication(specs);
        authority=mock(PositionAuthority.class);
        when(authority.withSkillManagementAuthority(any(),eq(grant),any())).thenAnswer(i->tx.execute(s->i.<Supplier<?>>getArgument(2).get()));
        @SuppressWarnings("unchecked") ObjectProvider<SkillPackages> provider=mock(ObjectProvider.class);
        when(provider.getIfUnique()).thenReturn(new SkillPackages(packagesRoot));
        publisher=new SkillPackagePublicationService(session.getMapper(SkillCatalogMapper.class),provider,authority,publication);
    }
    @AfterEach void cleanup() {
        if(database!=null && database.matches("cyf_capability_[0-9a-f]{32}"))admin.execute("DROP DATABASE `"+database+"`");
    }
    private byte[] archive(String version) throws Exception {
        var out=new java.io.ByteArrayOutputStream();
        try(var zip=new java.util.zip.ZipOutputStream(out)) {
            var entry=new java.util.zip.ZipEntry("SKILL.md");entry.setTime(0);zip.putNextEntry(entry);
            zip.write(("---\nname: general-task\ndescription: fixture\nmetadata:\n  version: \""+version+"\"\n---\nUse native tools.\n").getBytes(StandardCharsets.UTF_8));zip.closeEntry();
        }
        return out.toByteArray();
    }
    private SkillPackagePublicationService.Publish command(int version,long expected,byte[] bytes,List<CapabilityQuoteSpec> declarations) {
        return new SkillPackagePublicationService.Publish("product","pv"+version,"General","fixture","general-task",version+".0.0",0,"NONE","[]",
                HexFormat.of().formatHex(SkillPackageStore.sha256(bytes)),null,expected,null,false,declarations);
    }
    private SkillPackagePublicationService.Receipt publish(PositionAuthority.Scope scope,int version,long expected,List<CapabilityQuoteSpec> declarations) throws Exception {
        byte[] bytes=archive(version+".0.0");return publisher.publish(scope,grant,command(version,expected,bytes,declarations),bytes);
    }
    private List<CapabilityQuoteSpec> declared() {return List.of(CapabilityQuoteSpecJson.read(DECLARATION));}
    private int count(String table) {return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}
    @Test void publicationStoresImmutableSemanticAndVersionBindingAndReplaysExactly() throws Exception {
        assertEquals("PUBLISHED",publish(actor,1,0,declared()).disposition());
        assertEquals("RECORDED",publish(actor,1,0,declared()).disposition());
        assertEquals(1,count("agent_capability_quote_spec"));assertEquals(1,count("agent_skill_quote_spec_binding"));
        var stored=tx.execute(s->specs.lockSpec("0","client","text.summary","1"));assertNotNull(stored);
        assertEquals(declared().getFirst(),CapabilityQuoteSpecJson.readStored(stored.specJson(),stored.specSha()));
        assertEquals(1,jdbc.queryForObject("SELECT JSON_LENGTH(spec_json,'$.buckets') FROM agent_capability_quote_spec",Integer.class));
        assertEquals("SKILL_VERSION_IMMUTABLE",assertThrows(SkillMarketplaceException.class,()->publish(actor,1,0,List.of())).code());
        assertEquals(1,count("agent_skill_product_version"));
    }
    @Test void changedSemanticsCannotReuseAnExistingCapabilityVersionAndRollsBackTheNewPackageVersion() throws Exception {
        publish(actor,1,0,declared());
        var changed=List.of(CapabilityQuoteSpecJson.read(DECLARATION.replace("10000","9999")));
        assertEquals("CAPABILITY_QUOTE_SPEC_IMMUTABLE",assertThrows(SkillMarketplaceException.class,()->publish(actor,2,2,changed)).code());
        assertEquals(1,count("agent_skill_product_version"));assertEquals(1,count("agent_skill_quote_spec_binding"));
        assertEquals("pv1",jdbc.queryForObject("SELECT current_product_version_id FROM agent_skill_product",String.class));
        assertEquals(2,jdbc.queryForObject("SELECT version FROM agent_skill_product",Integer.class));
    }
    @Test void compatibleVersionsShareSemanticsAndTenantClientDomainsDoNotShareDeclarations() throws Exception {
        publish(actor,1,0,declared());publish(actor,2,2,declared());
        assertEquals(1,count("agent_capability_quote_spec"));assertEquals(2,count("agent_skill_quote_spec_binding"));
        var changed=List.of(CapabilityQuoteSpecJson.read(DECLARATION.replace("10000","9999")));
        publish(new PositionAuthority.Scope("other","client","owner"),1,0,changed);
        publish(new PositionAuthority.Scope("0","Client","owner"),1,0,changed);
        assertEquals(3,count("agent_capability_quote_spec"));assertEquals(4,count("agent_skill_quote_spec_binding"));
        assertNull(tx.execute(s->specs.lockSpec("0","client ","text.summary","1")));
    }
    @Test void failedFinalProductCasRollsBackSpecBindingAndVersionTogether() throws Exception {
        jdbc.execute("CREATE TRIGGER fail_quote_product_publish BEFORE UPDATE ON agent_skill_product FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='fixture publication rollback'");
        assertThrows(RuntimeException.class,()->publish(actor,1,0,declared()));
        for(String table:List.of("agent_skill_product","agent_skill_product_version","agent_capability_quote_spec","agent_skill_quote_spec_binding"))assertEquals(0,count(table),table);
    }
    @Test void externalTransactionRollbackDoesNotLeaveAnAuthorizedSpecification() throws Exception {
        byte[] bytes=archive("1.0.0");var prepared=publisher.prepare(actor,grant,command(1,0,bytes,declared()),bytes);
        assertThrows(IllegalStateException.class,()->tx.execute(s->{publisher.commitPrepared(prepared);throw new IllegalStateException("fixture rollback");}));
        assertEquals(0,count("agent_skill_quote_spec_binding"));assertEquals(0,count("agent_capability_quote_spec"));assertEquals(0,count("agent_skill_product_version"));
    }
    @Test void revokedPublicationAuthorityAfterPreparationWritesNoBusinessRows() throws Exception {
        byte[] bytes=archive("1.0.0");var prepared=publisher.prepare(actor,grant,command(1,0,bytes,declared()),bytes);
        when(authority.withSkillManagementAuthority(any(),eq(grant),any())).thenThrow(new cn.jia.agent.position.PositionSkillException(403,"POSITION_AUTHORITY_REVOKED"));
        assertThrows(cn.jia.agent.position.PositionSkillException.class,()->publisher.commitPrepared(prepared));
        assertEquals(0,count("agent_capability_quote_spec"));assertEquals(0,count("agent_skill_product"));
    }
    @Test void withdrawnSpecDoesNotReviveThroughNewPublicationButHistoricalReplayIsReadable() throws Exception {
        publish(actor,1,0,declared());
        assertEquals("WITHDRAWN",publisher.withdrawQuoteSpec(actor,grant,new SkillPackagePublicationService.WithdrawQuoteSpec("text.summary","1")).disposition());
        assertEquals("RECORDED",publisher.withdrawQuoteSpec(actor,grant,new SkillPackagePublicationService.WithdrawQuoteSpec("text.summary","1")).disposition());
        assertEquals("RECORDED",publish(actor,1,0,declared()).disposition());
        assertEquals("CAPABILITY_QUOTE_SPEC_WITHDRAWN",assertThrows(SkillMarketplaceException.class,()->publish(actor,2,2,declared())).code());
        assertEquals(1,count("agent_skill_product_version"));assertEquals("WITHDRAWN",jdbc.queryForObject("SELECT status FROM agent_capability_quote_spec",String.class));
    }
    @Test void withdrawalRequiresOriginalPositionAuthorityAndCannotCrossThePublicationDomain() throws Exception {
        publish(actor,1,0,declared());var command=new SkillPackagePublicationService.WithdrawQuoteSpec("text.summary","1");
        assertEquals(403,assertThrows(SkillMarketplaceException.class,()->publisher.withdrawQuoteSpec(actor,null,command)).status());
        assertEquals(404,assertThrows(SkillMarketplaceException.class,()->publisher.withdrawQuoteSpec(new PositionAuthority.Scope("other","client","owner"),grant,command)).status());
        when(authority.withSkillManagementAuthority(any(),eq(grant),any())).thenThrow(new cn.jia.agent.position.PositionSkillException(403,"POSITION_AUTHORITY_REVOKED"));
        assertThrows(cn.jia.agent.position.PositionSkillException.class,()->publisher.withdrawQuoteSpec(actor,grant,command));
        assertEquals("ACTIVE",jdbc.queryForObject("SELECT status FROM agent_capability_quote_spec",String.class));assertEquals(1,count("agent_skill_quote_spec_binding"));
    }
    @Test void corruptedJsonCannotBecomeNewPricingEvidenceAndDirectBindRequiresAnExistingTransaction() throws Exception {
        publish(actor,1,0,declared());jdbc.update("UPDATE agent_capability_quote_spec SET spec_json=JSON_SET(spec_json,'$.version','2')");
        assertEquals(503,assertThrows(SkillMarketplaceException.class,()->publish(actor,2,2,declared())).status());
        assertEquals(1,count("agent_skill_product_version"));
        assertEquals("SKILL_PUBLICATION_TRANSACTION_REQUIRED",assertThrows(SkillMarketplaceException.class,
                ()->publication.bindWithinPublication(actor,"pv1",CapabilityQuotePublication.prepare(declared()),true)).code());
    }
    @Test void additiveMigrationIsIdempotentAndRejectsExistingSchemaDriftBeforeCreatingMissingTable() throws Exception {
        publish(actor,1,0,declared());var before=jdbc.queryForList("SELECT * FROM agent_capability_quote_spec");
        new CapabilityQuoteSpecSchemaInitializer(jdbc).afterPropertiesSet();assertEquals(before,jdbc.queryForList("SELECT * FROM agent_capability_quote_spec"));
        jdbc.execute("DROP TABLE agent_skill_quote_spec_binding");
        jdbc.execute("ALTER TABLE agent_capability_quote_spec MODIFY status VARCHAR(15) NOT NULL");
        assertThrows(IllegalStateException.class,()->new CapabilityQuoteSpecSchemaInitializer(jdbc).afterPropertiesSet());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='agent_skill_quote_spec_binding'",Integer.class));
        jdbc.execute("ALTER TABLE agent_capability_quote_spec MODIFY status VARCHAR(16) NOT NULL");
        new CapabilityQuoteSpecSchemaInitializer(jdbc).afterPropertiesSet();assertEquals(0,count("agent_skill_quote_spec_binding"));
        assertEquals(before,jdbc.queryForList("SELECT * FROM agent_capability_quote_spec"));
    }
    @Test void foreignKeysRejectWrongSemanticDigestAndMissingProductVersion() throws Exception {
        publish(actor,1,0,declared());
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->tx.execute(s->specs.insertBinding("0","client","missing","text.summary","1",declared().getFirst().specSha())));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->tx.execute(s->{
            jdbc.update("DELETE FROM agent_skill_quote_spec_binding");return specs.insertBinding("0","client","pv1","text.summary","1","0".repeat(64));}));
        assertEquals(1,count("agent_skill_quote_spec_binding"));
    }
}
