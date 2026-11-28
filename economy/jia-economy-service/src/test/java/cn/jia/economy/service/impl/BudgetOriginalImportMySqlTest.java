package cn.jia.economy.service.impl;

import cn.jia.core.util.CanonicalValueEncoding;
import cn.jia.economy.config.EconomyMySqlTestFixture;
import cn.jia.economy.mapper.BudgetOriginalImportMapper;
import cn.jia.economy.pricing.GroupMeanPricing;
import cn.jia.economy.service.EconomyScope;
import cn.jia.economy.task.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
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
class BudgetOriginalImportMySqlTest {
    private final EconomyMySqlTestFixture fixture=new EconomyMySqlTestFixture();
    private final EconomyScope scope=new EconomyScope("Tenant","Client");
    private final ObjectMapper json=new ObjectMapper();
    private JdbcTemplate jdbc;private TransactionTemplate tx;private BudgetOriginalImport importer;
    @BeforeEach void start() throws Exception {
        fixture.start();var db=fixture.newDatabase("budgetoriginal");jdbc=db.jdbc();
        tx=new TransactionTemplate(new DataSourceTransactionManager(db.dataSource()));
        ddl("economy-group-budget-v1.sql",";");ddl("economy-group-budget-approval-v1.sql","$$");
        ddl("economy-v0-foundation.sql",";");ddl("economy-usage-capture.sql",";");
        ddl("economy-task-benchmark-case-v1.sql",";");ddl("economy-task-benchmark-sponsorship-v1.sql",";");
        ddl("economy-task-benchmark-closure-v1.sql",";");ddl("economy-task-benchmark-review-v1.sql",";");
        var c=new org.apache.ibatis.session.Configuration();c.setMapUnderscoreToCamelCase(true);c.addMapper(BudgetOriginalImportMapper.class);
        var factory=new SqlSessionFactoryBean();factory.setDataSource(db.dataSource());factory.setConfiguration(c);
        importer=new BudgetOriginalImport(new SqlSessionTemplate(factory.getObject()).getMapper(BudgetOriginalImportMapper.class));
    }
    @AfterEach void stop(){fixture.close();}
    private void ddl(String name,String separator){var pop=new ResourceDatabasePopulator(new ClassPathResource("db/"+name));pop.setSeparator(separator);pop.execute(jdbc.getDataSource());}
    private String sha(String s){return CanonicalValueEncoding.sha256(CanonicalValueEncoding.utf8(s));}
    private BudgetOriginalImport.Kind insert(String body){return tx.execute(s->importer.importApproved(scope,sha(body),body));}
    private List<GroupMeanPricing.ScopeSlot> slots(){return List.of(new GroupMeanPricing.ScopeSlot(0,"text.summary","1","a".repeat(64),List.of()));}
    private Map<String,Object> config(){return Map.of("strategy","native-test","resources",List.of("model"));}
    private GroupMeanPricing.ScopedPolicy policy(){
        var input=new GroupMeanPricing.ScopeInput(slots(),List.of("small"),CanonicalValueEncoding.digest(config()));
        return new GroupMeanPricing.ScopedPolicy("f1",input.specShas(),input.scopeShapeSha(),"config1",GroupMeanPricing.ScopedPolicy.definitionsSha(input.specShas()),3);
    }
    private GroupMeanPricing.ScopeGroup group(){var p=policy();return new GroupMeanPricing.ScopeGroup(p.featureVersion(),p.sha(),slots(),List.of("small"),config());}
    private ScopedBenchmarkManifest manifest(String task,String id){return new ScopedBenchmarkManifest(ScopedBenchmarkManifest.SCHEMA,
            new TaskBillingService.Task(scope,"operator",task),id,"agent","fixture-only-authority","accepted-summary",
            ScopedBenchmarkManifest.Purpose.TRAIN,new ScopedTaskQuoteSnapshot.ScopePin(1,"b".repeat(64),"c".repeat(64),"d".repeat(64)),
            policy(),slots(),List.of("small"),config(),group().key());}
    private String study(){return json.writeValueAsString(new DatabaseScopedGroupPolicies.Receipt(DatabaseScopedGroupPolicies.PURPOSE,
            scope.tenantId(),scope.clientId(),"fixture-only-approval","e".repeat(64),"f".repeat(64),policy(),policy().sha(),group().input().key(),
            group().key(),slots(),List.of("small"),config(),4,4,new BigDecimal("1"),new BigDecimal("0.10"),new BigDecimal("0.20")));}
    private String aggregate(){return json.writeValueAsString(new GroupPublicationEnrollment.Approval("GROUP_AGGREGATE","TRAIN",
            scope.tenantId(),scope.clientId(),group().key(),policy().sha(),"operator","task","case","a".repeat(64),1,
            "b".repeat(64),"c".repeat(64),"fixture-only-approval"));}
    @Test void caseImportPreservesOriginalBytesAndDoesNotAdmitOrReplaceTask(){
        String body="\n"+json.writeValueAsString(manifest("task","case"))+"\n";
        assertEquals(BudgetOriginalImport.Kind.CASE,insert(body));assertEquals(BudgetOriginalImport.Kind.CASE,insert(body));
        assertEquals(body,jdbc.queryForObject("SELECT manifest_json FROM economy_task_benchmark_case",String.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM economy_task_benchmark_case",Integer.class));
        assertThrows(RuntimeException.class,()->insert(json.writeValueAsString(manifest("task","another"))));
        assertThrows(RuntimeException.class,()->insert(json.writeValueAsString(manifest("another","case"))));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM economy_budget_group_head",Integer.class));
        jdbc.update("UPDATE economy_task_benchmark_case SET retired=TRUE");
        assertThrows(RuntimeException.class,()->insert(body));
    }
    @Test void approvedStudyAndAggregateImportDoNotSelectPublishOrResurrect(){
        String s=study(),a=aggregate();assertEquals(BudgetOriginalImport.Kind.STUDY,insert(s));
        assertEquals(BudgetOriginalImport.Kind.AGGREGATE,insert(a));insert(s);insert(a);
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM economy_budget_publish_approval",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM economy_budget_group_member",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM economy_budget_group_head",Integer.class));
        jdbc.update("UPDATE economy_budget_publish_approval SET withdrawn=TRUE WHERE approval_sha=?",sha(s));
        assertThrows(RuntimeException.class,()->insert(s));
        assertEquals(s,jdbc.queryForObject("SELECT authority_json FROM economy_budget_publish_approval WHERE approval_sha=?",String.class,sha(s)));
    }
    @Test void foreignDomainsBadDigestsUnknownFieldsAndFailingMetricsDoNotWrite(){
        var m=json.writeValueAsString(manifest("task","case"));var s=study();var a=aggregate();
        for(String body:List.of(m,s,a)){
            assertThrows(RuntimeException.class,()->tx.execute(t->importer.importApproved(new EconomyScope("Other","Client"),sha(body),body)));
            assertThrows(RuntimeException.class,()->tx.execute(t->importer.importApproved(scope,"0".repeat(64),body)));
            assertThrows(RuntimeException.class,()->insert(body.substring(0,body.length()-1)+",\"unexpected\":true}"));
            assertThrows(RuntimeException.class,()->insert(body+" {}"));
        }
        assertThrows(RuntimeException.class,()->insert(s.replace("\"estimatedCases\":4","\"estimatedCases\":3")));
        assertThrows(RuntimeException.class,()->insert(s.replace("\"absoluteErrorSum\":0.10","\"absoluteErrorSum\":0.90")));
        assertThrows(RuntimeException.class,()->insert(a.replace("\"TRAIN\"","\"HOLDOUT\"")));
        assertThrows(RuntimeException.class,()->insert("{\"purpose\":\"GROUP_AGGREGATE\",\"purpose\":\"OTHER\"}"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM economy_budget_publish_approval",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM economy_task_benchmark_case",Integer.class));
    }
    @Test void callerRollbackLeavesNoPartialOriginalAndRequiresTransaction(){
        String s=study();assertThrows(IllegalStateException.class,()->importer.importApproved(scope,sha(s),s));
        tx.executeWithoutResult(t->{importer.importApproved(scope,sha(s),s);t.setRollbackOnly();});
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM economy_budget_publish_approval",Integer.class));
    }
    @Test void concurrentImportEitherConvergesOrIsRetryableWithoutOverwriting() throws Exception {
        // InnoDB gap locking may choose a deadlock victim; only a caller retry is allowed.
        String s=study();try(var pool=Executors.newFixedThreadPool(2)){
            var gate=new CountDownLatch(1);var futures=new ArrayList<Future<Boolean>>();
            for(int i=0;i<2;i++)futures.add(pool.submit(()->{gate.await();try{insert(s);return true;}
                catch(org.springframework.dao.TransientDataAccessException retryable){return false;}}));
            gate.countDown();int successes=0;for(var f:futures)if(f.get(10,TimeUnit.SECONDS))successes++;
            assertTrue(successes>0);insert(s);
        }
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM economy_budget_publish_approval",Integer.class));
        assertEquals(s,jdbc.queryForObject("SELECT authority_json FROM economy_budget_publish_approval",String.class));
    }

    private String sponsor(String caseBody,String task){return json.writeValueAsString(new TaskBenchmarkAdmission.SponsorshipAuthority(
            "benchmark-sponsorship-v1",new TaskBillingService.Task(scope,"operator",task),"case",sha(caseBody),"agent","action",
            "e".repeat(64),"f".repeat(64),"fixture-only-authorized-spending"));}
    @Test void sponsorshipRequiresExactOriginalAndNeverActivatesOrResetsExistingFunding(){
        String m=json.writeValueAsString(manifest("task","case")),s=sponsor(m,"task");
        assertThrows(RuntimeException.class,()->insert(s));insert(m);
        assertThrows(RuntimeException.class,()->insert(sponsor(m,"other-task")));
        assertThrows(RuntimeException.class,()->insert(s.replace("\"agent\"","\"other-agent\"")));
        assertEquals(BudgetOriginalImport.Kind.SPONSORSHIP,insert(s));insert(s);
        assertEquals("APPROVED",jdbc.queryForObject("SELECT state FROM economy_task_benchmark_sponsorship",String.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM economy_task_cost_sample",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM economy_task_charge",Integer.class));
        jdbc.update("UPDATE economy_task_benchmark_sponsorship SET state='ACCEPTED'");insert(s);
        assertEquals("ACCEPTED",jdbc.queryForObject("SELECT state FROM economy_task_benchmark_sponsorship",String.class));
        assertThrows(RuntimeException.class,()->insert(s.replace("fixture-only-authorized-spending","changed-approval")));
        jdbc.update("UPDATE economy_task_benchmark_sponsorship SET withdrawn=TRUE");
        assertThrows(RuntimeException.class,()->insert(s));
    }
    @Test void importCannotRetrofitAStartedTaskOrUseADamagedCase(){
        String m=json.writeValueAsString(manifest("task","case"));insert(m);
        jdbc.update("INSERT INTO economy_usage_capture(tenant_id,client_id,execution_id,run_id,capture_id,owner_jiacn,agent_id,task_id) VALUES('Tenant','Client','execution','run',?,'operator','agent','task')","a".repeat(64));
        assertThrows(RuntimeException.class,()->insert(sponsor(m,"task")));
        // Even without usage, an original whose bytes no longer match the pin is not authority.
        jdbc.update("DELETE FROM economy_usage_capture");jdbc.update("UPDATE economy_task_benchmark_case SET manifest_json='{}'");
        assertThrows(RuntimeException.class,()->insert(sponsor(m,"task")));
        String another=json.writeValueAsString(manifest("started","new-case"));
        jdbc.update("INSERT INTO economy_usage_capture(tenant_id,client_id,execution_id,run_id,capture_id,owner_jiacn,agent_id,task_id) VALUES('Tenant','Client','execution2','run2',?,'operator','agent','started')","b".repeat(64));
        assertThrows(RuntimeException.class,()->insert(another));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM economy_task_benchmark_sponsorship",Integer.class));
    }
    private String review(String manifest){return json.writeValueAsString(new cn.jia.economy.usage.BenchmarkSampleEvidence.Review(
            "benchmark-review-v1","case",sha(manifest),"e".repeat(64),"f".repeat(64),"ACCEPTED","fixture-only-reviewer","fixture-only-deliverable"));}
    private void closedSample(String id){
        jdbc.update("INSERT INTO economy_task_cost_sample(sample_id,tenant_id,client_id,owner_jiacn,task_id,benchmark_case_id,sample_definition_version,initial_requirement_sha256,initial_input_snapshot_sha256,initial_feature_sha256,lifecycle_state,evidence_revision,sample_state) VALUES(?,'Tenant','Client','operator','task','case','TASK_LIFECYCLE_V1',?,?,?,'ACCEPTED',1,'WAITING_EVIDENCE')",id,"a".repeat(64),"b".repeat(64),"c".repeat(64));
        jdbc.update("INSERT INTO economy_task_membership_receipt VALUES('Tenant','Client','operator','task',?,'ACCEPTED',1,1,1,1,?,1,1,?,TRUE)","e".repeat(64),"f".repeat(64),"f".repeat(64));
        jdbc.update("INSERT INTO economy_task_benchmark_closure VALUES(?,?,1,1,TRUE)",id,"e".repeat(64));
    }
    @Test void reviewRequiresTheActualClosedMembershipAndDoesNotMakeUsageComplete(){
        String m=json.writeValueAsString(manifest("task","case")),r=review(m);insert(m);
        assertThrows(RuntimeException.class,()->insert(r));String sample="1".repeat(64);closedSample(sample);
        assertThrows(RuntimeException.class,()->insert(r.replace("f".repeat(64),"0".repeat(64))));
        assertThrows(RuntimeException.class,()->insert(r.replace("ACCEPTED","REJECTED")));
        assertThrows(RuntimeException.class,()->tx.execute(t->importer.importApproved(new EconomyScope("Tenant","OtherClient"),sha(r),r)));
        assertEquals(BudgetOriginalImport.Kind.REVIEW,insert(r));insert(r);
        assertEquals(r,jdbc.queryForObject("SELECT review_json FROM economy_task_benchmark_review WHERE sample_id=?",String.class,sample));
        assertEquals("WAITING_EVIDENCE",jdbc.queryForObject("SELECT sample_state FROM economy_task_cost_sample",String.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM economy_usage_capture",Integer.class));
        jdbc.update("UPDATE economy_task_benchmark_review SET withdrawn=TRUE");assertThrows(RuntimeException.class,()->insert(r));
    }
    @Test void reviewRejectsIncompleteOrChangedClosureWithoutWriting(){
        String m=json.writeValueAsString(manifest("task","case")),r=review(m);insert(m);closedSample("1".repeat(64));
        jdbc.update("UPDATE economy_task_benchmark_closure SET completed=FALSE");assertThrows(RuntimeException.class,()->insert(r));
        jdbc.update("UPDATE economy_task_benchmark_closure SET completed=TRUE,attached_count=2");assertThrows(RuntimeException.class,()->insert(r));
        jdbc.update("UPDATE economy_task_benchmark_closure SET attached_count=1");
        jdbc.update("UPDATE economy_task_membership_receipt SET membership_verified=FALSE");assertThrows(RuntimeException.class,()->insert(r));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM economy_task_benchmark_review",Integer.class));
    }
}
