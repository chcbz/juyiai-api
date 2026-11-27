package cn.jia.agent.service.impl;

import cn.jia.agent.config.AgentTaskRequirementSnapshotSchemaInitializer;
import cn.jia.agent.dao.impl.AgentTaskMetaDaoImpl;
import cn.jia.agent.mapper.AgentTaskMetaMapper;
import cn.jia.agent.requirement.ConfirmedScope;
import cn.jia.agent.security.AuthenticatedRequirementConfirmationActor;
import cn.jia.agent.service.AgentTaskExecutionGrantService;
import cn.jia.agent.service.AgentTaskRequirementSnapshotService;
import cn.jia.agent.skill.SkillManagementMySqlFixture;
import cn.jia.agent.skill.*;
import cn.jia.agent.skill.quote.CapabilityQuoteSpec;
import cn.jia.agent.mapper.skill.*;
import cn.jia.agent.service.PositionAuthority;
import cn.jia.agent.config.skill.CapabilityQuoteSpecSchemaInitializer;
import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Native JSON, additive migration and real MyBatis/JDBC transactions on this suite's private mysqld. */
class ConfirmedScopeMySqlTest extends SkillManagementMySqlFixture {
    private static final AgentTaskExecutionGrantService.Scope OWNER =
            new AgentTaskExecutionGrantService.Scope("0","client","owner");
    private static final List<String> TABLES=List.of("agent_task_requirement_snapshot","agent_task_current_requirement");
    private static final List<String> SCOPE_COLUMNS=List.of("scope_schema","scope_json","scope_sha256","scope_input_sha256","scope_confirmed_at");
    private String database;
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private AgentTaskMetaDaoImpl roots;
    private AgentTaskRequirementSnapshotServiceImpl service;
    private AgentTaskExecutionGrantService catalogues;
    private SkillPackagePublicationService publisher;
    private PublishedCapabilityQuoteSpecs specifications;
    private final PositionAuthority.Scope publisherScope=new PositionAuthority.Scope("0","client","owner");
    private final PositionAuthority.Grant publisherGrant=new PositionAuthority.Grant("appointment",1,1,"agent",1,null,true);

