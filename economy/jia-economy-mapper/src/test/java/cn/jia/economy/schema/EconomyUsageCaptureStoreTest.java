package cn.jia.economy.schema;

import cn.jia.economy.mapper.EconomyUsageCaptureMapper;
import cn.jia.economy.mapper.EconomyUsageCaptureMapper.*;
import org.apache.ibatis.exceptions.PersistenceException;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.h2.tools.RunScript;
import org.junit.jupiter.api.Test;
import java.io.StringReader;
import java.nio.file.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class EconomyUsageCaptureStoreTest {
    private final String h="a".repeat(64), revision="900719925474099312345678901234567890";
    private final Binding original=new Binding("t","c","exec","run",h,"owner","agent",null);
    private final String ack="{\"acceptance\":\"DURABLE\"}";
    @Test void scopedEvidenceSurvivesRestartAndAckIsWriteOnce() throws Exception {
        var ds=new JdbcDataSource(); ds.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        String ddl=Files.readString(Path.of("src/main/resources/db/economy-usage-capture.sql"));
        // MySQL physical table options are not the subject of this H2 transaction test.
        ddl=ddl.replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin", "");
        try(var c=ds.getConnection()){for(int i=0;i<2;i++)RunScript.execute(c,new StringReader(ddl));}
        var config=new Configuration(new Environment("fixture",new JdbcTransactionFactory(),ds));config.addMapper(EconomyUsageCaptureMapper.class);
        var factory=new SqlSessionFactoryBuilder().build(config);long pk;
        try(var s=factory.openSession()){
            var m=s.getMapper(EconomyUsageCaptureMapper.class);m.ensureCapture(original);m.ensureCapture(original);
            var cap=m.lockCapture(original);pk=cap.id();assertEquals("WAITING_EVIDENCE",cap.coverageState());assertNull(cap.projectionRevision());
            var foreign=new Binding("other","c","exec","run",h,"owner","agent",null);assertNull(m.lockCapture(foreign));m.ensureCapture(foreign);assertNotEquals(pk,m.lockCapture(foreign).id());
            m.appendEvidence(new Evidence(pk,h,h,revision,"RESPONSE",h,null,"{\"reportedUsage\":null}","WAITING"));
            m.appendSubmission(new Receipt(pk,"receipt",h,h,"request",h,"0",null,null));s.commit();
        }
        try(var s=factory.openSession()){
            var m=s.getMapper(EconomyUsageCaptureMapper.class);assertEquals("PENDING",m.receipt(pk,"receipt").processingState());
            assertEquals(0,m.finishReceipt(pk,"receipt","other",ack));assertEquals(1,m.finishReceipt(pk,"receipt",h,ack));s.rollback();
        }
        try(var s=factory.openSession()){
            var m=s.getMapper(EconomyUsageCaptureMapper.class);assertNull(m.receipt(pk,"receipt").ackJson());
            assertEquals(1,m.finishReceipt(pk,"receipt",h,ack));s.commit();
            assertEquals(0,m.finishReceipt(pk,"receipt",h,"replacement"));assertEquals(ack,m.receipt(pk,"receipt").ackJson());
            assertThrows(PersistenceException.class,()->m.appendEvidence(new Evidence(pk,h,h,revision,"RESPONSE",h,null,"changed","WAITING")));s.rollback();
            assertThrows(PersistenceException.class,()->m.appendSubmission(new Receipt(pk,"new",h,"b".repeat(64),"request2",h,"1",null,null)));s.rollback();
        }
    }
}
