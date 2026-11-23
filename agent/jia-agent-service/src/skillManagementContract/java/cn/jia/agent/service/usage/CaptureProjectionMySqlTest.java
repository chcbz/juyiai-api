package cn.jia.agent.service.usage;

import cn.jia.agent.skill.SkillManagementMySqlFixture;
import cn.jia.core.security.FixedTenantAdmissionPolicy;
import cn.jia.economy.mapper.EconomyUsageCaptureMapper;
import cn.jia.economy.service.impl.UsageCaptureJournalImpl;
import cn.jia.economy.service.impl.UsageCaptureProjectionStore;
import cn.jia.economy.usage.UsageCaptureJournal.*;
import cn.jia.task.mapper.ScheduledWorkMapper;
import cn.jia.task.scheduling.ScheduleService;
import cn.jia.task.scheduling.ScheduledWorkStore;
import org.junit.jupiter.api.Test;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Reader and writer use independent MySQL connections; scheduling shares the evidence transaction. */
class CaptureProjectionMySqlTest extends SkillManagementMySqlFixture {
    final String h="e".repeat(64),sample="f".repeat(64);
    final Binding b=new Binding("t","c","own","agent","task","exec","r",h);
    Submission sub(String receipt,String kind,String id,String rev,String prev,String sha){return new Submission("request-"+receipt,sha,receipt,kind,id,rev,prev,sha,kind.equals("RESPONSE")?"0":null,"{\"test\":1}",h);}
    @Test void mysqlConcurrentClosureCannotBeOverwrittenByAnOpenCaptureProjection() throws Exception {
        String database="cyf_capture_"+java.util.UUID.randomUUID().toString().replace("-","");
        admin.execute("CREATE DATABASE `"+database+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
        try {var ds=source(database);var jdbc=new JdbcTemplate(ds);
            new ResourceDatabasePopulator(new ClassPathResource("db/task-scheduled-work-v1.sql")).execute(ds);
            new cn.jia.economy.config.EconomyUsageCostSchemaInitializer(ds).afterPropertiesSet();
            var conf=new Configuration(new Environment("closure",new SpringManagedTransactionFactory(),ds));
            conf.setMapUnderscoreToCamelCase(true);conf.addMapper(EconomyUsageCaptureMapper.class);conf.addMapper(ScheduledWorkMapper.class);
            var sql=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(conf));var db=sql.getMapper(EconomyUsageCaptureMapper.class);
            var manager=new DataSourceTransactionManager(ds);var tx=new TransactionTemplate(manager);
            var store=proxy(new UsageCaptureProjectionStore(db),manager,cn.jia.economy.usage.UsageCaptureProjection.class);
            var scope=new FixedTenantAdmissionPolicy("t","c");var schedules=new ScheduledWorkStore(sql.getMapper(ScheduledWorkMapper.class),scope,manager);
            var beans=new DefaultListableBeanFactory();beans.registerSingleton("schedules",schedules);
            var handler=new NativeCaptureProjection(store,beans.getBeanProvider(ScheduleService.class),scope);
            var journal=proxy(new UsageCaptureJournalImpl(db,event->handler.changed((cn.jia.economy.usage.UsageCaptureProjection.Key)event)),manager,cn.jia.economy.usage.UsageCaptureJournal.class);
            tx.execute(st->{journal.register(b,sub("rollback","PLAN",h,"1",null,h));st.setRollbackOnly();return null;});
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM task_scheduled_work",Integer.class));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM economy_usage_capture",Integer.class));
            tx.execute(st->journal.register(b,sub("p","PLAN",h,"1",null,h)));
            var pk=jdbc.queryForObject("SELECT id FROM economy_usage_capture",Long.class);
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
                var group=new cn.jia.economy.usage.UsageCaptureProjection.DispatchGroup("NATIVE_DISPATCH_GROUP_V1",sample,h,sample,"unverified",h);
                assertFalse(store.publish(other,fresh.inputRevision(),waiting,group));assertFalse(store.publish(key,old.inputRevision(),waiting,group));
                assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM economy_usage_capture_dispatch_group",Integer.class));
                var broken=new cn.jia.economy.usage.UsageCaptureProjection.DispatchGroup("NATIVE_DISPATCH_GROUP_V1",null,h,sample,"unverified",h);
                assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->store.publish(key,fresh.inputRevision(),waiting,broken));
                assertEquals(fresh,store.pending(key));assertTrue(store.publish(key,fresh.inputRevision(),waiting,group));
                assertEquals(h,jdbc.queryForObject("SELECT g.route_sha256 FROM economy_usage_capture_dispatch_group g JOIN economy_usage_capture c ON c.id=g.capture_pk AND c.input_revision=g.projection_revision AND c.projection_revision=g.projection_revision WHERE c.coverage_state='WAITING_EVIDENCE' AND g.plan_evidence_sha256=?",String.class,h));
                assertNull(store.pending(key));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM task_scheduled_work",Integer.class));
            }finally{release.countDown();thread.shutdownNow();assertTrue(thread.awaitTermination(15,java.util.concurrent.TimeUnit.SECONDS));}
        }finally{admin.execute("DROP DATABASE `"+database+"`");}
    }
    private static <T>T proxy(Object target,DataSourceTransactionManager manager,Class<T> type){
        var p=new org.springframework.aop.framework.ProxyFactory(target);p.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(manager,new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));return type.cast(p.getProxy());
    }
}
