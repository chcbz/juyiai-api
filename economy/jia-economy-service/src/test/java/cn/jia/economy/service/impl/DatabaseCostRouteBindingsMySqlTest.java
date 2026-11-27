package cn.jia.economy.service.impl;

import cn.jia.economy.config.EconomyMySqlTestFixture;
import cn.jia.economy.config.CostRouteBindingSchemaInitializer;
import cn.jia.economy.mapper.CostRouteBindingMapper;
import cn.jia.economy.pricing.*;
import cn.jia.economy.pricing.CostPricingRoute;
import cn.jia.economy.pricing.CostPricingRoute.*;
import cn.jia.economy.service.EconomyScope;
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
class DatabaseCostRouteBindingsMySqlTest {
    private final EconomyMySqlTestFixture fixture=new EconomyMySqlTestFixture();
    private final EconomyScope scope=new EconomyScope("Tenant","Client");
    private String database;private JdbcTemplate jdbc;private TransactionTemplate tx;private DatabaseCostRouteBindings service;private final AtomicInteger reads=new AtomicInteger();
    private static CostRouteReference ref(String namespace){return new CostRouteReference("MODEL_RESPONSE","INFERENCE",namespace,"same-model","TOKENS_V1");}
    private static TokenRoute route(String channel){return new TokenRoute(channel,"same-model","standard","TOKENS_V1");}
    @BeforeEach void setup()throws Exception{
        fixture.start();var db=fixture.newDatabase("costroutes");jdbc=db.jdbc();database=jdbc.queryForObject("SELECT DATABASE()",String.class);tx=new TransactionTemplate(new DataSourceTransactionManager(db.dataSource()));
        new CostRouteBindingSchemaInitializer(jdbc).afterPropertiesSet();
        var config=new org.apache.ibatis.session.Configuration();config.setMapUnderscoreToCamelCase(true);config.addMapper(CostRouteBindingMapper.class);
        var factory=new SqlSessionFactoryBean();factory.setDataSource(db.dataSource());factory.setConfiguration(config);
        var mapper=new SqlSessionTemplate(factory.getObject()).getMapper(CostRouteBindingMapper.class);
        var counted=(CostRouteBindingMapper)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{CostRouteBindingMapper.class},(proxy,method,args)->{
            if(method.getName().equals("read"))reads.incrementAndGet();
            try{return method.invoke(mapper,args);}catch(java.lang.reflect.InvocationTargetException error){throw error.getCause();}
        });service=new DatabaseCostRouteBindings(counted,new ObjectMapper());
    }
    @AfterEach void close(){fixture.close();}
    @Test void sameModelDifferentNamespacesAndDomainsRemainDistinct(){
        var a=tx.execute(s->service.publish(scope,ref("provider-a"),0,route("channel-a")));
        var b=tx.execute(s->service.publish(scope,ref("provider-b"),0,route("channel-b")));
        assertNotEquals(a.sha(),b.sha());var values=service.read(scope,List.of(ref("provider-a"),ref("provider-b")),false);
        assertEquals(route("channel-a"),values.get(ref("provider-a")).route());assertEquals(route("channel-b"),values.get(ref("provider-b")).route());
        assertTrue(service.read(new EconomyScope("tenant","Client"),List.of(ref("provider-a")),false).isEmpty());
        assertTrue(service.read(scope,List.of(ref("Provider-a")),false).isEmpty());
        assertEquals(a,service.read(scope,List.of(ref("provider-a")),false).get(ref("provider-a")));
    }
    @Test void immutableRevisionsWithdrawalAndReplayNeverMeanZeroCost(){
        var r=ref("provider");var one=tx.execute(s->service.publish(scope,r,0,route("a")));
        assertEquals(one,tx.execute(s->service.publish(scope,r,0,route("a"))));
        var two=tx.execute(s->service.publish(scope,r,1,route("b")));assertEquals(2,two.revision());
        assertThrows(CostRouteBindings.Conflict.class,()->tx.execute(s->service.publish(scope,r,0,route("a"))));
        assertEquals(Long.valueOf(3),tx.execute(s->service.withdraw(scope,r,2)));assertEquals(Long.valueOf(3),tx.execute(s->service.withdraw(scope,r,2)));
        assertTrue(service.read(scope,List.of(r),false).isEmpty());
        var tombstone=service.inspect(scope,r).orElseThrow();assertEquals(3,tombstone.revision());assertNull(tombstone.route());
        assertTrue(service.inspect(scope,ref("missing")).isEmpty());
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM economy_cost_route_revision",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM economy_cost_route_revision WHERE route_json IS NULL",Integer.class));
    }
    @Test void tokenUnitAndExplicitZeroAreTypedWithoutBusinessCategorySwitches(){
        var refs=List.of(ref("model"),new CostRouteReference("TOOL","PROCESS","tool","processor","SECONDS_V1"),new CostRouteReference("LOCAL","RENDER","local","renderer","FREE_CONTRACT_V1"));
        List<CostPricingRoute> routes=List.of(route("channel"),new UnitRoute(new TaskQuoteCalculator.ToolKey("tools","processor","seconds")),new ZeroRoute("local-free-1"));
        for(int i=0;i<refs.size();i++){int n=i;tx.execute(s->service.publish(scope,refs.get(n),0,routes.get(n)));}
        var values=service.read(scope,refs,false);for(int i=0;i<refs.size();i++)assertEquals(routes.get(i),values.get(refs.get(i)).route());
        assertTrue(service.read(scope,List.of(ref("missing")),false).isEmpty());
    }
    @Test void batchLookupDeduplicatesAndUses128ReferencePages(){
        var refs=new ArrayList<CostRouteReference>();for(int i=0;i<129;i++)refs.add(ref("provider-"+i));
        tx.executeWithoutResult(s->{for(var r:refs)service.publish(scope,r,0,route("channel"));});
        refs.add(refs.getFirst());reads.set(0);assertEquals(129,service.read(scope,refs,false).size());assertEquals(2,reads.get());
        reads.set(0);assertTrue(service.read(scope,List.of(),false).isEmpty());assertEquals(0,reads.get());
    }
    @Test void corruptRowsAndMismatchedBasisAreNotMissingRouteFallback(){
        var r=ref("provider");assertThrows(IllegalArgumentException.class,()->tx.execute(s->service.publish(scope,r,0,new TokenRoute("c","m","standard","OTHER"))));
        tx.executeWithoutResult(s->service.publish(scope,r,0,route("channel")));
        jdbc.update("UPDATE economy_cost_route_revision SET binding_sha=?","0".repeat(64));
        assertThrows(IllegalStateException.class,()->service.read(scope,List.of(r),false));
        assertThrows(IllegalStateException.class,()->tx.execute(s->service.withdraw(scope,r,1)));
    }
    @Test void rollbackPreservesHeadAndRevisionTogetherAndLockedReadNeedsTransaction(){
        var r=ref("provider");assertThrows(IllegalStateException.class,()->service.publish(scope,r,0,route("a")));
        assertThrows(IllegalStateException.class,()->service.read(scope,List.of(r),true));
        tx.executeWithoutResult(s->service.publish(scope,r,0,route("a")));
        assertThrows(IllegalStateException.class,()->tx.execute(s->{service.publish(scope,r,1,route("b"));throw new IllegalStateException("fixture rollback");}));
        assertEquals(1,service.read(scope,List.of(r),false).get(r).revision());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM economy_cost_route_revision",Integer.class));
    }
    @Test void sharedRecheckLocksMappingAgainstConcurrentWithdrawal()throws Exception{
        var r=ref("provider");tx.executeWithoutResult(s->service.publish(scope,r,0,route("a")));
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            var holder=pool.submit(()->tx.execute(s->{assertEquals(1,service.read(scope,List.of(r),true).size());entered.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("release timeout");}catch(InterruptedException e){throw new AssertionError(e);}return null;}));
            Future<Long> writer=null;
            try{
                assertTrue(entered.await(10,TimeUnit.SECONDS));
                writer=pool.submit(()->tx.execute(s->service.withdraw(scope,r,1)));
                awaitMappingLockWait(writer);
            }finally{release.countDown();}
            assertNotNull(writer);
            holder.get(10,TimeUnit.SECONDS);assertEquals(2L,writer.get(10,TimeUnit.SECONDS));
        }
        assertTrue(service.read(scope,List.of(r),false).isEmpty());
    }
    @Test void competingPublishersCannotOverwriteTheWinningRevision()throws Exception{
        var r=ref("provider");tx.executeWithoutResult(s->service.publish(scope,r,0,route("original")));
        var written=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            var first=pool.submit(()->tx.execute(s->{
                var result=service.publish(scope,r,1,route("winner"));written.countDown();
                try{assertTrue(release.await(15,TimeUnit.SECONDS));}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
                return result;
            }));
            Future<CostRouteBindings.Binding> second=null;
            try{
                assertTrue(written.await(10,TimeUnit.SECONDS));
                second=pool.submit(()->tx.execute(s->service.publish(scope,r,1,route("loser"))));
                awaitMappingLockWait(second);
            }finally{release.countDown();}
            assertEquals(route("winner"),first.get(10,TimeUnit.SECONDS).route());
            assertNotNull(second);var losing=second;
            assertInstanceOf(CostRouteBindings.Conflict.class,
                    assertThrows(ExecutionException.class,()->losing.get(10,TimeUnit.SECONDS)).getCause());
        }
        assertEquals(route("winner"),service.read(scope,List.of(r),false).get(r).route());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM economy_cost_route_revision",Integer.class));
    }
    @Test void migrationReplayPreservesBindingsAndHeadCannotPointToMissingRevision(){
        var r=ref("provider");var original=tx.execute(s->service.publish(scope,r,0,route("channel")));
        new ResourceDatabasePopulator(new ClassPathResource("db/economy-cost-route-binding.sql")).execute(jdbc.getDataSource());
        new CostRouteBindingSchemaInitializer(jdbc).afterPropertiesSet();
        assertEquals(original,service.read(scope,List.of(r),false).get(r));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update(
                "UPDATE economy_cost_route_head SET revision=99"));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update(
                "DELETE FROM economy_cost_route_revision"));
        assertEquals(original,service.read(scope,List.of(r),false).get(r));
    }
    @Test void maintenanceResumesPartialCreationWithoutInventingMappings(){
        jdbc.execute("DROP TABLE economy_cost_route_head");
        new CostRouteBindingSchemaInitializer(jdbc).afterPropertiesSet();
        assertTrue(service.read(scope,List.of(ref("missing")),false).isEmpty());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM economy_cost_route_revision",Integer.class));
    }
    @Test void maintenanceRejectsDriftBeforeCreatingMissingTable(){
        jdbc.execute("DROP TABLE economy_cost_route_head");
        jdbc.execute("ALTER TABLE economy_cost_route_revision MODIFY binding_sha VARCHAR(64) NOT NULL");
        assertThrows(IllegalStateException.class,()->new CostRouteBindingSchemaInitializer(jdbc).afterPropertiesSet());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='economy_cost_route_head'",Integer.class));
    }
    @Test void maintenanceRejectsMissingReferenceConstraint(){
        jdbc.execute("ALTER TABLE economy_cost_route_head DROP FOREIGN KEY fk_cost_route_head_revision");
        assertThrows(IllegalStateException.class,()->new CostRouteBindingSchemaInitializer(jdbc).afterPropertiesSet());
    }
    @Test void unrelatedMappingCanCommitWhileAdoptedMappingIsShareLocked()throws Exception{
        var adopted=ref("adopted");var other=ref("other");
        tx.executeWithoutResult(s->{service.publish(scope,adopted,0,route("a"));service.publish(scope,other,0,route("b"));});
        var acquired=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            var holder=pool.submit(()->tx.execute(s->{
                service.read(scope,List.of(adopted),true);acquired.countDown();
                try{assertTrue(release.await(15,TimeUnit.SECONDS));}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}return null;
            }));
            try{
                assertTrue(acquired.await(10,TimeUnit.SECONDS));
                var writer=pool.submit(()->tx.execute(s->service.publish(scope,other,1,route("changed"))));
                assertEquals(2,writer.get(5,TimeUnit.SECONDS).revision());
                assertEquals(1,release.getCount(),"unrelated write committed before releasing adopted mapping lock");
            }finally{release.countDown();}
            holder.get(10,TimeUnit.SECONDS);
        }
    }
    private void awaitMappingLockWait(Future<?> writer)throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(System.nanoTime()<deadline){
            int waits=jdbc.queryForObject("SELECT COUNT(*) FROM performance_schema.data_lock_waits w "
                    +"JOIN performance_schema.data_locks l ON l.ENGINE=w.ENGINE "
                    +"AND l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID "
                    +"WHERE l.OBJECT_SCHEMA=? AND l.OBJECT_NAME='economy_cost_route_head'",Integer.class,database);
            if(waits>0)return;
            if(writer.isDone())break;
            Thread.sleep(20);
        }
        fail("Must observe an actual MySQL head lock wait, not an unfinished future");
    }

}