    @BeforeEach void setup() throws Exception {
        database="cyf_scope_"+UUID.randomUUID().toString().replace("-","");
        admin.execute("CREATE DATABASE `"+database+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
        var source=source(database);jdbc=new JdbcTemplate(source);
        var manager=new DataSourceTransactionManager(source);tx=new TransactionTemplate(manager);
        for (String file:List.of("agent-task-requirement-snapshot-v1.sql","agent-task-current-requirement-v1.sql")) {
            String ddl=new ClassPathResource("db/"+file).getContentAsString(StandardCharsets.UTF_8);
            jdbc.execute(ddl.replaceAll("(?m)^ scope_[^\n]*\\n", ""));
        }
        new AgentTaskRequirementSnapshotSchemaInitializer(jdbc).afterPropertiesSet();
        jdbc.execute("CREATE TABLE agent_task_meta(id BIGINT AUTO_INCREMENT PRIMARY KEY,tenant_id VARCHAR(50) NOT NULL,"
                +"client_id VARCHAR(50) NOT NULL,owner_jiacn VARCHAR(50) NOT NULL,task_id VARCHAR(100) NOT NULL,"
                +"task_version BIGINT NOT NULL,current_event_version BIGINT NOT NULL,update_time BIGINT,"
                +"reward_status VARCHAR(20),assigned_agent_id VARCHAR(100),assigned_at BIGINT,started_at BIGINT,"
                +"KEY scope_root(tenant_id,client_id,owner_jiacn,task_id)) ENGINE=InnoDB COLLATE=utf8mb4_0900_ai_ci");
        jdbc.update("INSERT INTO agent_task_meta(tenant_id,client_id,owner_jiacn,task_id,task_version,current_event_version,reward_status) "
                +"VALUES ('0','client','owner','task',0,0,'open')");
        jdbc.execute("CREATE TABLE agent_task_funding(tenant_id VARCHAR(50),client_id VARCHAR(50),owner_jiacn VARCHAR(50),"
                +"task_id VARCHAR(100)) ENGINE=InnoDB COLLATE=utf8mb4_0900_bin");
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new ClassPathResource("db/economy-v0-skill-marketplace.sql")).execute(source);
        new CapabilityQuoteSpecSchemaInitializer(jdbc).afterPropertiesSet();
        var configuration=new MybatisConfiguration();configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(SkillCatalogMapper.class);configuration.addMapper(CapabilityQuoteSpecMapper.class);
        configuration.addMapper(AgentTaskMetaMapper.class);
        var factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(source);factory.setConfiguration(configuration);
        var session=new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
        roots=new AgentTaskMetaDaoImpl();
        var field=cn.jia.common.dao.BaseDaoImpl.class.getDeclaredField("baseMapper");field.setAccessible(true);
        field.set(roots,session.getMapper(AgentTaskMetaMapper.class));
        service=new AgentTaskRequirementSnapshotServiceImpl(jdbc,new AgentTaskMutationTransactionImpl(roots,manager));
        service.confirmationActor(new AuthenticatedRequirementConfirmationActor());
        specifications=new PublishedCapabilityQuoteSpecs(session.getMapper(CapabilityQuoteSpecMapper.class));
        service.capabilitySpecifications(specifications);
        publishSpecifications(session);
        catalogues=mock(AgentTaskExecutionGrantService.class);
        when(catalogues.snapshotTaskInputsWithinLockedTask(OWNER,"task")).thenReturn(new AgentTaskExecutionGrantService.InputCatalogue(
                "0".repeat(64),List.of(new AgentTaskExecutionGrantService.AuthorizedInput("f-text",1,"INPUT","text/plain",1000,"a".repeat(64)),
                new AgentTaskExecutionGrantService.AuthorizedInput("f-doc",3,"INPUT","application/pdf",2000,"b".repeat(64)))));
        @SuppressWarnings("unchecked") ObjectProvider<AgentTaskExecutionGrantService> provider=mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(catalogues);service.scopeDependencies(provider,roots);
        authenticate();tx.execute(s->service.captureOnCreate(OWNER,"task","Title",null));
    }

    private void publishSpecifications(SqlSessionTemplate session) throws Exception {
        var authority=mock(PositionAuthority.class);
        when(authority.withSkillManagementAuthority(org.mockito.ArgumentMatchers.eq(publisherScope),org.mockito.ArgumentMatchers.eq(publisherGrant),org.mockito.ArgumentMatchers.any()))
                .thenAnswer(i->tx.execute(status->i.<java.util.function.Supplier<?>>getArgument(2).get()));
        @SuppressWarnings("unchecked") ObjectProvider<SkillPackages> packageProvider=mock(ObjectProvider.class);
        when(packageProvider.getIfUnique()).thenReturn(new SkillPackages(java.nio.file.Files.createTempDirectory(scratch,"scope-packages-")));
        publisher=new SkillPackagePublicationService(session.getMapper(SkillCatalogMapper.class),packageProvider,authority,
                new CapabilityQuotePublication(session.getMapper(CapabilityQuoteSpecMapper.class)));
        var pack=new cn.jia.agent.config.skill.skillseed.PlatformSkillPackageCatalog().requirePackage("spv_repo_inspector_1_0_0");var product=pack.product();
        var declared=new ArrayList<CapabilityQuoteSpec>();
        for(var entry:List.of(List.of("text.summary","words","unit/words"),List.of("visual.asset","count","unit/items"),List.of("document.extract","count","unit/items"))) {
            declared.add(new CapabilityQuoteSpec(CapabilityQuoteSpec.SCHEMA,entry.get(0),"1",
                    Map.of("deliverables."+entry.get(1),new CapabilityQuoteSpec.Field(ConfirmedScope.ValueType.INTEGER,entry.get(2),true,"1","10000",Set.of(),null)),
                    List.of(new CapabilityQuoteSpec.CostDimension("size",CapabilityQuoteSpec.Source.DELIVERABLE,entry.get(1),ConfirmedScope.ValueType.INTEGER,entry.get(2),null,Set.of())),
                    List.of(new CapabilityQuoteSpec.Bucket("all",Map.of("size",new CapabilityQuoteSpec.Range("1","10001",Set.of()))))));
        }
        publisher.publish(publisherScope,publisherGrant,new SkillPackagePublicationService.Publish("fixture","fixture-v1",product.name(),product.description(),
                product.skillKey(),product.skillVersion(),0,"NONE","[]",HexFormat.of().formatHex(product.packageSha256()),null,0,null,false,declared),pack.bytes());
    }

