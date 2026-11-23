package cn.jia.agent.service.impl;

import cn.jia.agent.api.NativeExecutionUsageCostController;
import cn.jia.agent.dao.impl.*;
import cn.jia.agent.mapper.*;
import cn.jia.agent.security.*;
import cn.jia.agent.service.usage.*;
import cn.jia.core.config.ApiEnvelopeAdvice;
import cn.jia.core.config.ApiRequestEnvelopeAdvice;
import cn.jia.core.filter.RequestIdFilter;
import cn.jia.core.security.FixedTenantAdmissionPolicy;
import cn.jia.economy.mapper.*;
import cn.jia.economy.service.impl.*;
import cn.jia.economy.usage.*;
import cn.jia.task.mapper.ScheduledWorkMapper;
import cn.jia.task.scheduling.*;
import cn.jia.user.security.*;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.web.servletapi.SecurityContextHolderAwareRequestFilter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import tools.jackson.databind.ObjectMapper;
import java.net.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.mockito.Mockito.*;

/** Explicit cross-component selector only. The socket harness cuts a reply AFTER MVC completed.
 * MySQL identity, run authority, fence, evidence, scheduler and independent ledger use production adapters.
 * Only the separate user-account directory is substituted with this fixture's active account. */
class MeteredPeerMySqlTest extends TaskExecutionCancellationMySqlTest {
    static final String INSTALL="rti_"+"1".repeat(32), AGENT="agt_"+"a".repeat(32);
    final ObjectMapper json=new ObjectMapper();
    <T>T wire(T dao,Object mapper){ReflectionTestUtils.setField(dao,"baseMapper",mapper);return dao;}
    <T>T transactional(Object object,Class<T> type,DataSourceTransactionManager manager){
        var proxy=new org.springframework.aop.framework.ProxyFactory(object);proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));return type.cast(proxy.getProxy());
    }
    @Test void lostResponseAfterCommitReturnsOriginalMySqlRevision(@TempDir Path temp) throws Exception {
        var ds=Objects.requireNonNull(jdbc.getDataSource());var manager=new DataSourceTransactionManager(ds);
        String schema=new ClassPathResource("db/schema.sql").getContentAsString(StandardCharsets.UTF_8);
        for(String table:List.of("agent_runtime","agent_persona_binding","agent_identity_registry","agent_identity_alias")){
            int i=schema.indexOf("CREATE TABLE IF NOT EXISTS "+table+" (");assertTrue(i>=0,table);jdbc.execute(schema.substring(i,schema.indexOf(";\n",i)));
        }
        for(String resource:List.of("agent-runtime-v1-schema","agent-runtime-session-fence-v1","agent-task-execution-grant-v1","task-scheduled-work-v1"))
            new ResourceDatabasePopulator(new ClassPathResource("db/"+resource+".sql")).execute(ds);
        new cn.jia.economy.config.EconomyUsageCostSchemaInitializer(ds).afterPropertiesSet();
        jdbc.update("INSERT INTO agent_persona_binding(id,jiacn,persona_code,agent_id,bound_at,status,tenant_id,client_id) VALUES(77,'executor','fixture',?,1,1,'0','client')",AGENT);
        jdbc.update("INSERT INTO agent_identity_registry(canonical_agent_id,canonical_type,lifecycle_status,client_id,owner_jiacn,tenant_id,binding_id,provisioned_at,activated_at,audit_reason,create_time,update_time) VALUES(?,'OPAQUE','ACTIVE','client','executor','0',77,1,1,'fixture',1,1)",AGENT);
        jdbc.update("INSERT INTO agent_runtime_v1_installation(installation_id,canonical_agent_id,manifest_version,manifest_sha256,enrollment_secret_hash,enrollment_expires_at,status,tenant_id,client_id) VALUES(?,?,'1',REPEAT('a',64),UNHEX(REPEAT('01',32)),1,'ACTIVE','0','client')",INSTALL,AGENT);
        jdbc.update("INSERT INTO agent_task_execution_grant(grant_id,tenant_id,client_id,owner_jiacn,task_id,requirement_revision,assignment_revision,target_agent_id,executor_owner_jiacn,executor_binding_id,permitted_operations_json,permitted_tool_policy_ref,input_scope_json,source_business_action_id,idempotency_key,request_hash,policy_revision,grant_version,state,issued_by,created_at) VALUES('g','0','client','owner','task',1,1,?,'executor',77,'[]','policy','{}','action','key',REPEAT('a',64),'v',1,'REVOKED','owner',1)",AGENT);
        row("exec1","owner","task","CONVERSATION","RESULT_COMMITTED",6);
        jdbc.update("UPDATE agent_personal_workspace_execution SET target_agent_id=?,task_grant_id='g',task_grant_version=1,usage_mode='RESPONSE_V2',capture_protocol='METERED_CAPTURE_V2' WHERE execution_id='exec1'",AGENT);
        var config=new MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);
        for(var cl:List.of(AgentRuntimeMapper.class,AgentRuntimeV1InstallationMapper.class,AgentIdentityRegistryMapper.class,AgentIdentityAliasMapper.class,AgentPersonaBindingMapper.class,EconomyUsageCostMapper.class,EconomyUsageCaptureMapper.class,ScheduledWorkMapper.class)) config.addMapper(cl);
        var factory=new MybatisSqlSessionFactoryBean();factory.setConfiguration(config);factory.setDataSource(ds);
        var sql=new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
        var installations=wire(new AgentRuntimeV1InstallationDaoImpl(),sql.getMapper(AgentRuntimeV1InstallationMapper.class));
        var registry=wire(new AgentIdentityRegistryDaoImpl(),sql.getMapper(AgentIdentityRegistryMapper.class));
        var identity=new AgentIdentityServiceImpl(registry,wire(new AgentIdentityAliasDaoImpl(),sql.getMapper(AgentIdentityAliasMapper.class)),wire(new AgentPersonaBindingDaoImpl(),sql.getMapper(AgentPersonaBindingMapper.class)));
        var scope=new FixedTenantAdmissionPolicy("0","client");var accounts=mock(AccountSecurityService.class);
        when(accounts.findUniqueByExactJiacn("executor")).thenReturn(Optional.of(new AccountSecuritySnapshot(1,"executor",AccountState.ACTIVE,1)));
        var auth=transactional(new AgentRuntimeAuthenticationService(wire(new AgentRuntimeDaoImpl(),sql.getMapper(AgentRuntimeMapper.class)),installations,registry,identity,accounts,null,scope),AgentRuntimeAuthenticationService.class,manager);
        var session=tx.execute(st->auth.issue(installations.lock(INSTALL),new cn.jia.agent.entity.AgentRuntimeV1SessionRequest(INSTALL,"0","client",AGENT,"1","a".repeat(64),"host","rt"),1));assertNotNull(session);
        var beans=new DefaultListableBeanFactory();beans.registerSingleton("s",new ScheduledWorkStore(sql.getMapper(ScheduledWorkMapper.class),scope,manager));
        var schedules=beans.getBeanProvider(ScheduleService.class);
        var store=transactional(new UsageCaptureProjectionStore(sql.getMapper(EconomyUsageCaptureMapper.class)),UsageCaptureProjection.class,manager);
        var projection=new NativeCaptureProjection(store,schedules,scope);
        var journal=transactional(new UsageCaptureJournalImpl(sql.getMapper(EconomyUsageCaptureMapper.class),event->projection.changed((UsageCaptureProjection.Key)event)),UsageCaptureJournal.class,manager);
        var cost=transactional(new UsageCostServiceImpl(sql.getMapper(EconomyUsageCostMapper.class),json),UsageCostService.class,manager);
        var settle=transactional(new UsageCaptureSettlement(cost,journal),UsageCaptureJournal.Settlement.class,manager);
        var recovery=new NativeCaptureRecovery(journal,settle,scope,schedules,manager);
        var ctrl=new NativeExecutionUsageCostController(null,auth,new NativeCaptureAuthority(executions),journal,settle,recovery);
        var principal=new SecurityContextHolderAwareRequestFilter();principal.afterPropertiesSet();
        var mvc=MockMvcBuilders.standaloneSetup(ctrl).setControllerAdvice(new ApiEnvelopeAdvice(),new ApiRequestEnvelopeAdvice()).addFilters(new RequestIdFilter(),new AgentRuntimeAuthenticationFilter(auth),principal).build();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var cuts=new AtomicReference<String>();var error=new AtomicReference<Throwable>();var trace=new java.util.concurrent.CopyOnWriteArrayList<String>();
        server.createContext("/",exchange->{try(exchange){var req=request(HttpMethod.valueOf(exchange.getRequestMethod()),exchange.getRequestURI());exchange.getRequestHeaders().forEach((k,v)->req.header(k,v.toArray()));
            byte[] input=exchange.getRequestBody().readAllBytes();if(input.length>0)req.content(input);
            var response=mvc.perform(req).andReturn().getResponse();String kind=input.length==0?"GET":json.readTree(input).path("data").path("kind").asText();trace.add(kind);
            assertEquals(200,response.getStatus(),kind+":"+response.getContentAsString());
            if("RESPONSE".equals(kind)&&cuts.compareAndSet(null,exchange.getRequestHeaders().getFirst("X-Request-Id"))){assertEquals(1,count("economy_usage_revision"));return;}
            if("GET".equals(kind))assertTrue(exchange.getRequestURI().getPath().endsWith("/receipts/"+cuts.get()));
            for(var h:response.getHeaderNames())exchange.getResponseHeaders().put(h,response.getHeaders(h));byte[] bytes=response.getContentAsByteArray();exchange.sendResponseHeaders(response.getStatus(),bytes.length);exchange.getResponseBody().write(bytes);
        }catch(Throwable t){error.compareAndSet(null,t);}});server.start();
        try {
            Path peer=Path.of(System.getProperty("ownedMeteredRuntime")).resolve("conf/codex-bridge/test/fixtures/metered-java-peer.mjs");assertTrue(Files.isRegularFile(peer));
            var pb=new ProcessBuilder("node",peer.toString());pb.environment().put("TMPDIR",temp.toString());var child=pb.start();
            try{try(var in=child.getOutputStream()){in.write(json.writeValueAsBytes(Map.of("port",server.getAddress().getPort(),"executionId","exec1","runId","run-exec1","session",Map.of("sessionToken",session.sessionToken(),"canonicalAgentId",AGENT,"installationId",INSTALL,"hostId","host","runtimeInstanceId","rt","sessionGeneration","1"))));}
                assertTrue(child.waitFor(50,TimeUnit.SECONDS));if(error.get()!=null)throw new AssertionError("receiver",error.get());assertEquals(0,child.exitValue(),"Runtime peer failed: "+trace);
                var report=json.readTree(child.getInputStream().readAllBytes());assertEquals(7,report.path("ackCount").asInt());assertEquals(cuts.get(),report.path("receiptId").asText());assertEquals("1",report.path("revision").asText());
            }finally{if(child.isAlive()){child.destroyForcibly();child.waitFor();}}
        }finally{server.stop(0);}
        assertEquals(List.of("PLAN","SOURCE","REQUEST","REQUEST","RESPONSE","GET","SOURCE_FINAL","CAPTURE_FINAL","RESPONSE"),trace);
        // Consume the real transactionally registered work keys, deliberately run coverage BEFORE association.
        String pendingSql="SELECT business_key FROM task_scheduled_work WHERE tenant_id='0' AND client_id='client' AND handler=?";
        var projectionKey=jdbc.queryForObject(pendingSql,String.class,projection.getName());
        var work=new ScheduledInvocation("0",new ScheduleKey("client",projection.getName(),projectionKey,""),1);
        projection.executeScheduled(work);
        assertEquals("WAITING_EVIDENCE",jdbc.queryForObject("SELECT coverage_state FROM economy_usage_capture",String.class));
        var keys=jdbc.queryForList(pendingSql,String.class,recovery.getName());assertFalse(keys.isEmpty());
        for(var key:keys) recovery.executeScheduled(new ScheduledInvocation("0",new ScheduleKey("client",recovery.getName(),key,""),1));
        projection.executeScheduled(work);projection.executeScheduled(work);
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM economy_usage_capture WHERE coverage_state='INCOMPLETE' AND projection_revision=input_revision",Integer.class));
        String path="/internal/agent/executions/exec1/runs/run-exec1/usage/receipts/"+cuts.get();
        var read=get(path).header("Authorization","AgentRuntime "+session.sessionToken()).header("X-Agent-Id",AGENT).header("X-Agent-Installation-Id",INSTALL).header("X-Agent-Host-Id","host").header("X-Agent-Runtime-Id","rt").header("X-Agent-Session-Generation","1");
        jdbc.update("UPDATE agent_task_execution_grant SET executor_owner_jiacn='foreign' WHERE grant_id='g'");assertEquals(404,mvc.perform(read).andReturn().getResponse().getStatus());
        jdbc.update("UPDATE agent_runtime_v1_installation SET status='REVOKED' WHERE installation_id=?",INSTALL);assertEquals(401,mvc.perform(read).andReturn().getResponse().getStatus());
        assertEquals(7,count("economy_usage_capture_receipt"));assertEquals(1,count("economy_usage_attempt"));assertEquals(1,count("economy_usage_revision"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM economy_usage_revision WHERE status='PENDING_USAGE' AND cost_yuan IS NULL AND revision=1",Integer.class));
    }
    int count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}
}
