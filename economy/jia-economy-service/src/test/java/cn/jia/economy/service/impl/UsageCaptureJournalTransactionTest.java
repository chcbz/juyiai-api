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
    Submission sub(String receipt,String kind,String id,String rev,String prev,String sha){return new Submission("request-"+receipt,sha,receipt,kind,id,rev,prev,sha,kind.equals("RESPONSE")?"0":null,"{\"test\":1}");}
    @Test void incompleteOrderedEvidenceInvalidatesOnlyRegisteredSampleAndConflictCommitsBefore409() throws Exception {
        var ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        try(var in=getClass().getResourceAsStream("/db/economy-usage-capture.sql");var c=ds.getConnection()){
            String sql=new String(in.readAllBytes(),StandardCharsets.UTF_8).replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin","");RunScript.execute(c,new StringReader(sql));}
        var config=new Configuration(new Environment("test",new SpringManagedTransactionFactory(),ds));config.addMapper(EconomyUsageCaptureMapper.class);
        var db=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config)).getMapper(EconomyUsageCaptureMapper.class);
        var journal=new UsageCaptureJournalImpl(db);var jdbc=new JdbcTemplate(ds);var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        for(String sampleId:new String[]{sample,h}) jdbc.update("INSERT INTO economy_task_cost_sample(sample_id,tenant_id,client_id,owner_jiacn,task_id,quote_id,sample_definition_version,initial_requirement_sha256,initial_input_snapshot_sha256,initial_feature_sha256,lifecycle_state,evidence_revision,projection_revision,sample_state) VALUES(?,'t','c','own','task',?,'v',?,?,?,'ACCEPTED',1,1,'READY')",sampleId,sampleId,h,h,h);
        jdbc.update("INSERT INTO economy_task_cost_sample_member VALUES(?,'exec','r',?)",sample,h);
        var later=sub("second","REQUEST",h,"2","a".repeat(64),"b".repeat(64));var accepted=tx.execute(st->journal.register(b,later));assertEquals(State.DURABLE,accepted.state());
        assertEquals(accepted,tx.execute(st->journal.readReceipt(b,"second")));assertEquals(accepted,tx.execute(st->journal.register(b,later)));
        assertEquals(2L,jdbc.queryForObject("SELECT evidence_revision FROM economy_task_cost_sample WHERE sample_id=?",Long.class,sample));
        assertEquals("READY",jdbc.queryForObject("SELECT sample_state FROM economy_task_cost_sample WHERE sample_id=?",String.class,h));
        var response=sub("response","RESPONSE",sample,"1",null,"c".repeat(64));assertEquals(State.PENDING,tx.execute(st->journal.register(b,response)).state());
        assertEquals(State.PENDING,tx.execute(st->journal.readReceipt(b,"response")).state());
        var broken=sub("first","REQUEST",h,"1",null,"d".repeat(64));assertEquals(State.CONFLICT,tx.execute(st->journal.register(b,broken)).state());
        assertEquals(State.CONFLICT,tx.execute(st->journal.readReceipt(b,"second")).state());
        assertEquals(3L,jdbc.queryForObject("SELECT input_revision FROM economy_usage_capture",Long.class));
        assertEquals("WAITING_EVIDENCE",jdbc.queryForObject("SELECT sample_state FROM economy_task_cost_sample WHERE sample_id=?",String.class,sample));
    }
}