    @AfterEach void cleanup() {
        clearIdentity();
        if (database!=null && database.matches("cyf_scope_[0-9a-f]{32}")) admin.execute("DROP DATABASE `"+database+"`");
    }
    private static void authenticate() {
        var context=new EsContext();context.setJiacn("owner");context.setClientId("client");EsContextHolder.setContext(context);
        var token=new JwtAuthenticationToken(Jwt.withTokenValue("fixture").header("alg","none")
                .claim("jiacn","owner").claim("client_id","client").build(),List.of());
        SecurityContextHolder.getContext().setAuthentication(token);
    }
    private static void clearIdentity() {SecurityContextHolder.clearContext();EsContextHolder.clearContext();}
    private static AgentTaskRequirementSnapshotService.ConfirmScopeCommand command(String capability,String key,String unit,String value,List<String> refs) {
        var item=new ConfirmedScope.WorkItem("w1",capability,"1",Map.of(),
                Map.of(key,new ConfirmedScope.Scalar(ConfirmedScope.ValueType.INTEGER,unit,value)),refs,List.of());
        return new AgentTaskRequirementSnapshotService.ConfirmScopeCommand("Title",null,
                new ConfirmedScope.Payload(ConfirmedScope.VERSION,List.of(item),"已确认的交付约定🚀"));
    }
    private static AgentTaskRequirementSnapshotService.ConfirmScopeCommand text() {
        return command("text.summary","words","unit/words","500",List.of("f-text@1"));
    }
    private AgentTaskRequirementSnapshotService.Snapshot confirm() {return service.confirmScope(OWNER,"task",0,"confirm",text());}
    private long version() {return jdbc.queryForObject("SELECT task_version FROM agent_task_meta WHERE owner_jiacn='owner'",Long.class);}
    private int snapshots() {return jdbc.queryForObject("SELECT COUNT(*) FROM agent_task_requirement_snapshot",Integer.class);}
    private void removeScopeColumns() {
        for (String table:TABLES) jdbc.execute("ALTER TABLE "+table+" "+String.join(", ",SCOPE_COLUMNS.stream().map(n->"DROP COLUMN "+n).toList()));
    }
    private int scopeColumnCount(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? "
                +"AND column_name IN ('scope_schema','scope_json','scope_sha256','scope_input_sha256','scope_confirmed_at')",Integer.class,table);
    }

    @Test void oldRowsSurviveMigrationAndRestartWithoutFabricatedConfirmation() {
        var original=service.read(OWNER,"task",1);removeScopeColumns();
        var before=jdbc.queryForList("SELECT * FROM agent_task_requirement_snapshot");
        new AgentTaskRequirementSnapshotSchemaInitializer(jdbc).afterPropertiesSet();
        new AgentTaskRequirementSnapshotSchemaInitializer(jdbc).afterPropertiesSet();
        assertEquals(original,service.read(OWNER,"task",1));
        assertNull(service.readCurrent(OWNER,"task").confirmedScope());assertEquals(0,version());
        var after=jdbc.queryForList("SELECT * FROM agent_task_requirement_snapshot");
        for (String name:SCOPE_COLUMNS) assertNull(after.getFirst().remove(name));
        assertEquals(before,after);
        for (String table:TABLES) assertEquals(5,scopeColumnCount(table));
    }

    @Test void explicitSqlMigrationPreservesOldSnapshotAndInitializerCanResume() throws Exception {
        var original=service.read(OWNER,"task",1);removeScopeColumns();
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                new ClassPathResource("db/agent-task-confirmed-scope-v1.sql"))
                .execute(Objects.requireNonNull(jdbc.getDataSource()));
        new AgentTaskRequirementSnapshotSchemaInitializer(jdbc).afterPropertiesSet();
        assertEquals(original,service.read(OWNER,"task",1));
        assertNull(service.readCurrent(OWNER,"task").confirmedScope());
        assertEquals(2,confirm().revision());assertEquals(1,version());
    }

    @Test void existingCurrentDriftStopsBothTableMigrationsThenPartialValidSchemaResumes() {
        removeScopeColumns();
        jdbc.execute("ALTER TABLE agent_task_current_requirement ADD COLUMN scope_schema VARCHAR(12) NULL");
        assertThrows(IllegalStateException.class,()->new AgentTaskRequirementSnapshotSchemaInitializer(jdbc).afterPropertiesSet());
        assertEquals(0,scopeColumnCount(TABLES.getFirst()));assertEquals(1,scopeColumnCount(TABLES.getLast()));
        jdbc.execute("ALTER TABLE agent_task_current_requirement DROP COLUMN scope_schema, ADD COLUMN scope_json JSON NULL");
        jdbc.execute("ALTER TABLE agent_task_requirement_snapshot ADD COLUMN scope_json JSON NULL");
        new AgentTaskRequirementSnapshotSchemaInitializer(jdbc).afterPropertiesSet();
        assertNotNull(confirm().confirmedScope());
        new AgentTaskRequirementSnapshotSchemaInitializer(jdbc).afterPropertiesSet();
        assertEquals(confirm(),service.read(OWNER,"task",2));
    }

    @Test void currentBaseDriftCannotPartiallyMigrateSnapshot() {
        removeScopeColumns();jdbc.execute("ALTER TABLE agent_task_current_requirement MODIFY title VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL");
        assertThrows(IllegalStateException.class,()->new AgentTaskRequirementSnapshotSchemaInitializer(jdbc).afterPropertiesSet());
        assertEquals(0,scopeColumnCount(TABLES.getFirst()));assertEquals(0,scopeColumnCount(TABLES.getLast()));
    }

    @Test void nativeJsonRoundTripsThreeBusinessShapesWithoutRewritingHistory() {
        var commands=List.of(text(),command("visual.asset","count","unit/items","2",List.of()),
                command("document.extract","count","unit/items","1",List.of("f-doc@3")));
        for(int i=0;i<commands.size();i++) {
            var row=service.confirmScope(OWNER,"task",i,"shape_"+i,commands.get(i));
            assertEquals(row,service.read(OWNER,"task",i+2));
            assertEquals(row.confirmedScope(),service.readCurrent(OWNER,"task").confirmedScope());
            assertEquals(row,service.confirmScope(OWNER,"task",i,"shape_"+i,commands.get(i)));
        }
        assertEquals(3,version());assertEquals(4,snapshots());
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM agent_task_requirement_snapshot WHERE JSON_VALID(scope_json)=1",Integer.class));
        assertNull(service.read(OWNER,"task",1).confirmedScope());
        assertThrows(IllegalStateException.class,this::confirm);
    }

    @Test void outerRollbackUndoesSnapshotCurrentAndVersionWithActualMapper() {
        assertThrows(IllegalStateException.class,()->tx.execute(s->{confirm();throw new IllegalStateException("abort outer transaction");}));
        assertEquals(0,version());assertEquals(1,snapshots());assertNull(service.readCurrent(OWNER,"task").confirmedScope());
        assertEquals(2,confirm().revision());assertEquals(1,version());
    }

    @Test void failureOnFinalVersionWriteRollsBackBothScopeWrites() {
        jdbc.execute("CREATE TRIGGER reject_scope_version BEFORE UPDATE ON agent_task_meta FOR EACH ROW "
                +"SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='fixture_version_failure'");
        try {
            assertThrows(org.springframework.dao.DataAccessException.class,this::confirm);
            assertEquals(0,version());assertEquals(1,snapshots());
            assertNull(service.readCurrent(OWNER,"task").confirmedScope());
        } finally {jdbc.execute("DROP TRIGGER reject_scope_version");}
        assertEquals(2,confirm().revision());assertEquals(1,version());
    }

    @Test void fundedReplayReturnsOriginalButNewConfirmationCannotWrite() {
        var row=confirm();jdbc.update("INSERT INTO agent_task_funding VALUES ('0','client','owner','task')");
        assertEquals(row,confirm());
        assertThrows(IllegalStateException.class,()->service.confirmScope(OWNER,"task",1,"new",text()));
        assertEquals(1,version());assertEquals(2,snapshots());
        verify(catalogues,times(1)).snapshotTaskInputsWithinLockedTask(OWNER,"task");
    }

    @Test void scopeConfirmationRequiresPublishedBindingAndValidDeclaredFields() {
        var invalid=command("text.summary","words","unit/words","10001",List.of("f-text@1"));
        assertEquals(400,assertThrows(SkillMarketplaceException.class,()->service.confirmScope(OWNER,"task",0,"bad",invalid)).status());
        jdbc.update("DELETE FROM agent_skill_quote_spec_binding WHERE capability_id='text.summary'");
        assertEquals(409,assertThrows(SkillMarketplaceException.class,this::confirm).status());
        assertEquals(0,version());assertEquals(1,snapshots());assertNull(service.readCurrent(OWNER,"task").confirmedScope());
    }
    @Test void authorizedWithdrawalStopsNewConfirmationsWithoutRewritingConfirmedHistory() {
        var original=confirm();var withdrawal=new SkillPackagePublicationService.WithdrawQuoteSpec("text.summary","1");
        assertEquals("WITHDRAWN",publisher.withdrawQuoteSpec(publisherScope,publisherGrant,withdrawal).disposition());
        assertEquals("RECORDED",publisher.withdrawQuoteSpec(publisherScope,publisherGrant,withdrawal).disposition());
        assertEquals(original,confirm());assertEquals(original,service.read(OWNER,"task",2));
        assertEquals(409,assertThrows(SkillMarketplaceException.class,()->service.confirmScope(OWNER,"task",1,"new",text())).status());
        assertEquals(1,version());assertEquals(2,snapshots());
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM agent_skill_quote_spec_binding",Integer.class));
    }
    @Test void publishedScopeConsumerRejectsCorruptSemanticsAndCrossClientKeys() {
        var key=new CapabilityQuoteSpecMapper.SpecKey("text.summary","1");
        assertEquals(1,specifications.readActive("0","client",List.of(key,key)).size());
        assertEquals(409,assertThrows(SkillMarketplaceException.class,()->specifications.readActive("0","Client",List.of(key))).status());
        assertEquals(409,assertThrows(SkillMarketplaceException.class,()->specifications.readActive("other","client",List.of(key))).status());
        jdbc.update("UPDATE agent_capability_quote_spec SET spec_json=JSON_SET(spec_json,'$.version','2') WHERE capability_id='text.summary'");
        assertEquals(503,assertThrows(SkillMarketplaceException.class,this::confirm).status());
        assertEquals(0,version());assertEquals(1,snapshots());
    }
    @Test void withdrawalWaitsForTheRealConfirmedSpecificationShareLock() throws Exception {
        var written=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var confirmation=pool.submit(()->{authenticate();try{return tx.execute(s->{var row=confirm();written.countDown();await(release);return row;});}finally{clearIdentity();}});
            Future<SkillPackagePublicationService.QuoteSpecWithdrawalReceipt> withdrawal=null;
            try {
                assertTrue(written.await(10,TimeUnit.SECONDS));
                withdrawal=pool.submit(()->publisher.withdrawQuoteSpec(publisherScope,publisherGrant,new SkillPackagePublicationService.WithdrawQuoteSpec("text.summary","1")));
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);boolean waiting=false;
                while(System.nanoTime()<deadline) {
                    int locks=jdbc.queryForObject("SELECT COUNT(*) FROM performance_schema.data_lock_waits w JOIN performance_schema.data_locks l "
                            +"ON w.REQUESTING_ENGINE_LOCK_ID=l.ENGINE_LOCK_ID AND w.ENGINE=l.ENGINE WHERE l.OBJECT_SCHEMA=? AND l.OBJECT_NAME='agent_capability_quote_spec'",Integer.class,database);
                    if(locks>0){waiting=true;break;}if(withdrawal.isDone())break;Thread.sleep(20);
                }
                assertTrue(waiting,"Observe an actual MySQL wait, not only an unfinished future");
            } finally {release.countDown();}
            assertNotNull(withdrawal);var original=confirmation.get(10,TimeUnit.SECONDS);
            assertEquals("WITHDRAWN",withdrawal.get(10,TimeUnit.SECONDS).status());assertEquals(original,confirm());
        }
        assertEquals(1,version());assertEquals(2,snapshots());
    }

    @Test void mapperVersionCasIsExactEvenUnderCaseInsensitiveRootCollation() {
        tx.executeWithoutResult(s->{
            assertEquals(0,roots.advanceRequirementVersionInOwnerScope("0","Client","owner","task",0));
            assertEquals(0,roots.advanceRequirementVersionInOwnerScope("0","client","Owner","task",0));
            assertEquals(0,roots.advanceRequirementVersionInOwnerScope("0","client","owner","Task",0));
            assertEquals(0,roots.advanceRequirementVersionInOwnerScope("0","client","owner","task",1));
        });
        assertEquals(0,version());assertEquals(1,confirm().confirmedScope().requirementRevision()-1);
    }

    @Test void concurrentReplayWaitsForRootCommitThenReturnsSameSnapshot() throws Exception {
        var written=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->{
                authenticate();
                try {return tx.execute(s->{var row=confirm();written.countDown();await(release);return row;});}
                finally {clearIdentity();}
            });
            Future<AgentTaskRequirementSnapshotService.Snapshot> second=null;
            try {
                assertTrue(written.await(10,TimeUnit.SECONDS));
                second=pool.submit(()->{authenticate();try{return confirm();}finally{clearIdentity();}});
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);boolean waiting=false;
                while(System.nanoTime()<deadline) {
                    int locks=jdbc.queryForObject("SELECT COUNT(*) FROM performance_schema.data_lock_waits w "
                            +"JOIN performance_schema.data_locks l ON w.REQUESTING_ENGINE_LOCK_ID=l.ENGINE_LOCK_ID "
                            +"AND w.ENGINE=l.ENGINE WHERE l.OBJECT_SCHEMA=? AND l.OBJECT_NAME='agent_task_meta'",Integer.class,database);
                    if(locks>0) {waiting=true;break;}
                    if(second.isDone()) break;
                    Thread.sleep(20);
                }
                assertTrue(waiting,"Second connection must be observed waiting on the real task-root lock");
            } finally {release.countDown();}
            assertNotNull(second);assertEquals(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS));
        }
        assertEquals(1,version());assertEquals(2,snapshots());
        verify(catalogues,times(1)).snapshotTaskInputsWithinLockedTask(OWNER,"task");
    }
    private static void await(CountDownLatch latch) {
        try {if(!latch.await(15,TimeUnit.SECONDS))throw new IllegalStateException("fixture latch timed out");}
        catch(InterruptedException failure){Thread.currentThread().interrupt();throw new IllegalStateException(failure);}
    }
}
