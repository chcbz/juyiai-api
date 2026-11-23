package cn.jia.economy.service.impl;

import cn.jia.economy.mapper.EconomyUsageCaptureMapper;
import cn.jia.economy.usage.UsageCaptureJournal.*;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.h2.jdbcx.JdbcDataSource;
import org.h2.tools.RunScript;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class UsageCaptureJournalTransactionTest {
    final String h="e".repeat(64),sample="f".repeat(64);
    final Binding b=new Binding("t","c","own","agent","task","exec","r",h);
    Submission sub(String receipt,String kind,String id,String rev,String prev,String sha){return new Submission("request-"+receipt,sha,receipt,kind,id,rev,prev,sha,kind.equals("RESPONSE")?"0":null,"{\"test\":1}",h);}
    @Test void crashAfterIndependentLedgerCommitReplaysOriginalWithoutDuplicateOrFalseZero() throws Exception {
        var ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        for(String script:new String[]{"economy-usage-cost","economy-usage-capture"})try(var in=getClass().getResourceAsStream("/db/"+script+".sql");var c=ds.getConnection()){
            RunScript.execute(c,new StringReader(new String(in.readAllBytes(),StandardCharsets.UTF_8).replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin","")));}
        var config=new Configuration(new Environment("test",new SpringManagedTransactionFactory(),ds));config.setMapUnderscoreToCamelCase(true);
        config.addMapper(EconomyUsageCaptureMapper.class);config.addMapper(cn.jia.economy.mapper.EconomyUsageCostMapper.class);
        var sql=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));var manager=new DataSourceTransactionManager(ds);var tx=new TransactionTemplate(manager);
        var journal=proxy(new UsageCaptureJournalImpl(sql.getMapper(EconomyUsageCaptureMapper.class),event->{}),manager,cn.jia.economy.usage.UsageCaptureJournal.class);
        var costs=proxy(new UsageCostServiceImpl(sql.getMapper(cn.jia.economy.mapper.EconomyUsageCostMapper.class),new tools.jackson.databind.ObjectMapper()),manager,cn.jia.economy.usage.UsageCostService.class);
        var settlement=proxy(new UsageCaptureSettlement(costs,journal),manager,Settlement.class);
        ResponseOrigin adapter=(raw,previous,lookup)->new Origin(sample,false,123);var first=sub("r1","RESPONSE",sample,"1",null,h);
        var bad=new Submission("bad",h,"bad","RESPONSE",sample,"1",null,h,"1",first.canonicalEvidence(),h);
        tx.execute(s->journal.register(b,bad));var stale=tx.execute(s->journal.bindResponse(b,"bad",adapter)).work();
        assertEquals(State.CONFLICT,settlement.apply(stale).state());assertEquals(State.CONFLICT,tx.execute(s->journal.readReceipt(b,"bad")).state());
        var second=new Submission("q2",sample,"r2","RESPONSE",sample,"2",h,sample,"2","{\"test\":2}",h);
        tx.execute(s->journal.register(b,second));assertNull(tx.execute(s->journal.bindResponse(b,"r2",adapter)).work());
        assertEquals(State.PENDING,tx.execute(s->journal.readRecord(b,sample)).state());
        tx.execute(s->journal.register(b,first));var work=tx.execute(s->journal.bindResponse(b,"r1",adapter)).work();
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,()->tx.execute(s->settlement.apply(work)));
        costs.record(work.observation()); // simulated stop before saving ACK
        assertEquals(State.PENDING,tx.execute(s->journal.readReceipt(b,"r1")).state());
        long pk=tx.execute(s->journal.bindResponse(b,"r1",adapter)).recoveryPk();assertTrue(pk>0);
        assertEquals(State.NOT_FOUND,tx.execute(s->journal.recover("other","c",pk,"r1",null,adapter)).state());
        assertEquals(State.NOT_FOUND,tx.execute(s->journal.recover("t","other",pk,"r1",null,adapter)).state());
        assertEquals(0,tx.execute(s->journal.recover("t","c",pk,"bad",null,adapter)).recoveryPk());
        var recovered=tx.execute(s->journal.recover("t","c",pk,"r1",null,adapter)).work();assertEquals(work,recovered);
        var ack=settlement.apply(recovered);assertEquals(State.DURABLE,ack.state());
        var ledger=new tools.jackson.databind.ObjectMapper().readTree(ack.ackJson()).get("ledger");assertEquals("1",ledger.get("revision").asText());assertTrue(ledger.get("costYuan").isNull());assertEquals("PENDING_USAGE",ledger.get("status").asText());
        assertEquals(ack,tx.execute(s->journal.readReceipt(b,"r1")));assertEquals(ack,settlement.apply(work));
        tx.execute(s->journal.register(b,sub("alias","RESPONSE",sample,"1",null,h)));assertEquals(State.DURABLE,tx.execute(s->journal.bindResponse(b,"alias",adapter)).state());
        var jdbc=new JdbcTemplate(ds);assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM economy_usage_revision",Integer.class));assertEquals(1L,jdbc.queryForObject("SELECT pending_usage FROM economy_task_cost",Long.class));
        var before=tx.execute(s->journal.readRecord(b,sample));var json=new tools.jackson.databind.ObjectMapper();assertEquals("1",json.readTree(before.ackJson()).get("sourceRevision").asText());
        // A distinct accepted ledger revision does not rewrite the old source ACK or advance sourceRevision.
        var category=cn.jia.economy.pricing.ModelTokenPrice.Category.INPUT;
        var price=new cn.jia.economy.pricing.ModelTokenPrice("fixture","actual","standard","r","fixture-only",java.util.Map.of(category,new java.math.BigDecimal("1.2")),null,java.util.Map.of());
        var usage=new cn.jia.economy.usage.TokenUsageCost.Usage("fixture","actual","standard","disjoint",java.util.Map.of(category,100000L));
        costs.record(new cn.jia.economy.usage.UsageCostService.Observation(work.observation().binding(),"independent",1,"{}",usage,price,124));
        var repriced=tx.execute(s->journal.readRecord(b,sample));assertEquals("2",json.readTree(repriced.ackJson()).get("ledgerRevision").asText());
        var next=tx.execute(s->journal.bindResponse(b,"r2",(raw,previous,lookup)->{assertEquals(first.canonicalEvidence(),previous);return new Origin(sample,false,125,new Facts(null,null,null,"disjoint",java.util.Map.of("INPUT","100000")));}));
        assertEquals(State.DURABLE,settlement.apply(next.work()).state());var head=json.readTree(tx.execute(s->journal.readRecord(b,sample)).ackJson());assertEquals("2",head.get("sourceRevision").asText());assertEquals("3",head.get("ledgerRevision").asText());assertEquals(sample,head.get("evidenceSha256").asText());
        assertEquals(ack,tx.execute(s->journal.readReceipt(b,"r1")));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM economy_usage_attempt",Integer.class));
        assertEquals(0,new java.math.BigDecimal("0.12").compareTo(jdbc.queryForObject("SELECT cost_yuan FROM economy_task_cost",java.math.BigDecimal.class)));
        assertEquals(usage,costs.observationAt(work.observation().binding(),3).orElseThrow().normalizedUsage());
        assertEquals(State.DURABLE,settlement.apply(next.work()).state());
        var projection=new UsageCaptureProjectionStore(sql.getMapper(EconomyUsageCaptureMapper.class));
        var key=new cn.jia.economy.usage.UsageCaptureProjection.Key("t","c",pk);
        var snapshot=projection.pending(key);var rows=projection.page(key,"");
        assertEquals(1,rows.size());assertEquals(2,rows.getFirst().revisions());assertEquals("2",rows.getFirst().sourceRevision());
        assertTrue(projection.page(key,rows.getFirst().recordId()).isEmpty());
        var alien=new cn.jia.economy.usage.UsageCaptureProjection.Key("t","foreign",pk);
        assertNull(projection.pending(alien));assertTrue(projection.page(alien,"").isEmpty());
        var third=new Submission("q3",h,"r3","RESPONSE",sample,"3",sample,h,"3","{\"test\":3}",h);
        var plan=sub("plan","PLAN",h,"1",null,h);var plan2=sub("late-plan","PLAN",h,"2",h,sample);
        var originalAck=tx.execute(s->journal.register(b,plan2));
        var pending=tx.execute(s->journal.linkMetadata(b,"late-plan",(raw,prev,lookup)->{throw new AssertionError("missing predecessor");}));
        assertEquals(pk,pending.recoveryPk());assertEquals(originalAck.ackJson(),pending.ackJson());
        tx.execute(s->journal.register(b,plan));tx.execute(s->journal.linkMetadata(b,"plan",(raw,prev,lookup)->Link.ASSOCIATED));
        var state=cn.jia.economy.usage.UsageCaptureProjection.Coverage.WAITING_EVIDENCE;
        assertFalse(tx.execute(s->projection.publish(key,snapshot.inputRevision(),state)).booleanValue());
        var fresh=projection.pending(key);assertTrue(tx.execute(s->projection.publish(key,fresh.inputRevision(),state)).booleanValue());
        assertNull(projection.pending(key));
        var resumed=tx.execute(s->journal.recover("t","c",pk,"late-plan",(raw,prev,lookup)->{assertEquals(plan.canonicalEvidence(),prev);return Link.ASSOCIATED;},null));
        assertEquals(originalAck,resumed);assertEquals("2",json.readTree(tx.execute(s->journal.readRecord(b,h)).ackJson()).get("sourceRevision").asText());assertTrue(json.readTree(tx.execute(s->journal.readRecord(b,h)).ackJson()).get("ledgerRevision").isNull());
        String srcId="9".repeat(64);var src=sub("src","SOURCE",srcId,"1",null,sample);tx.execute(s->journal.register(b,src));
        assertEquals(State.DURABLE,tx.execute(s->journal.linkMetadata(b,"src",(ignored,previous,lookup)->Link.WAITING)).state());assertEquals(State.PENDING,tx.execute(s->journal.readRecord(b,srcId)).state());
        assertEquals(pk,tx.execute(s->journal.recover("t","c",pk,"src",(raw,previous,lookup)->Link.WAITING,null)).recoveryPk());
        assertEquals(0,tx.execute(s->journal.recover("t","c",pk,"src",(ignored,previous,lookup)->{assertTrue(lookup.apply(h).associated());return Link.ASSOCIATED;},null)).recoveryPk());
        assertEquals(State.DURABLE,tx.execute(s->journal.readRecord(b,srcId)).state());tx.execute(s->journal.linkMetadata(b,"src",(ignored,previous,lookup)->{throw new AssertionError("already associated");}));
        tx.execute(s->journal.register(b,third));var mismatch=tx.execute(s->journal.bindResponse(b,"r3",(raw,previous,lookup)->new Origin(sample,false,126,new Facts(null,null,null,"disjoint",java.util.Map.of("INPUT","100001")))));
        assertEquals(State.CONFLICT,settlement.apply(mismatch.work()).state());assertEquals(State.CONFLICT,tx.execute(s->journal.readRecord(b,sample)).state());
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM economy_usage_revision",Integer.class));assertEquals(0,new java.math.BigDecimal("0.12").compareTo(jdbc.queryForObject("SELECT cost_yuan FROM economy_task_cost",java.math.BigDecimal.class)));
    }
    @Test void mysqlConcurrentClosureCannotBeOverwrittenByAnOpenCaptureProjection() throws Exception {
        try(var fixture=new cn.jia.economy.config.EconomyMySqlTestFixture()){
            fixture.start();var database=fixture.newDatabase("closurecas");var ds=database.dataSource();
            new cn.jia.economy.config.EconomyUsageCostSchemaInitializer(ds).afterPropertiesSet();
            var conf=new Configuration(new Environment("closure",new SpringManagedTransactionFactory(),ds));
            conf.setMapUnderscoreToCamelCase(true);conf.addMapper(EconomyUsageCaptureMapper.class);
            var db=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(conf)).getMapper(EconomyUsageCaptureMapper.class);
            var manager=new DataSourceTransactionManager(ds);var tx=new TransactionTemplate(manager);
            var journal=proxy(new UsageCaptureJournalImpl(db,event->assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())),manager,cn.jia.economy.usage.UsageCaptureJournal.class);
            var store=proxy(new UsageCaptureProjectionStore(db),manager,cn.jia.economy.usage.UsageCaptureProjection.class);
            tx.execute(st->journal.register(b,sub("p","PLAN",h,"1",null,h)));
            var pk=database.jdbc().queryForObject("SELECT id FROM economy_usage_capture",Long.class);
            var key=new cn.jia.economy.usage.UsageCaptureProjection.Key("t","c",pk);
            var other=new cn.jia.economy.usage.UsageCaptureProjection.Key("t","other",pk);
            var old=store.pending(key);assertFalse(store.hasAssociated(key,sample));
            var held=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
            var thread=java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                var writer=thread.submit(()->tx.execute(st->{journal.register(b,sub("f","CAPTURE_FINAL",sample,"1",null,sample));
                    var linked=journal.linkMetadata(b,"f",(raw,prev,lookup)->Link.ASSOCIATED);held.countDown();
                    try{if(!release.await(15,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("reader stalled");}catch(InterruptedException ex){throw new AssertionError(ex);}
                    return linked;}));
                try{assertTrue(held.await(15,java.util.concurrent.TimeUnit.SECONDS));assertFalse(store.hasAssociated(key,sample));
                    assertEquals(old,store.pending(key));assertEquals(1,store.page(key,"").size());}finally{release.countDown();}
                assertEquals(State.DURABLE,writer.get(15,java.util.concurrent.TimeUnit.SECONDS).state());
                var waiting=cn.jia.economy.usage.UsageCaptureProjection.Coverage.WAITING_EVIDENCE;
                assertFalse(store.publish(key,old.inputRevision(),waiting));assertTrue(store.hasAssociated(key,sample));
                assertFalse(store.hasAssociated(other,sample));assertTrue(store.page(other,"").isEmpty());
                var fresh=store.pending(key);assertTrue(fresh.inputRevision()>old.inputRevision());assertEquals(2,store.page(key,"").size());
                assertFalse(store.publish(other,fresh.inputRevision(),waiting));assertTrue(store.publish(key,fresh.inputRevision(),waiting));
                assertNull(store.pending(key));
            }finally{release.countDown();thread.shutdownNow();assertTrue(thread.awaitTermination(15,java.util.concurrent.TimeUnit.SECONDS));}
        }
    }
    private static <T>T proxy(Object target,DataSourceTransactionManager manager,Class<T> type){
        var p=new org.springframework.aop.framework.ProxyFactory(target);p.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(manager,new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));return type.cast(p.getProxy());
    }
    @Test void incompleteOrderedEvidenceInvalidatesOnlyRegisteredSampleAndConflictCommitsBefore409() throws Exception {
        var ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        try(var in=getClass().getResourceAsStream("/db/economy-usage-capture.sql");var c=ds.getConnection()){
            String sql=new String(in.readAllBytes(),StandardCharsets.UTF_8).replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin","");RunScript.execute(c,new StringReader(sql));}
        var config=new Configuration(new Environment("test",new SpringManagedTransactionFactory(),ds));config.addMapper(EconomyUsageCaptureMapper.class);
        var db=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config)).getMapper(EconomyUsageCaptureMapper.class);
        var journal=new UsageCaptureJournalImpl(db,event->{});var jdbc=new JdbcTemplate(ds);var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        for(String sampleId:new String[]{sample,h}) jdbc.update("INSERT INTO economy_task_cost_sample(sample_id,tenant_id,client_id,owner_jiacn,task_id,quote_id,sample_definition_version,initial_requirement_sha256,initial_input_snapshot_sha256,initial_feature_sha256,lifecycle_state,evidence_revision,projection_revision,sample_state) VALUES(?,'t','c','own','task',?,'v',?,?,?,'ACCEPTED',1,1,'READY')",sampleId,sampleId,h,h,h);
        jdbc.update("INSERT INTO economy_task_cost_sample_member VALUES(?,'exec','r',?)",sample,h);
        // Revision 4 arrived first. A different attempt in revision 1 conflicts even with 2/3 absent.
        var separate=new Binding("t","c","own","agent",null,"other","r",sample);
        var fourth=sub("fourth","REQUEST",h,"4",h,sample);
        assertEquals(State.DURABLE,tx.execute(st->journal.register(separate,fourth)).state());
        var rebind=new Submission("q",h,"first","REQUEST",h,"1",null,h,null,"{}",sample);
        assertEquals(State.CONFLICT,tx.execute(st->journal.register(separate,rebind)).state());
        assertEquals(State.CONFLICT,tx.execute(st->journal.readReceipt(separate,"fourth")).state());
        assertEquals("RECORD_BINDING",jdbc.queryForObject("SELECT conflict_reason FROM economy_usage_capture WHERE execution_id='other'",String.class));
        var later=sub("second","REQUEST",h,"2","a".repeat(64),"b".repeat(64));var accepted=tx.execute(st->journal.register(b,later));assertEquals(State.DURABLE,accepted.state());
        assertEquals(accepted,tx.execute(st->journal.readReceipt(b,"second")));assertEquals(accepted,tx.execute(st->journal.register(b,later)));
        assertEquals(2L,jdbc.queryForObject("SELECT evidence_revision FROM economy_task_cost_sample WHERE sample_id=?",Long.class,sample));
        assertEquals("READY",jdbc.queryForObject("SELECT sample_state FROM economy_task_cost_sample WHERE sample_id=?",String.class,h));
        var response=sub("response","RESPONSE",sample,"1",null,"c".repeat(64));assertEquals(State.PENDING,tx.execute(st->journal.register(b,response)).state());
        assertEquals(State.PENDING,tx.execute(st->journal.readReceipt(b,"response")).state());
        ResponseOrigin adapter=(raw,previous,first)->new Origin(sample,false,123);
        assertEquals(State.PENDING,tx.execute(st->journal.bindResponse(b,"response",adapter)).state());
        assertEquals(State.PENDING,tx.execute(st->journal.bindResponse(b,"response",adapter)).state());
        var copied=new Binding("t","c","own","agent","task","copy","r",sample);
        var foreign=new Binding("other","c","own","agent","task","copy","r",sample);
        for(var other:new Binding[]{copied,foreign}){
            tx.execute(st->journal.register(other,response));
            var bound=tx.execute(st->journal.bindResponse(other,"response",adapter));
            assertEquals(other==copied?State.CONFLICT:State.PENDING,bound.state());
        }
        assertEquals(State.CONFLICT,tx.execute(st->journal.readReceipt(copied,"response")).state());
        assertEquals(State.PENDING,tx.execute(st->journal.readReceipt(b,"response")).state());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM economy_usage_response_origin",Integer.class));
        var broken=sub("first","REQUEST",h,"1",null,"d".repeat(64));assertEquals(State.CONFLICT,tx.execute(st->journal.register(b,broken)).state());
        assertEquals(State.CONFLICT,tx.execute(st->journal.readReceipt(b,"second")).state());
        assertEquals(3L,jdbc.queryForObject("SELECT input_revision FROM economy_usage_capture WHERE execution_id='exec'",Long.class));
        assertEquals("WAITING_EVIDENCE",jdbc.queryForObject("SELECT sample_state FROM economy_task_cost_sample WHERE sample_id=?",String.class,sample));
    }
}
