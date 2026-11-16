package cn.jia.agent.service.impl;

import cn.jia.agent.entity.PersonalWorkspaceExecutionEntity;
import cn.jia.agent.mapper.PersonalWorkspaceExecutionMapper;
import cn.jia.agent.skill.SkillManagementMySqlFixture;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Owned MySQL fixture for the current v6 conversation queue mapper. No production data or new DDL. */
class NativeConversationQueueMySqlTest extends SkillManagementMySqlFixture {
    private String database;
    private JdbcTemplate jdbc;
    private PersonalWorkspaceExecutionMapper mapper;
    private org.apache.ibatis.session.SqlSessionFactory sqlFactory;

    @BeforeEach void setup() throws Exception {
        database="cyf_native_queue_"+UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE DATABASE `"+database+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
        var source=source(database);jdbc=new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE agent_personal_workspace_execution (execution_id VARCHAR(100), tenant_id VARCHAR(50), client_id VARCHAR(50), owner_jiacn VARCHAR(50), target_agent_id VARCHAR(100), execution_mode VARCHAR(24), execution_state VARCHAR(24), created_at BIGINT, execution_protocol_version INT, conversation_provider_started_at BIGINT, task_id VARCHAR(100), task_grant_id VARCHAR(100), task_grant_version BIGINT, business_scope VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin, assignment_revision BIGINT)");
        jdbc.execute("CREATE TABLE agent_task_execution_grant (tenant_id VARCHAR(50),client_id VARCHAR(50),owner_jiacn VARCHAR(50),task_id VARCHAR(100),grant_id VARCHAR(100),grant_version BIGINT,target_agent_id VARCHAR(100),executor_owner_jiacn VARCHAR(50))");
        jdbc.update("INSERT INTO agent_task_execution_grant VALUES ('0','client','owner','task','grant',1,'agent','executor')");
        var config=new MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);
        config.addMapper(PersonalWorkspaceExecutionMapper.class);
        var factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(source);factory.setConfiguration(config);
        sqlFactory=Objects.requireNonNull(factory.getObject());
        mapper=new SqlSessionTemplate(sqlFactory).getMapper(PersonalWorkspaceExecutionMapper.class);
    }
    @AfterEach void cleanup() {
        if(database!=null && database.matches("cyf_native_queue_[0-9a-f]{32}")) admin.execute("DROP DATABASE `"+database+"`");
    }
    private void row(String id,String mode,int protocol,String state,long created) {
        jdbc.update("INSERT INTO agent_personal_workspace_execution VALUES (?,'0','client','owner','agent',?,?,?, ?,NULL,'task','grant',1,'TASK',7)",id,mode,state,created,protocol);
    }
    private List<String> page(Long after,String id,int limit) {
        return mapper.listQueuedConversationsByTarget("0","client","executor","agent",after,id,limit).stream()
                .map(PersonalWorkspaceExecutionEntity::getExecutionId).toList();
    }
    @Test void retiredProtocolsModesAndStartedRowsCannotStarveCurrentNativeQueue() {
        for(int i=0;i<32;i++) {
            row("old-"+i,"CONVERSATION",1+i%5,"QUEUED",i);
            row("private-"+i,"PRIVATE",6,"QUEUED",i);
            row("task-"+i,"TASK",6,"QUEUED",i);
            row("historical-"+i,null,1,"QUEUED",i);
            row("started-"+i,"CONVERSATION",6,"QUEUED",i);
            jdbc.update("UPDATE agent_personal_workspace_execution SET conversation_provider_started_at=1 WHERE execution_id=?","started-"+i);
        }
        row("current","CONVERSATION",6,"QUEUED",100);
        assertEquals(List.of("current"),page(null,null,16));
        assertEquals(List.of("current"),page(null,null,16));
        assertEquals(161,jdbc.queryForObject("SELECT COUNT(*) FROM agent_personal_workspace_execution",Integer.class));
    }
    @Test void currentQueueKeepsExactPartitionExecutorGrantAndOrderingBeforeLimit() {
        row("wanted","CONVERSATION",6,"QUEUED",100);
        var variants=Map.of("tenant_id",List.of("1","0 "),"client_id",List.of("Client","client "),
                "target_agent_id",List.of("Agent","agent "),"execution_mode",List.of("conversation","CONVERSATION "));
        int n=0;
        for(var entry:variants.entrySet())for(String value:entry.getValue())for(int i=0;i<16;i++) {
            String id="other-"+(n++);row(id,"CONVERSATION",6,"QUEUED",0);
            jdbc.update("UPDATE agent_personal_workspace_execution SET "+entry.getKey()+"=? WHERE execution_id=?",value,id);
        }
        assertEquals(List.of("wanted"),page(null,null,16));
        assertEquals(List.of(),mapper.listQueuedConversationsByTarget("0","client","Executor","agent",null,null,16));
        jdbc.update("DELETE FROM agent_task_execution_grant");
        assertEquals(List.of(),page(null,null,16));
    }
    @Test void privateAndTaskUseOneQueueWithoutPrivateGrantOrCrossOwnerLeak() {
        row("task","CONVERSATION",6,"QUEUED",1);
        row("private","CONVERSATION",6,"QUEUED",2);
        jdbc.update("UPDATE agent_personal_workspace_execution SET business_scope='PRIVATE',owner_jiacn='executor',task_id=NULL,task_grant_id=NULL,task_grant_version=NULL,assignment_revision=NULL WHERE execution_id='private'");
        assertEquals(List.of("task","private"),page(null,null,16));
        jdbc.update("DELETE FROM agent_task_execution_grant");
        assertEquals(List.of("private"),page(null,null,16));
        assertEquals(List.of(),mapper.listQueuedConversationsByTarget("0","client","owner","agent",null,null,16));
        jdbc.update("UPDATE agent_personal_workspace_execution SET task_id='invented' WHERE execution_id='private'");
        assertEquals(List.of(),page(null,null,16));
    }
    @Test void unknownScopeAndAnotherOwnersPrivateRowsCannotStarveAuthorizedPage() {
        row("wanted","CONVERSATION",6,"QUEUED",100);
        for(int i=0;i<32;i++) {
            row("unknown-"+i,"CONVERSATION",6,"QUEUED",i);
            jdbc.update("UPDATE agent_personal_workspace_execution SET business_scope='UNKNOWN' WHERE execution_id=?","unknown-"+i);
            row("foreign-"+i,"CONVERSATION",6,"QUEUED",i);
            jdbc.update("UPDATE agent_personal_workspace_execution SET business_scope='PRIVATE',task_id=NULL,task_grant_id=NULL,task_grant_version=NULL,assignment_revision=NULL WHERE execution_id=?","foreign-"+i);
        }
        assertEquals(List.of("wanted"),page(null,null,1));
    }

    @Test void keysetPreservesBinaryTieBreakWithoutMutatingExecutionFacts() {
        for(String id:List.of("A","a","a ","b"))row(id,"CONVERSATION",6,"QUEUED",100);
        row("next","CONVERSATION",6,"QUEUED",101);
        assertEquals(List.of("A","a","a ","b","next"),page(null,null,16));
        assertEquals(List.of("a ","b"),page(100L,"a",2));
        assertEquals(List.of("next"),page(100L,"b",16));
        assertEquals(List.of(),page(101L,"next",16));
        assertEquals(5,page(null,null,16).size());
    }
}
