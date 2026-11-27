package cn.jia.agent.service.impl;

import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.mapper.AgentTaskMetaMapper;
import cn.jia.agent.requirement.ConfirmedScope;
import cn.jia.agent.service.*;
import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Transaction/codec fixture. H2 JSON is CLOB here; native MySQL JSON/migration is a separate check. */
class ConfirmedScopeTransactionTest {
    static final AgentTaskExecutionGrantService.Scope OWNER=new AgentTaskExecutionGrantService.Scope("0","client","owner");
    static final String INPUTS="0".repeat(64);
    JdbcTemplate jdbc;
    TransactionTemplate tx;
    AgentTaskMetaDao roots;
    AgentTaskMutationTransactionImpl mutations;
    AgentTaskExecutionGrantService catalogues;
    AgentTaskRequirementSnapshotServiceImpl service;
    cn.jia.agent.skill.PublishedCapabilityQuoteSpecs specifications;

    @BeforeEach void setup() throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:scope-"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        jdbc=new JdbcTemplate(ds);
        var manager=new DataSourceTransactionManager(ds);tx=new TransactionTemplate(manager);
        for (String name:List.of("agent-task-requirement-snapshot-v1.sql","agent-task-current-requirement-v1.sql")) {
            String ddl=new ClassPathResource("db/"+name).getContentAsString(StandardCharsets.UTF_8)
                    .replaceAll("(?i) COLLATE [a-z0-9_]+", "").replaceAll("(?i) CHARACTER SET [a-z0-9_]+", "")
                    .replaceAll("(?i) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4", "")
                    .replace("scope_json JSON", "scope_json CLOB");
            jdbc.execute(ddl);
        }
        jdbc.execute("CREATE TABLE agent_task_meta(tenant_id VARCHAR(50),client_id VARCHAR(50),owner_jiacn VARCHAR(50),"
                +"task_id VARCHAR(100),task_version BIGINT,update_time BIGINT)");
        jdbc.update("INSERT INTO agent_task_meta VALUES ('0','client','owner','task',0,0)");
        jdbc.execute("CREATE TABLE agent_task_funding(tenant_id VARCHAR(50),client_id VARCHAR(50),owner_jiacn VARCHAR(50),task_id VARCHAR(100))");
        roots=mock(AgentTaskMetaDao.class);
        when(roots.findByTaskIdForUpdateInOwnerScope(anyString(),anyString(),anyString(),anyString())).thenAnswer(i->{
            var rows=jdbc.query("SELECT task_version FROM agent_task_meta WHERE tenant_id=? AND client_id=? AND owner_jiacn=? AND task_id=? FOR UPDATE",
                    (r,n)->r.getLong(1),i.getArgument(0),i.getArgument(1),i.getArgument(2),i.getArgument(3));
            if (rows.isEmpty()) return null;
            var root=new AgentTaskMetaEntity().setTaskId(i.getArgument(3)).setOwnerJiacn(i.getArgument(2))
                    .setTaskVersion(rows.getFirst()).setCurrentEventVersion(0L).setRewardStatus("open");
            root.setTenantId(i.getArgument(0));root.setClientId(i.getArgument(1));return root;
        });
        // Execute the actual mapper annotation's SQL, not an independently invented version update.
        String sql=String.join("\n",AgentTaskMetaMapper.class.getMethod("advanceRequirementVersionInOwnerScope",
                String.class,String.class,String.class,String.class,long.class,long.class).getAnnotation(Update.class).value())
                .replaceAll("#\\{([A-Za-z]+)\\}",":$1");
        when(roots.advanceRequirementVersionInOwnerScope(anyString(),anyString(),anyString(),anyString(),anyLong())).thenAnswer(i->
                new NamedParameterJdbcTemplate(jdbc).update(sql,Map.<String,Object>of("tenantId",i.getArgument(0),"clientId",i.getArgument(1),
                        "ownerJiacn",i.getArgument(2),"taskId",i.getArgument(3),"expectedVersion",i.getArgument(4),"updateTime",1L)));
        mutations=new AgentTaskMutationTransactionImpl(roots,manager);
        service=new AgentTaskRequirementSnapshotServiceImpl(jdbc,mutations);
        service.confirmationActor(new cn.jia.agent.security.AuthenticatedRequirementConfirmationActor());
        specifications=mock(cn.jia.agent.skill.PublishedCapabilityQuoteSpecs.class);service.capabilitySpecifications(specifications);
        catalogues=mock(AgentTaskExecutionGrantService.class);
        when(catalogues.snapshotTaskInputsWithinLockedTask(OWNER,"task")).thenReturn(new AgentTaskExecutionGrantService.InputCatalogue(INPUTS,
                List.of(new AgentTaskExecutionGrantService.AuthorizedInput("f-text",1,"INPUT","text/plain",1000,"a".repeat(64)))));
        @SuppressWarnings("unchecked") ObjectProvider<AgentTaskExecutionGrantService> provider=mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(catalogues);service.scopeDependencies(provider,roots);
        var context=new EsContext();context.setJiacn("owner");context.setClientId("client");EsContextHolder.setContext(context);
        var token=new JwtAuthenticationToken(Jwt.withTokenValue("fixture").header("alg","none")
                .claim("jiacn","owner").claim("client_id","client").build());token.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(token);
        tx.execute(s->service.captureOnCreate(OWNER,"task","Title","Body"));
    }
    @AfterEach void cleanup() {SecurityContextHolder.clearContext();EsContextHolder.clearContext();}

    static AgentTaskRequirementSnapshotService.ConfirmScopeCommand command(List<String> refs) {
        var item=new ConfirmedScope.WorkItem("w1","text.summary","1",Map.of(),
                Map.of("words",new ConfirmedScope.Scalar(ConfirmedScope.ValueType.INTEGER,"unit/words","500")),refs,List.of());
        return new AgentTaskRequirementSnapshotService.ConfirmScopeCommand("Title","Body",
                new ConfirmedScope.Payload(ConfirmedScope.VERSION,List.of(item),"交付一份约定长度的中文摘要"));
    }
    AgentTaskRequirementSnapshotService.Snapshot confirm() {return service.confirmScope(OWNER,"task",0,"confirm_1",command(List.of("f-text@1")));}
    long version() {return jdbc.queryForObject("SELECT task_version FROM agent_task_meta",Long.class);}
    int snapshots() {return jdbc.queryForObject("SELECT COUNT(*) FROM agent_task_requirement_snapshot",Integer.class);}
    void fund() {jdbc.update("INSERT INTO agent_task_funding VALUES ('0','client','owner','task')");}

    @Test void confirmationRequiresMatchingAuthenticatedJwtAndRequestContext() {
        var valid=SecurityContextHolder.getContext().getAuthentication();
        SecurityContextHolder.clearContext();
        assertThrows(IllegalArgumentException.class,this::confirm);
        SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("owner","unused",List.of()));
        assertThrows(IllegalArgumentException.class,this::confirm);
        SecurityContextHolder.getContext().setAuthentication(valid);
        EsContextHolder.getContext().setClientId("other-client");
        assertThrows(IllegalArgumentException.class,this::confirm);
        EsContextHolder.getContext().setClientId("client");
        EsContextHolder.getContext().setJiacn("other-owner");
        assertThrows(IllegalArgumentException.class,this::confirm);
        EsContextHolder.clearContext();
        assertThrows(IllegalArgumentException.class,this::confirm);
        assertEquals(0,version());assertEquals(1,snapshots());
        verifyNoInteractions(catalogues);
    }

    @Test void confirmationFailsClosedWithoutSecurityAdapter() {
        var unconfigured=new AgentTaskRequirementSnapshotServiceImpl(jdbc,mutations);
        assertThrows(IllegalStateException.class,()->unconfigured.confirmScope(OWNER,"task",0,"missing_adapter",
                command(List.of("f-text@1"))));
        assertEquals(0,version());assertEquals(1,snapshots());
    }

    @Test void persistsAndReadsExactScopeAndAdvancesOnlyOnce() {
        var first=confirm();assertEquals(2,first.revision());assertEquals(1,version());assertEquals(2,snapshots());
        assertEquals(first,service.read(OWNER,"task",2));
        assertEquals(first.confirmedScope(),service.readCurrent(OWNER,"task").confirmedScope());
        var read=mutations.executeWithLockedTaskRootInOwnerScope("0","client","owner","task",
                root->service.readConfirmedWithinLockedTask(OWNER,"task",root));
        assertEquals(first.confirmedScope(),read);
        assertEquals(first,confirm());assertEquals(1,version());assertEquals(2,snapshots());
        verify(catalogues,times(1)).snapshotTaskInputsWithinLockedTask(OWNER,"task");
    }
    @Test void confirmationConsumesPublishedSpecificationsButHistoricalReplayDoesNotRevalidateThem() {
        var first=confirm();
        verify(specifications).validateForConfirmation("0","client",command(List.of("f-text@1")).payload().workItems());
        doThrow(new cn.jia.agent.skill.SkillMarketplaceException(409,"CAPABILITY_QUOTE_SPEC_UNAVAILABLE"))
                .when(specifications).validateForConfirmation(anyString(),anyString(),anyList());
        assertEquals(first,confirm());
        assertThrows(cn.jia.agent.skill.SkillMarketplaceException.class,()->service.confirmScope(OWNER,"task",1,"new",command(List.of("f-text@1"))));
        assertEquals(1,version());assertEquals(2,snapshots());
    }
    @Test void fundedReplayIsReadOnlyButNewConfirmationAndOverwriteAreRejected() {
        var first=confirm();fund();assertEquals(first,confirm());
        assertThrows(IllegalStateException.class,()->service.confirmScope(OWNER,"task",1,"new",command(List.of("f-text@1"))));
        assertThrows(IllegalStateException.class,()->tx.execute(s->service.updateCurrent(OWNER,"task",2,"Changed","Body")));
        assertEquals(first,service.read(OWNER,"task",2));assertEquals(1,version());
    }
    @Test void originalVersionAndPayloadMustMatchOnReplay() {
        confirm();
        assertThrows(IllegalStateException.class,()->service.confirmScope(OWNER,"task",1,"confirm_1",command(List.of("f-text@1"))));
        assertThrows(IllegalStateException.class,()->service.confirmScope(OWNER,"task",0,"confirm_1",command(List.of())));
        assertEquals(2,snapshots());assertEquals(1,version());
    }
    @Test void staleVersionAndUnboundInputCannotWrite() {
        assertThrows(IllegalStateException.class,()->service.confirmScope(OWNER,"task",1,"new",command(List.of("f-text@1"))));
        assertThrows(IllegalArgumentException.class,()->service.confirmScope(OWNER,"task",0,"new",command(List.of("f-foreign@1"))));
        assertEquals(0,version());assertEquals(1,snapshots());
        assertNull(service.readCurrent(OWNER,"task").confirmedScope());
    }
    @Test void failedVersionCasRollsBackCurrentAndImmutableSnapshot() {
        when(roots.advanceRequirementVersionInOwnerScope(anyString(),anyString(),anyString(),anyString(),anyLong())).thenReturn(0);
        assertThrows(IllegalStateException.class,this::confirm);
        assertEquals(0,version());assertEquals(1,snapshots());
        assertEquals(1,service.readCurrent(OWNER,"task").requirementRevision());
    }
    @Test void legacyReconfirmationWithdrawsScopeWithoutRewritingHistory() {
        var first=confirm();
        service.reconfirm(OWNER,"task",1,"edit_1","Changed","Body");
        assertNull(service.readCurrent(OWNER,"task").confirmedScope());assertEquals(2,version());
        assertEquals(first,service.read(OWNER,"task",2));
        assertThrows(IllegalStateException.class,this::confirm);
    }
    @Test void partialOrAlteredPersistedScopeIsNotReturned() {
        confirm();
        jdbc.update("UPDATE agent_task_current_requirement SET scope_input_sha256=?","1".repeat(64));
        assertThrows(AgentTaskRequirementReadException.class,()->service.readCurrent(OWNER,"task"));
        jdbc.update("UPDATE agent_task_current_requirement SET scope_input_sha256=NULL");
        assertThrows(AgentTaskRequirementReadException.class,()->service.readCurrent(OWNER,"task"));
    }
    @Test void wrongIdentityAndUnconfirmedCurrentAreNotNewQuoteInputs() {
        assertThrows(IllegalStateException.class,()->mutations.executeWithLockedTaskRootInOwnerScope("0","client","owner","task",
                root->service.readConfirmedWithinLockedTask(OWNER,"task",root)));
        SecurityContextHolder.clearContext();
        assertThrows(IllegalArgumentException.class,this::confirm);assertEquals(1,snapshots());
    }
}
