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
import cn.jia.economy.pricing.*;
import cn.jia.economy.config.*;
import cn.jia.economy.task.TaskBillingService;
import cn.jia.economy.service.EconomyScope;
import java.math.BigDecimal;
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
        verifyPeer(temp,null,false);
    }
    @Test void realArtifactTurnProjectsCompleteWithoutInventingInvoice(@TempDir Path temp) throws Exception {
        var fixture=json.readTree(new ClassPathResource("metered-local-artifact-fixture.json").getContentAsString(StandardCharsets.UTF_8));
        verifyPeer(temp,fixture,false);
    }
    @Test void missingRetryPreventsWholeTaskSampleEvenWithCompleteFirstRun(@TempDir Path temp) throws Exception {
        verifyPeer(temp,json.readTree(new ClassPathResource("metered-local-artifact-fixture.json").getContentAsString(StandardCharsets.UTF_8)),true);
    }
    // Upstream Chat admission is verified in the signed quote/dispatch regression. This
    // fixture starts at its Agent port, with no synthetic Chat or native execution row.
    @Test void missingDiscussionUsagePreventsWholeTaskSampleDespiteCompleteNativeRun(@TempDir Path temp) throws Exception {
        verifyPeer(temp,json.readTree(new ClassPathResource("metered-local-artifact-fixture.json").getContentAsString(StandardCharsets.UTF_8)),false,false,true);
    }
    @Test void successorEpochLostResponseStillHasOneOriginalMySqlLedgerRevision(@TempDir Path temp) throws Exception {
        verifyPeer(temp,null,false,true);
    }
    private void verifyPeer(Path temp,tools.jackson.databind.JsonNode measured,boolean retry) throws Exception {
        verifyPeer(temp,measured,retry,false);
    }
    private void verifyPeer(Path temp,tools.jackson.databind.JsonNode measured,boolean retry,boolean successor) throws Exception {
        verifyPeer(temp,measured,retry,successor,false);
    }
    private void verifyPeer(Path temp,tools.jackson.databind.JsonNode measured,boolean retry,boolean successor,boolean discussion) throws Exception {
        verifyPeer(temp,measured,retry,successor,discussion,DiscussionPeer.NONE);
    }
    @Test void originalDiscussionLostResponseAfterCommitReadsSameRevision(@TempDir Path temp) throws Exception {
        verifyPeer(temp,null,false,false,false,DiscussionPeer.ORIGINAL);
    }
    enum DiscussionPeer { NONE, ORIGINAL, ROTATE_AFTER_COMMIT }
    @Test void rotationBetweenCommitAndReadbackCannotInheritDiscussion(@TempDir Path temp) throws Exception {
        verifyPeer(temp,null,false,false,false,DiscussionPeer.ROTATE_AFTER_COMMIT);
    }
    private void verifyPeer(Path temp,tools.jackson.databind.JsonNode measured,boolean retry,boolean successor,boolean discussion,DiscussionPeer peerMode) throws Exception {
        boolean chat=peerMode!=DiscussionPeer.NONE,rotate=peerMode==DiscussionPeer.ROTATE_AFTER_COMMIT;
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
        if(!chat){
        row("exec1","owner","task","CONVERSATION","RESULT_COMMITTED",6);
        jdbc.update("UPDATE agent_personal_workspace_execution SET target_agent_id=?,task_grant_id='g',task_grant_version=1,usage_mode='RESPONSE_V2',capture_protocol='METERED_CAPTURE_V2' WHERE execution_id='exec1'",AGENT);
        skill("exec1","COMPLETE");
        String admitted=measured==null?"e".repeat(64):measured.get("admission").asText();
        jdbc.update("UPDATE agent_personal_workspace_execution SET capture_profile_sha256=? WHERE execution_id='exec1'",admitted);
        assertEquals(admitted,executions.admittedCaptureProfile("0","client","owner","task",AGENT,"exec1","run-exec1"));
        assertNull(executions.admittedCaptureProfile("0","client","executor","task",AGENT,"exec1","run-exec1"));
        assertNull(executions.admittedCaptureProfile("0","client","owner","task",AGENT,"exec1","foreign"));
        }
        var config=new MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);
        for(var cl:List.of(AgentRuntimeMapper.class,AgentRuntimeV1InstallationMapper.class,AgentIdentityRegistryMapper.class,AgentIdentityAliasMapper.class,AgentPersonaBindingMapper.class,EconomyUsageCostMapper.class,EconomyUsageCaptureMapper.class,ConfirmedSampleMapper.class,EconomyLedgerMapper.class,EconomyTaskBillingMapper.class,TaskMembershipReceiptMapper.class,ScheduledWorkMapper.class)) config.addMapper(cl);
        if(chat){config.addMapper(cn.jia.chat.deliberation.ChatDispatchAuthorityMapper.class);config.addMapper(cn.jia.chat.deliberation.ChatDeliberationMapper.class);}
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
        var projection=new NativeCaptureProjection(store,schedules,scope,executions);
        var journal=transactional(new UsageCaptureJournalImpl(sql.getMapper(EconomyUsageCaptureMapper.class),event->projection.changed((UsageCaptureProjection.Key)event)),UsageCaptureJournal.class,manager);
        var cost=transactional(new UsageCostServiceImpl(sql.getMapper(EconomyUsageCostMapper.class),json),UsageCostService.class,manager);
        var settle=transactional(new UsageCaptureSettlement(cost,journal),UsageCaptureJournal.Settlement.class,manager);
        var recovery=new NativeCaptureRecovery(journal,settle,scope,schedules,manager);
        var ctrl=new NativeExecutionUsageCostController(null,auth,new NativeCaptureAuthority(executions,skills),new cn.jia.agent.service.usage.RuntimeCaptureIntakeService(journal,settle,recovery));
        Map<String,Object> payload=chat?discussionPayload():Map.of();Object receiver=ctrl;
        if(chat){
            var member=transactional(new TaskDiscussionMembershipService(executions),TaskDiscussionMembershipService.class,manager);
            var origins=new cn.jia.chat.deliberation.ChatTaskDispatchOriginStore(jdbc);
            seedSentDiscussion(payload,member,origins);
            var dispatches=new cn.jia.chat.service.usage.ChatUsageDispatchAuthority(sql.getMapper(cn.jia.chat.deliberation.ChatDispatchAuthorityMapper.class),
                    new cn.jia.chat.deliberation.ChatDeliberationDaoImpl(sql.getMapper(cn.jia.chat.deliberation.ChatDeliberationMapper.class)),origins);
            receiver=new cn.jia.chat.api.ChatDiscussionCaptureController(new cn.jia.chat.service.usage.ChatDiscussionCaptureAccess(auth,dispatches,member),
                    new RuntimeCaptureIntakeService(journal,settle,recovery));
        }
        var principal=new SecurityContextHolderAwareRequestFilter();principal.afterPropertiesSet();
        var mvc=MockMvcBuilders.standaloneSetup(receiver).setControllerAdvice(new ApiEnvelopeAdvice(),new ApiRequestEnvelopeAdvice()).addFilters(new RequestIdFilter(),new AgentRuntimeAuthenticationFilter(auth),principal).build();
        if(measured!=null){
            // Original, unchanged wire records from an isolated CLI + loopback TLS run. Independent pin predated PLAN.
            var scenario=confirmTestTask(measured,sql,manager,schema,retry,discussion);
            var records=measured.get("records");assertEquals(10,records.size());var keys=new ArrayList<>(List.of("plan","source-0","request-1-1","request-1-2","request-2-1","request-2-2"));
            for(int i=1;i<=2;i++)keys.add("response-"+records.get("request-"+i+"-2").get("observation").get("sourceAttemptId").asText()+"-1");
            keys.addAll(List.of("source-final-1","capture-final-1"));
            for(String name:keys){var record=records.get(name);String id=record.get("receiptId").asText();var request=post("/internal/agent/executions/exec1/runs/run-exec1/usage").header("Authorization","AgentRuntime "+session.sessionToken()).header("X-Agent-Id",AGENT).header("X-Agent-Installation-Id",INSTALL).header("X-Agent-Host-Id","host").header("X-Agent-Runtime-Id","rt").header("X-Agent-Session-Generation","1").header("X-Request-Id",id).header("Idempotency-Key",id).contentType("application/json").content(json.writeValueAsBytes(Map.of("protocolVersion",1,"requestId",id,"data",record)));
                var reply=mvc.perform(request).andReturn().getResponse();assertEquals(200,reply.getStatus(),name+":"+reply.getContentAsString());}
            String jobs="SELECT business_key FROM task_scheduled_work WHERE tenant_id='0' AND client_id='client' AND handler=?";
            for(var key:jdbc.queryForList(jobs,String.class,recovery.getName()))recovery.executeScheduled(new ScheduledInvocation("0",new ScheduleKey("client",recovery.getName(),key,""),1));
            String key=jdbc.queryForObject(jobs,String.class,projection.getName());projection.executeScheduled(new ScheduledInvocation("0",new ScheduleKey("client",projection.getName(),key,""),1));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM economy_usage_capture WHERE coverage_state='COMPLETE' AND projection_revision=input_revision",Integer.class));
            assertEquals(10,count("economy_usage_capture_receipt"));assertEquals(2,count("economy_usage_attempt"));assertEquals(2,count("economy_usage_revision"));
            assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM economy_usage_revision WHERE cost_yuan IS NULL AND revision=1",Integer.class));
            verifyAcceptedSample(scenario,sql,manager,retry||discussion);return;
        }
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var cuts=new AtomicReference<String>();var error=new AtomicReference<Throwable>();var trace=new java.util.concurrent.CopyOnWriteArrayList<String>();
        var rotated=new AtomicReference<cn.jia.agent.entity.AgentRuntimeV1SessionResponse>();var originalBody=new AtomicReference<byte[]>();
        server.createContext("/",exchange->{try(exchange){var req=request(HttpMethod.valueOf(exchange.getRequestMethod()),exchange.getRequestURI());exchange.getRequestHeaders().forEach((k,v)->req.header(k,v.toArray()));
            byte[] input=exchange.getRequestBody().readAllBytes();if(input.length>0)req.content(input);
            var response=mvc.perform(req).andReturn().getResponse();String kind=input.length==0?"GET":json.readTree(input).path("data").path("kind").asText();trace.add(kind);
            assertEquals(rotated.get()==null?200:401,response.getStatus(),kind+":"+response.getContentAsString());
            if("RESPONSE".equals(kind)&&cuts.compareAndSet(null,exchange.getRequestHeaders().getFirst("X-Request-Id"))){
                assertEquals(1,count("economy_usage_revision"));
                if(rotate){originalBody.set(input);rotated.set(tx.execute(st->auth.issue(installations.lock(INSTALL),new cn.jia.agent.entity.AgentRuntimeV1SessionRequest(INSTALL,"0","client",AGENT,"1","a".repeat(64),"host","rt"),2)));assertEquals(2,rotated.get().sessionGeneration());}
                if(!rotate)return; // Other lost-response cases drop the actual socket.
                exchange.getResponseHeaders().set("X-Fixture-Drop-Committed","yes");
            }
            if("GET".equals(kind))assertTrue(exchange.getRequestURI().getPath().endsWith("/receipts/"+cuts.get()));
            for(var h:response.getHeaderNames())exchange.getResponseHeaders().put(h,response.getHeaders(h));byte[] bytes=response.getContentAsByteArray();exchange.sendResponseHeaders(response.getStatus(),bytes.length);exchange.getResponseBody().write(bytes);
        }catch(Throwable t){error.compareAndSet(null,t);}});server.start();
        try {
            Path peer=Path.of(System.getProperty("ownedMeteredRuntime")).resolve("conf/codex-bridge/test/fixtures/metered-java-peer.mjs");assertTrue(Files.isRegularFile(peer));
            var pb=new ProcessBuilder("node",peer.toString());pb.environment().put("TMPDIR",temp.toString());var child=pb.start();
            try{try(var in=child.getOutputStream()){in.write(json.writeValueAsBytes(Map.of("rotate",rotate,"discussion",chat?(Object)payload:false,"successor",successor,"snapshotDigest",NativeCancellationFixture.snapshotDigest("task"),"port",server.getAddress().getPort(),"executionId","exec1","runId","run-exec1","session",Map.of("sessionToken",session.sessionToken(),"canonicalAgentId",AGENT,"installationId",INSTALL,"hostId","host","runtimeInstanceId","rt","sessionGeneration","1"))));}
                assertTrue(child.waitFor(50,TimeUnit.SECONDS));if(error.get()!=null)throw new AssertionError("receiver",error.get());assertEquals(0,child.exitValue(),"Runtime peer failed: "+trace);
                var report=json.readTree(child.getInputStream().readAllBytes());assertEquals(rotate?4:successor?9:7,report.path("ackCount").asInt());assertEquals(cuts.get(),report.path("receiptId").asText());if(rotate)assertEquals(1,report.path("pending").asInt());else assertEquals("1",report.path("revision").asText());
            }finally{if(child.isAlive()){child.destroyForcibly();child.waitFor();}}
        }finally{server.stop(0);}
        if(rotate){
            assertEquals(List.of("PLAN","SOURCE","REQUEST","REQUEST","RESPONSE","GET","RESPONSE"),trace);
            var data=json.readTree(originalBody.get()).path("data");String id=cuts.get();assertEquals(id,data.path("receiptId").asText());
            String url="/internal/agent/chat/dispatches/"+payload.get("dispatchId")+"/turns/"+payload.get("turnId")+"/contexts/"+payload.get("contextHash").toString().substring(7)+"/captures/"+data.path("captureId").asText()+"/usage";
            for(var req:List.of(get(url+"/receipts/"+id),get(url+"/records/"+data.path("recordId").asText()),post(url).contentType("application/json").header("Idempotency-Key",id).content(originalBody.get()))){
                req.header("Authorization","AgentRuntime "+rotated.get().sessionToken()).header("X-Agent-Id",AGENT).header("X-Agent-Installation-Id",INSTALL).header("X-Agent-Host-Id","host").header("X-Agent-Runtime-Id","rt").header("X-Agent-Session-Generation","2").header("X-Request-Id",id);
                var refusal=mvc.perform(req).andReturn().getResponse();assertEquals(404,refusal.getStatus());assertEquals("CHAT_USAGE_DISPATCH_NOT_FOUND",json.readTree(refusal.getContentAsByteArray()).path("code").asText());
            }
            assertEquals(5,count("economy_usage_capture_receipt"));assertEquals(1,count("economy_usage_revision"));assertEquals(1,count("economy_usage_attempt"));return;
        }
        assertEquals(chat?List.of("PLAN","SOURCE","REQUEST","REQUEST","RESPONSE","GET","SOURCE_FINAL","CAPTURE_FINAL"):successor?List.of("PLAN","SOURCE","SOURCE","REQUEST","REQUEST","RESPONSE","GET","SOURCE_FINAL","SOURCE_FINAL","CAPTURE_FINAL","RESPONSE"):List.of("PLAN","SOURCE","REQUEST","REQUEST","RESPONSE","GET","SOURCE_FINAL","CAPTURE_FINAL","RESPONSE"),trace);
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
        if(chat)path="/internal/agent/chat/dispatches/"+payload.get("dispatchId")+"/turns/"+payload.get("turnId")+"/contexts/"+payload.get("contextHash").toString().substring(7)+"/captures/"+NativeCaptureEvidence.captureIdentity((String)payload.get("turnId"),(String)payload.get("dispatchId"))+"/usage/receipts/"+cuts.get();
        var read=get(path).header("Authorization","AgentRuntime "+session.sessionToken()).header("X-Agent-Id",AGENT).header("X-Agent-Installation-Id",INSTALL).header("X-Agent-Host-Id","host").header("X-Agent-Runtime-Id","rt").header("X-Agent-Session-Generation","1");
        jdbc.update("UPDATE agent_task_execution_grant SET executor_owner_jiacn='foreign' WHERE grant_id='g'");assertEquals(404,mvc.perform(read).andReturn().getResponse().getStatus());
        jdbc.update("UPDATE agent_runtime_v1_installation SET status='REVOKED' WHERE installation_id=?",INSTALL);assertEquals(401,mvc.perform(read).andReturn().getResponse().getStatus());
        assertEquals(successor?9:7,count("economy_usage_capture_receipt"));assertEquals(1,count("economy_usage_attempt"));assertEquals(1,count("economy_usage_revision"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM economy_usage_revision WHERE status='PENDING_USAGE' AND cost_yuan IS NULL AND revision=1",Integer.class));
    }
    // Test boundary: committed original dispatch, not admission, model execution, or business finality.
    @SuppressWarnings("unchecked") Map<String,Object> discussionPayload() throws Exception {
        String template=Files.readString(Path.of(System.getProperty("ownedMeteredRuntime"),"conf/codex-bridge/contracts/api-hosted-wire-v1.json"));
        Map<String,Object> wire=json.readValue(template.replace("hosted-agent-contract",AGENT).replace("client-contract","client").replace("tenant-contract","0").replace("owner-contract","owner").replace("task-contract-17","task"),Map.class);
        wire.put("route","CHAT");var facts=(Map<String,Object>)wire.get("factsManifest");facts.put("participantAgentIds",List.of(AGENT));
        ((Map<String,Object>)facts.get("task")).put("assignedAgentIds",List.of(AGENT));facts.put("contextDelivery",Map.of("mode","BOOTSTRAP"));
        facts.put("taskUsageCapture",Map.of("protocol","METERED_CAPTURE_V2","captureId",NativeCaptureEvidence.captureIdentity((String)wire.get("turnId"),(String)wire.get("dispatchId"))));
        String hash=cn.jia.chat.service.ChatDeliberationService.digest(Map.of("sourceVector",wire.get("sourceVector"),"facts",facts));wire.put("contextHash",hash);
        var snap=(Map<String,Object>)wire.get("contextSnapshot");snap.put("facts",facts);snap.put("contextHash",hash);return wire;
    }
    void seedSentDiscussion(Map<String,Object> w,TaskDiscussionMembershipService members,cn.jia.chat.deliberation.ChatTaskDispatchOriginStore origins){
        var ds=Objects.requireNonNull(jdbc.getDataSource());new ResourceDatabasePopulator(new ClassPathResource("db/chat-deliberation-schema.sql"),new ClassPathResource("db/chat-task-dispatch-origin-v1.sql")).execute(ds);
        String turn=(String)w.get("turnId"),run=(String)w.get("dispatchId"),req=(String)w.get("requestId"),ctx=(String)w.get("contextHash"),snap=(String)w.get("contextSnapshotId"),event=(String)w.get("messageId");
        String digest=NativeCaptureEvidence.digest(List.of("fixture-admission",req,turn,run));
        tx.executeWithoutResult(status->{
            jdbc.update("INSERT INTO chat_request(tenant_id,owner_jiacn,client_id,request_id,request_revision,request_digest,conversation_id,conversation_generation,user_message_id,aggregate_state,created_at,updated_at) VALUES('0','owner','client',?,7,?,'42',3,1,'RUNNING',1,1)",req,"sha256:"+digest);
            jdbc.update("INSERT INTO chat_turn(turn_id,tenant_id,owner_jiacn,client_id,request_id,request_revision,conversation_id,conversation_generation,target_agent_id,snapshot_id,context_digest,dispatch_id,route,state,created_at,updated_at) VALUES(?,'0','owner','client',?,7,'42',3,?,?,?,?,'CHAT','RUNNING',1,1)",turn,req,AGENT,snap,ctx,run);
            var original=new LinkedHashMap<>(w);original.remove("contextSnapshot");
            jdbc.update("INSERT INTO chat_dispatch_outbox(event_id,tenant_id,owner_jiacn,client_id,turn_id,dispatch_id,event_type,task_grant_id,task_grant_version,status,payload_json,created_at,updated_at) VALUES(?,'0','owner','client',?,?,'DISPATCH','g',1,'SENT',?,1,1)",event,turn,run,json.writeValueAsString(original));
            origins.observe(event,ctx,INSTALL,"host","rt",1);assertEquals(NativeCaptureEvidence.captureIdentity(turn,run),members.admit(new cn.jia.agent.service.AgentTaskExecutionGrantService.Scope("0","client","owner"),"task",turn,run,digest,true));
        });assertEquals(0,count("agent_personal_workspace_execution"));
    }
    // The test supplies only the upstream quote estimate and acceptance decision. Wallet, membership,
    // response association, whole-task projection and repricing use production services on this fixture DB.
    record Scenario(TaskBillingService.Task task,TaskBillingService billing,MeasuredTaskRepricing.Reference reference,String sample){}
    void lockTask(){assertEquals("task",jdbc.queryForObject("SELECT task_id FROM agent_task_meta WHERE tenant_id='0' AND client_id='client' AND owner_jiacn='owner' AND task_id='task' FOR UPDATE",String.class));}
    Scenario confirmTestTask(tools.jackson.databind.JsonNode fixture,SqlSessionTemplate sql,DataSourceTransactionManager manager,String schema,boolean retry,boolean discussion){
        int begin=schema.indexOf("CREATE TABLE IF NOT EXISTS agent_task_meta (");assertTrue(begin>=0);jdbc.execute(schema.substring(begin,schema.indexOf(";\n",begin)));
        jdbc.update("INSERT INTO agent_task_meta(task_id,owner_jiacn,tenant_id,client_id) VALUES('task','owner','0','client')");
        new EconomySchemaInitializer(jdbc).afterPropertiesSet();
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("db/economy-task-quote-preparation-v1.sql")).execute(jdbc.getDataSource());jdbc.update("INSERT INTO economy_account(account_id,owner_type,owner_id,purpose,currency,balance_wen,allow_negative,status,version,tenant_id,client_id,create_time,update_time) VALUES('wallet','USER','payer','AVAILABLE','WEN',100,0,'ACTIVE',0,'0','client',1,1)");
        var ledger=sql.getMapper(EconomyLedgerMapper.class);var gate=new EconomyPreviewGate(new EconomyPreviewProperties(true,List.of(new EconomyPreviewProperties.AllowedScope("0","client"))));
        var billing=transactional(new TaskBillingServiceImpl(sql.getMapper(EconomyTaskBillingMapper.class),ledger,new EconomyPostingServiceImpl(ledger,manager,gate),json,null),TaskBillingService.class,manager);
        var task=new TaskBillingService.Task(new EconomyScope("0","client"),"owner","task");var binding=new TaskBillingService.Binding(1,"a".repeat(64),"b".repeat(64),AGENT,"independent-quote");
        var vector=new ArrayList<>(Collections.nCopies(1024,0f));vector.set(0,1f);
        var features=new TaskEstimateFeatures(TaskEstimateFeatures.VERSION,"template",binding.requirementSha256(),binding.inputSnapshotDigest(),"c".repeat(64),new TaskEstimateFeatures.Identity("fixture","fixture",1024),vector,List.of(),null);
        var plan=fixture.at("/records/plan/observation");var g=plan.get("dispatchGroup");String config=g.get("executionConfigSha256").asText(),route=g.get("routeSha256").asText(),tools=g.get("toolConfigSha256").asText(),policy=plan.get("coveragePolicyVersion").asText(),basis="RESPONSES_TEXT_TOKENS_V1";
        assertEquals(fixture.get("admission").asText(),NativeCaptureEvidence.digest(List.of(policy,plan.get("engineVersion").asText(),basis,config,route,tools,fixture.at("/records/source-0/observation/channelNamespace").asText())));
        var group=new TaskBillingService.MeasuredGroup(g.get("version").asText(),config,route,"reference-only",tools,policy);
        var rates=new ModelTokenPrice("test","reference","standard","1","SYNTHETIC TEST ONLY",Map.of(ModelTokenPrice.Category.INPUT,new BigDecimal("0.1"),ModelTokenPrice.Category.CACHED_INPUT,new BigDecimal("0.05"),ModelTokenPrice.Category.OUTPUT,new BigDecimal("0.2")),null,Map.of());
        var reference=new MeasuredTaskRepricing.Reference("test-reference",Map.of("MODEL_RESPONSE",Map.of("INFERENCE",new MeasuredTaskRepricing.TokenRate(basis,rates))));
        var historical=new MeasuredTaskRepricing.Fact(new MeasuredTaskRepricing.Operation("fixture","prior"),"MODEL_RESPONSE","INFERENCE","test-only",new MeasuredTaskRepricing.Tokens(basis,Map.of(ModelTokenPrice.Category.INPUT,1_000_000L)));
        var estimate=MeasuredNeighborEstimate.aggregate(reference,List.of(new MeasuredNeighborEstimate.Neighbor("fixture-prior","1",BigDecimal.ONE,List.of(historical))));
        var prepared=new TaskBillingService.MeasuredPreparation(features,group,"fixture","test-calibration","test-only",estimate);
        tx.executeWithoutResult(st->{lockTask();var q=billing.storeMeasured(task,binding,"payer",prepared);assertEquals(10,q.amountWen());billing.reserve(task,q.quoteId(),binding,"payer");
            var member=new cn.jia.agent.entity.PersonalWorkspaceExecutionEntity().setOwnerJiacn("owner").setTaskId("task").setExecutionId("exec1").setRunId("run-exec1").setRequestHash("a".repeat(64));member.setTenantId("0");member.setClientId("client");executions.registerTaskMember(member,fixture.at("/records/plan/captureId").asText());
            if(discussion){new TaskDiscussionMembershipService(executions).admit(new cn.jia.agent.service.AgentTaskExecutionGrantService.Scope("0","client","owner"),"task","initial-turn","initial-dispatch","d".repeat(64),false);}
            if(retry){row("retry","owner","task","CONVERSATION","RESULT_COMMITTED",6);member.setExecutionId("retry").setRunId("run-retry");executions.registerTaskMember(member,null);}});
        return new Scenario(task,billing,reference,jdbc.queryForObject("SELECT sample_id FROM economy_task_cost_sample",String.class));
    }
    void verifyAcceptedSample(Scenario s,SqlSessionTemplate sql,DataSourceTransactionManager manager,boolean retry){
        var mapper=sql.getMapper(ConfirmedSampleMapper.class);var reader=transactional(new ConfirmedSampleEvidenceReader(mapper,new tools.jackson.databind.ObjectMapper()),ConfirmedSampleEvidence.class,manager);var decoder=new NativeSampleResponseDecoder();
        assertEquals(ConfirmedSampleEvidence.Verdict.ACTIVE,reader.inspect(s.task,s.sample).verdict());
        tx.executeWithoutResult(st->{lockTask();executions.closeTaskMembers("0","client","owner","task",cn.jia.agent.dao.TaskExecutionClosureDao.Outcome.ACCEPTED,"decision",11,777);s.billing.capture(s.task);});
        var terminal=executions.pending("0","client",executions.pendingPage("0","client","").getFirst().fact());
        var receiver=transactional(new TaskMembershipReceiptStore(sql.getMapper(TaskMembershipReceiptMapper.class)),TaskMembershipReceipt.class,manager);var delivery=new TaskMembershipDelivery(executions,receiver,new DefaultListableBeanFactory().getBeanProvider(ScheduleService.class),new FixedTenantAdmissionPolicy("0","client"));
        var invocation=new ScheduledInvocation("0",new ScheduleKey("client",delivery.getName(),terminal.fact(),""),1);
        delivery.executeScheduled(invocation);delivery.executeScheduled(invocation);assertTrue(receiver.open(terminal).verified());
        var measured=reader.measure(s.task,s.sample,decoder);assertEquals(retry?2:1,measured.assessment().members());
        var projection=new ConfirmedSampleProjectionService(reader,mapper,json,manager);assertTrue(projection.refresh(s.task,s.sample,decoder));assertFalse(projection.refresh(s.task,s.sample,decoder));
        assertEquals(retry?"WAITING_EVIDENCE":"READY",jdbc.queryForObject("SELECT sample_state FROM economy_task_cost_sample_revision WHERE sample_revision=(SELECT evidence_revision FROM economy_task_cost_sample)",String.class));
        if(retry){assertEquals(ConfirmedSampleEvidence.Verdict.USAGE_INCOMPLETE,measured.assessment().verdict());assertTrue(measured.operations().isEmpty());
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM economy_task_cost_sample_revision WHERE sample_state='WAITING_EVIDENCE' AND reason_code='USAGE_INCOMPLETE' AND member_count=2 AND JSON_LENGTH(operation_refs)=0",Integer.class));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM economy_task_cost_sample_revision WHERE sample_state='READY'",Integer.class));}else{assertEquals(2,measured.operations().size());var quoted=MeasuredTaskRepricing.reprice(s.reference,measured.operations().stream().map(ConfirmedSampleEvidence.Operation::fact).toList());assertEquals(2,quoted.lines().size());assertEquals(0,new BigDecimal("0.00000250").compareTo(quoted.yuan()));}
        assertEquals(10,jdbc.queryForObject("SELECT amount_wen FROM economy_task_charge WHERE state='CAPTURED'",Integer.class));assertEquals(2,count("economy_transaction"));
    }
    int count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}
}
