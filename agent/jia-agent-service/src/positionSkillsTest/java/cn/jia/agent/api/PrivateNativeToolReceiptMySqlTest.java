package cn.jia.agent.api;

import cn.jia.agent.mapper.PrivateToolCallReceiptMapper;
import cn.jia.agent.position.PositionSkillException;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Isolated MySQL, real mapper + same-transaction domain update; no access to production. */
class PrivateNativeToolReceiptMySqlTest {
    JdbcTemplate admin,jdbc; String db; TransactionTemplate tx; PrivateToolCallReceipts receipts;
    final PrivateToolCallReceiptMapper.Key key=new PrivateToolCallReceiptMapper.Key("tenant","client","owner","execution","run","call");
    final String hash="a".repeat(64);
    @BeforeEach void setup() throws Exception {
        String url=System.getenv("CYF_POSITION_TEST_JDBC");assertNotNull(url);assertTrue(url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/mysql(?:\\?.*)?"));
        String user=System.getenv("CYF_POSITION_TEST_USER"),pass=System.getenv("CYF_POSITION_TEST_PASSWORD");
        admin=new JdbcTemplate(new DriverManagerDataSource(url,user,pass));db="pwt_"+UUID.randomUUID().toString().replace("-","");admin.execute("CREATE DATABASE "+db);
        var ds=new DriverManagerDataSource(url.replace("/mysql","/"+db),user,pass);jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        String ddl=new ClassPathResource("db/agent-private-tool-call-v1.sql").getContentAsString(StandardCharsets.UTF_8);
        for(String sql:ddl.replaceAll("(?m)^--.*$","").split(";")) if(!sql.isBlank())jdbc.execute(sql);
        jdbc.execute("CREATE TABLE fixture_binding(id INT PRIMARY KEY, revision INT) ENGINE=InnoDB");jdbc.update("INSERT INTO fixture_binding VALUES(1,0)");
        var config=new Configuration();config.addMapper(PrivateToolCallReceiptMapper.class);var factory=new SqlSessionFactoryBean();factory.setConfiguration(config);factory.setDataSource(ds);
        receipts=new PrivateToolCallReceipts(new SqlSessionTemplate(factory.getObject()).getMapper(PrivateToolCallReceiptMapper.class));
    }
    @AfterEach void cleanup(){if(db!=null&&db.matches("pwt_[a-f0-9]{32}")) admin.execute("DROP DATABASE "+db);}
    Map<String,Object> write(String digest){return tx.execute(status->{
        // The production caller owns the analogous execution root lock before receipt selection.
        jdbc.queryForObject("SELECT revision FROM fixture_binding WHERE id=1 FOR UPDATE",Integer.class);
        return receipts.apply(key,"fixture_write",digest,()->{jdbc.update("UPDATE fixture_binding SET revision=revision+1 WHERE id=1");return Map.of("committed",true);});
    });}
    @Test void concurrentDuplicateReturnsSameReceiptAndDifferentInputCannotMutate() throws Exception {
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->write(hash));var b=pool.submit(()->write(hash));assertEquals(a.get(),b.get());
        }
        assertEquals(409,assertThrows(PositionSkillException.class,()->write("b".repeat(64))).status());
        assertEquals(1,jdbc.queryForObject("SELECT revision FROM fixture_binding",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM agent_private_tool_call_receipt",Integer.class));
    }
    @Test void receiptInsertFailureRollsBackDomainUpdate(){
        jdbc.execute("CREATE TRIGGER reject_fixture BEFORE INSERT ON agent_private_tool_call_receipt FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='fixture receipt failure'");
        assertThrows(RuntimeException.class,()->write(hash));assertEquals(0,jdbc.queryForObject("SELECT revision FROM fixture_binding",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM agent_private_tool_call_receipt",Integer.class));
    }
}
