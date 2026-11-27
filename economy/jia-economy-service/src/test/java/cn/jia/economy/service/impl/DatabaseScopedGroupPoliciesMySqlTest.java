package cn.jia.economy.service.impl;

import cn.jia.core.util.CanonicalValueEncoding;
import cn.jia.economy.config.EconomyMySqlTestFixture;
import cn.jia.economy.mapper.ScopedGroupPolicyMapper;
import cn.jia.economy.pricing.*;
import cn.jia.economy.service.EconomyScope;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named=EconomyMySqlTestFixture.URL_ENV,matches=".+")
class DatabaseScopedGroupPoliciesMySqlTest {
    private final EconomyMySqlTestFixture fixture=new EconomyMySqlTestFixture();
    private final EconomyScope domain=new EconomyScope("Tenant","Client");
    private final ObjectMapper json=new ObjectMapper();
    private JdbcTemplate jdbc;private TransactionTemplate tx;private DatabaseScopedGroupPolicies service;
    private final AtomicInteger reads=new AtomicInteger();private String database;
    @BeforeEach void setup()throws Exception {
        fixture.start();var db=fixture.newDatabase("scopepolicy");jdbc=db.jdbc();
        database=jdbc.queryForObject("SELECT DATABASE()",String.class);
        tx=new TransactionTemplate(new DataSourceTransactionManager(db.dataSource()));
        ddl("economy-group-budget-v1.sql",";");ddl("economy-group-budget-approval-v1.sql","$$");
        ddl("economy-group-study-v1.sql","$$");ddl("economy-group-study-scope-v1.sql",";");
        var configuration=new org.apache.ibatis.session.Configuration();configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(ScopedGroupPolicyMapper.class);var factory=new SqlSessionFactoryBean();
        factory.setDataSource(db.dataSource());factory.setConfiguration(configuration);
        var mapper=new SqlSessionTemplate(factory.getObject()).getMapper(ScopedGroupPolicyMapper.class);
        var counted=(ScopedGroupPolicyMapper)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{ScopedGroupPolicyMapper.class},(p,m,args)->{
            if(m.getName().equals("read"))reads.incrementAndGet();
            try{return m.invoke(mapper,args);}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}
        });service=new DatabaseScopedGroupPolicies(counted);
    }
    @AfterEach void close(){fixture.close();}
    private void ddl(String name,String separator){var pop=new ResourceDatabasePopulator(new ClassPathResource("db/"+name));pop.setSeparator(separator);pop.execute(jdbc.getDataSource());}
    private DatabaseScopedGroupPolicies.Receipt receipt(String bucket,int support){
        var slots=List.of(new GroupMeanPricing.ScopeSlot(0,"text.summary","1","b".repeat(64),List.of()));
        var config=Map.<String,Object>of("strategy","native-1","routes",List.of("model","tool"));
        var input=new GroupMeanPricing.ScopeInput(slots,List.of(bucket),CanonicalValueEncoding.digest(config));
        var policy=new GroupMeanPricing.ScopedPolicy("f1",input.specShas(),input.scopeShapeSha(),"config-1",GroupMeanPricing.ScopedPolicy.definitionsSha(input.specShas()),support);
        var group=new GroupMeanPricing.ScopeGroup(policy.featureVersion(),policy.sha(),slots,List.of(bucket),config);
        return new DatabaseScopedGroupPolicies.Receipt(DatabaseScopedGroupPolicies.PURPOSE,domain.tenantId(),domain.clientId(),"fixture-approval",
                "c".repeat(64),"d".repeat(64),policy,policy.sha(),input.key(),group.key(),slots,List.of(bucket),config,
                10,10,new BigDecimal("10"),new BigDecimal("2"),new BigDecimal("0.40"));
    }
    private GroupMeanPricing.ScopeInput input(DatabaseScopedGroupPolicies.Receipt r){return new GroupMeanPricing.ScopeInput(r.scopeShape(),r.bucketIds(),CanonicalValueEncoding.digest(r.compatibleConfig()));}
    private String approved(String body){String sha=GroupPublicationEnrollment.digest(body);jdbc.update("INSERT INTO economy_budget_publish_approval(approval_sha,authority_json) VALUES(?,?)",sha,body);return sha;}
    private String approved(DatabaseScopedGroupPolicies.Receipt r){return approved(json.writeValueAsString(r));}
    private ScopedGroupPolicies.Selected select(DatabaseScopedGroupPolicies.Receipt r,String approval,String previous){return tx.execute(s->service.select(domain,input(r),approval,previous));}
    private ScopedGroupPolicies.Selected read(DatabaseScopedGroupPolicies.Receipt r){return service.read(domain,List.of(input(r)),false).get(input(r));}
    private void head(DatabaseScopedGroupPolicies.Receipt r){jdbc.update("INSERT INTO economy_budget_group_head(tenant_id,client_id,group_sha,generation,status,book_epoch,attempt,policy_sha,updated_at) VALUES(?,?,?,1,'WAIT_INPUT',1,0,?,CURRENT_TIMESTAMP(3))",domain.tenantId(),domain.clientId(),r.groupSha(),r.policySha());}
    private long generation(DatabaseScopedGroupPolicies.Receipt r){return jdbc.queryForObject("SELECT generation FROM economy_budget_group_head WHERE group_sha=?",Long.class,r.groupSha());}

    @Test void findsApprovedPolicyBeforeFinalGroupAndIsolatesDomains(){
        var r=receipt("short",3);var approval=approved(r);var selected=select(r,approval,null);
        assertEquals(approval,selected.approvalSha());assertEquals(r.groupSha(),read(r).group().key());
        assertEquals(r.policySha(),read(r).policy().sha());assertEquals(input(r),read(r).group().input());
        assertTrue(service.read(new EconomyScope("tenant","Client"),List.of(input(r)),false).isEmpty());
        assertTrue(service.read(new EconomyScope("Tenant","client"),List.of(input(r)),false).isEmpty());
        assertThrows(UnsupportedOperationException.class,()->service.read(domain,List.of(input(r)),false).clear());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM economy_budget_group_member",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM economy_budget_group_revision",Integer.class));
    }
    @Test void selectionChangesInvalidateOldAndNewGroupsAtomicallyWithoutChangingApprovalOriginals(){
        var first=receipt("short",3);var next=receipt("short",4);var a=approved(first);var b=approved(next);
        head(first);head(next);select(first,a,null);assertEquals(2,generation(first));
        assertEquals(a,select(first,a,null).approvalSha());assertEquals(2,generation(first));
        assertThrows(ScopedGroupPolicies.Conflict.class,()->select(next,b,null));assertEquals(a,read(first).approvalSha());
        assertThrows(IllegalStateException.class,()->tx.execute(s->{service.select(domain,input(next),b,a);throw new IllegalStateException("rollback");}));
        assertEquals(a,read(first).approvalSha());assertEquals(2,generation(first));assertEquals(1,generation(next));
        select(next,b,a);assertEquals(b,read(next).approvalSha());assertEquals(3,generation(first));assertEquals(2,generation(next));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM economy_budget_publish_approval",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM economy_budget_group_study",Integer.class));
    }
    @Test void withdrawalImmediatelyRemovesDiscoveryAndCannotResurrectOriginal(){
        var r=receipt("short",3);var a=approved(r);head(r);select(r,a,null);
        jdbc.update("UPDATE economy_budget_publish_approval SET withdrawn=TRUE WHERE approval_sha=?",a);
        assertNull(read(r));assertEquals(3,generation(r));assertThrows(ScopedGroupPolicies.Conflict.class,()->select(r,a,a));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE economy_budget_publish_approval SET withdrawn=FALSE WHERE approval_sha=?",a));
    }
    @Test void deduplicates129InputsIntoTwoIndexedReadsAndEmptyInputDoesNoSql(){
        var inputs=new ArrayList<GroupMeanPricing.ScopeInput>();
        for(int i=0;i<129;i++){var r=receipt("bucket-"+i,3);select(r,approved(r),null);inputs.add(input(r));}
        inputs.add(inputs.getFirst());reads.set(0);assertEquals(129,service.read(domain,inputs,false).size());assertEquals(2,reads.get());
        reads.set(0);assertTrue(service.read(domain,List.of(),false).isEmpty());assertEquals(0,reads.get());
        var plan=jdbc.queryForMap("EXPLAIN SELECT group_sha FROM economy_budget_group_study FORCE INDEX(study_by_scope_input) WHERE tenant_id=? AND client_id=? AND scope_input_sha=?",domain.tenantId(),domain.clientId(),inputs.getFirst().key());
        assertEquals("study_by_scope_input",plan.get("key"));
    }
    @Test void malformedApprovedOriginalsNeverBecomeMissingDataOrSelectableDefaults(){
        var r=receipt("short",3);String body=json.writeValueAsString(r);
        for(String invalid:List.of(
                body.replace("\"estimatedCases\":10","\"estimatedCases\":9"),
                body.replace("\"absoluteErrorSum\":2","\"absoluteErrorSum\":3"),
                body.replace("\"positiveCostP90RelativeError\":0.40","\"positiveCostP90RelativeError\":0.60"),
                body.replace("\"minSupport\":3","\"minSupport\":0"),
                body.replace("\"minSupport\":3","\"minSupport\":\"3\""),
                body.replace("\"slot\":0,",""),
                body.replace("\"inputSha\":\""+r.inputSha(),"\"inputSha\":\""+"0".repeat(64)),
                body.substring(0,body.length()-1)+",\"unknown\":true}",
                body.replaceFirst("\\{","{\"purpose\":\"duplicate\","))){
            assertNotEquals(body,invalid,"fixture replacement must actually mutate original");
            String a=approved(invalid);assertThrows(ScopedGroupPolicies.Corrupt.class,()->select(r,a,null));
        }
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM economy_budget_group_study",Integer.class));
    }
    @Test void incorrectlyBoundSelectionIsCorruptionNotOneWen(){
        var r=receipt("short",3);var other=receipt("long",3);var a=approved(r);
        jdbc.update("INSERT INTO economy_budget_group_study(tenant_id,client_id,group_sha,approval_sha,scope_input_sha) VALUES(?,?,?,?,?)",domain.tenantId(),domain.clientId(),r.groupSha(),a,input(other).key());
        assertThrows(ScopedGroupPolicies.Corrupt.class,()->read(other));
        assertThrows(ScopedGroupPolicies.Conflict.class,()->select(other,a,null));
    }
    @Test void allZeroCostHoldoutRequiresExactZeroErrorWithoutInventingPositiveDenominator(){
        var r=receipt("short",3);var raw=json.writeValueAsString(r);
        var zero=raw.replace("\"actualSum\":10","\"actualSum\":0")
                .replace("\"absoluteErrorSum\":2","\"absoluteErrorSum\":0")
                .replace("\"positiveCostP90RelativeError\":0.40","\"positiveCostP90RelativeError\":0");
        var a=approved(zero);select(r,a,null);assertEquals(a,read(r).approvalSha());
        var invalid=approved(zero.replace("\"absoluteErrorSum\":0","\"absoluteErrorSum\":0.001"));
        assertThrows(ScopedGroupPolicies.Corrupt.class,()->select(r,invalid,a));
    }
    @Test void originalMigrationPreservesLegacySelectionsAndRequiresTransactions(){
        var r=receipt("short",3);var a=approved(r);
        jdbc.execute("ALTER TABLE economy_budget_group_study DROP INDEX study_by_scope_input,DROP COLUMN scope_input_sha");
        jdbc.update("INSERT INTO economy_budget_group_study(tenant_id,client_id,group_sha,approval_sha) VALUES(?,?,?,?)",domain.tenantId(),domain.clientId(),r.groupSha(),a);
        ddl("economy-group-study-scope-v1.sql",";");
        assertNull(read(r));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM economy_budget_group_study WHERE scope_input_sha IS NULL",Integer.class));
        assertThrows(IllegalStateException.class,()->service.read(domain,List.of(input(r)),true));
        assertThrows(IllegalStateException.class,()->service.select(domain,input(r),a,null));
        assertThrows(ScopedGroupPolicies.Conflict.class,()->select(r,a,null));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM economy_budget_group_study",Integer.class));
    }
    @Test void lockedDiscoveryBlocksWithdrawalOnActualMysqlConnection()throws Exception {
        var r=receipt("short",3);var a=approved(r);select(r,a,null);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            var holder=pool.submit(()->tx.execute(s->{assertEquals(1,service.read(domain,List.of(input(r)),true).size());entered.countDown();
                try{assertTrue(release.await(15,TimeUnit.SECONDS));}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}return null;}));
            Future<Integer> writer=null;
            try{
                assertTrue(entered.await(10,TimeUnit.SECONDS));
                writer=pool.submit(()->jdbc.update("UPDATE economy_budget_publish_approval SET withdrawn=TRUE WHERE approval_sha=?",a));
                awaitLock(writer,"economy_budget_publish_approval");
            }finally{release.countDown();}
            holder.get(10,TimeUnit.SECONDS);assertNotNull(writer);assertEquals(1,writer.get(10,TimeUnit.SECONDS));
        }
        assertNull(read(r));
    }
    @Test void lockedDiscoveryBlocksSelectorReplacementButNotUnrelatedSelection()throws Exception {
        var r=receipt("short",3);var a=approved(r);select(r,a,null);
        var next=receipt("short",4);var b=approved(next);var other=receipt("long",3);var c=approved(other);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            var holder=pool.submit(()->tx.execute(s->{service.read(domain,List.of(input(r)),true);entered.countDown();
                try{assertTrue(release.await(15,TimeUnit.SECONDS));}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}return null;}));
            Future<ScopedGroupPolicies.Selected> writer=null;
            try{
                assertTrue(entered.await(10,TimeUnit.SECONDS));
                assertEquals(c,pool.submit(()->select(other,c,null)).get(5,TimeUnit.SECONDS).approvalSha());
                writer=pool.submit(()->select(next,b,a));awaitLock(writer,"economy_budget_group_study");
            }finally{release.countDown();}
            holder.get(10,TimeUnit.SECONDS);assertNotNull(writer);assertEquals(b,writer.get(10,TimeUnit.SECONDS).approvalSha());
        }
    }
    private void awaitLock(Future<?> writer,String table)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(System.nanoTime()<deadline){
            int n=jdbc.queryForObject("SELECT COUNT(*) FROM performance_schema.data_lock_waits w JOIN performance_schema.data_locks l ON l.ENGINE=w.ENGINE AND l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID WHERE l.OBJECT_SCHEMA=? AND l.OBJECT_NAME=?",Integer.class,database,table);
            if(n>0)return;if(writer.isDone())break;Thread.sleep(20);
        }
        fail("No actual MySQL lock wait observed");
    }
}
