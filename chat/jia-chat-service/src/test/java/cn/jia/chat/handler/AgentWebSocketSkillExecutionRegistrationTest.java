package cn.jia.chat.handler;

import cn.jia.agent.service.SkillCapabilityLookup.*;
import cn.jia.agent.service.AgentManagedSessionLookup.SessionFence;
import cn.jia.agent.security.AgentRuntimeAuthenticationService;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.*;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static cn.jia.chat.handler.AgentWebSocketRuntimeAuthenticationTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentWebSocketSkillExecutionRegistrationTest {
    final Scope scope = new Scope(TENANT, CLIENT, OWNER, AGENT);
    final SessionFence fence = new SessionFence(UnifiedRuntimeTestSupport.INSTALLATION, "host-1", "runtime-a", 1);
    final SkillExecutionSessionRegistry registry = new SkillExecutionSessionRegistry();
    final ObjectMapper json = new ObjectMapper();

    @Test void slowSubjectCannotHoldRegistrationOrReturnEvidenceAfterScopeChanges() throws Exception {
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        var catalog=new Catalog("1",List.of());var other=new Scope(TENANT,CLIENT,OWNER,"another-agent");
        registry.register("slow",scope,fence,catalog,()->{
            entered.countDown();try{return release.await(10,java.util.concurrent.TimeUnit.SECONDS);}
            catch(InterruptedException e){Thread.currentThread().interrupt();return false;}
        });
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try{
            var reading=pool.submit(()->registry.lookup(scope,fence));assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));
            var changed=pool.submit(()->{
                registry.register("other",other,fence,catalog,()->true);
                assertEquals(State.AVAILABLE,registry.lookup(other,fence).state());
                // Another live entry appears without replacing the original key: validating
                // only the selected entry at the end would disclose an ambiguous subject.
                registry.register("replacement",scope,fence,catalog,()->true);
            });changed.get(5,java.util.concurrent.TimeUnit.SECONDS);release.countDown();
            assertEquals(State.FENCED,reading.get(5,java.util.concurrent.TimeUnit.SECONDS).state());
            assertEquals(State.AMBIGUOUS,registry.lookup(scope,fence).state());
            registry.suspend("slow");assertEquals("replacement",registry.lookup(scope,fence).sessionId());
            registry.remove("replacement");assertEquals(State.DISCONNECTED,registry.lookup(scope,fence).state());
        }finally{release.countDown();pool.shutdownNow();}
    }

    private cn.jia.agent.service.NativeTaskPreparation.Frozen prepared(String provider){
        var binding=new cn.jia.agent.service.NativeTaskPreparation.Binding(new cn.jia.agent.service.AgentTaskExecutionGrantService.Scope(TENANT,CLIENT,"task-owner"),
            "private-task",1,1,"b".repeat(64),"c".repeat(64),new cn.jia.agent.service.AgentExternalServicePolicyService.QuoteTarget(AGENT,provider,1,1));
        return new cn.jia.agent.service.NativeTaskPreparation.Frozen("a".repeat(64),new cn.jia.agent.service.NativeTaskPreparation.Intent("TASK_NATIVE_LOCAL_FILES_V1",binding,
            new cn.jia.agent.service.NativeTaskPreparation.Output(cn.jia.agent.service.NativeTaskPreparation.Mode.ARTIFACTS,List.of("text/plain"))));
    }
    @Test void preparedProbeNeverDispatchesInTransactionOrAcrossProviders()throws Exception{
        var f=fixture();f.handler.afterConnectionEstablished(f.session);
        assertNull(f.handler.measure(prepared(OWNER),fence).toCompletableFuture().join());register(f,null);
        assertNull(f.handler.measure(prepared("other"),fence).toCompletableFuture().join());
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        try{assertNull(f.handler.measure(prepared(OWNER),fence).toCompletableFuture().join());}
        finally{org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);}
        assertTrue(f.frames.stream().noneMatch(frame->frame.contains("runtime.task.configuration.probe")));
    }
    @Test void preparedProbeUsesFixedRuntimeCodecAndRejectsUncorrelatedOrExtendedMeasurements()throws Exception{
        String root=System.getProperty("ownedMeteredRuntime");org.junit.jupiter.api.Assumptions.assumeTrue(root!=null);
        var f=fixture();f.handler.afterConnectionEstablished(f.session);register(f,null);
        var future=f.handler.measure(prepared(OWNER),fence).toCompletableFuture();String wire=f.frames.getLast();
        assertFalse(wire.contains("private-task"));assertTrue(wire.contains("runtime.task.configuration.probe"));
        var process=new ProcessBuilder("node","--input-type=module","-e","""
            import {fromAgentWire,toAgentWire} from './agent-message-wire.mjs';import fs from 'node:fs';
            const {messageId,requestId,tenantId,clientId,agentId,installationId,hostId,runtimeInstanceId,sessionGeneration,intentSha256,outputs}=fromAgentWire(fs.readFileSync(0,'utf8'));
            if(outputs.dataSchema!==null || outputs.mimeTypes[0]!=='text/plain')throw Error('output mismatch');
            const measurement={version:'NATIVE_DISPATCH_GROUP_V1',executionConfigSha256:'1'.repeat(64),routeSha256:'2'.repeat(64),toolConfigSha256:'3'.repeat(64),capturePolicyVersion:'NATIVE_LOCAL_ARTIFACT_TEXT_V1'};
            console.log(JSON.stringify(toAgentWire({messageType:'runtime.task.configuration.measured',messageId:'result',requestId,correlationId:messageId,agentId,sourceAgentId:agentId,tenantId,clientId,installationId,hostId,runtimeInstanceId,sessionGeneration,intentSha256,measurement})));
            """).directory(java.nio.file.Path.of(root,"conf/codex-bridge").toFile()).redirectErrorStream(true).start();
        try{try(var out=process.getOutputStream()){out.write(wire.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
            assertTrue(process.waitFor(3,java.util.concurrent.TimeUnit.SECONDS));String reply=new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).trim();assertEquals(0,process.exitValue(),reply);
            for(String bad:List.of(reply.replace("a".repeat(64),"b".repeat(64)),reply.replace("NATIVE_DISPATCH_GROUP_V1","OTHER"),reply.replace("executionConfigSha256","price"))){
                f.handler.handleTextMessage(f.session,new TextMessage(bad));assertFalse(future.isDone());}
            f.handler.handleTextMessage(f.session,new TextMessage(reply));assertEquals("2".repeat(64),future.join().routeSha256());
        }finally{process.destroyForcibly();}
    }
    Fixture fixture() throws Exception {
        var f = new Fixture(false); f.handler.setSkillExecutionSessions(registry);
        when(f.auth.bind(eq("socket-a"), any(AgentRuntimeAuthenticationService.Proof.class), any()))
                .thenReturn(new AgentRuntimeAuthenticationService.Receipt("native-runtime-v1", TENANT, CLIENT, OWNER,
                        AGENT, "runtime-a", UnifiedRuntimeTestSupport.INSTALLATION, "host-1", 1, true));
        return f;
    }
    @SuppressWarnings("unchecked") String wire(Fixture f, Object report) {
        Map<String,Object> payload = json.readValue(f.command(), Map.class);
        UnifiedRuntimeTestSupport.registrationProof(f.session, payload);
        payload.put("durableStateHealthy", true); payload.put("readyCommandTypes", List.of());
        if (report != null) payload.put("skillExecution", report);
        return json.writeValueAsString(cn.jia.agent.common.AgentMessageWire.event("agent.register",payload,null));
    }
    void register(Fixture f, Object report) { f.handler.handleTextMessage(f.session, new TextMessage(wire(f, report))); }

    @Test void onlyDeliveredAuthenticatedRegistrationActivatesExactTrustedSubject() throws Exception {
        var f = fixture();
        doAnswer(inv -> {
            String frame = ((TextMessage)inv.getArgument(0)).getPayload();
            if (frame.contains("agent.registered")) assertEquals(State.DISCONNECTED, registry.lookup(scope, fence).state());
            f.frames.add(frame); return null;
        }).when(f.session).sendMessage(any());
        register(f, SkillExecutionFixtures.catalog());
        assertEquals(State.AVAILABLE, registry.lookup(scope, fence).state());
        assertEquals(2, registry.lookup(scope, fence).catalog().skills().size());
        assertEquals(State.DISCONNECTED, registry.lookup(new Scope(TENANT, CLIENT, "foreign-owner", AGENT), fence).state());
        assertEquals(State.FENCED, registry.lookup(scope, new SessionFence(fence.runtimeInstallationId(), "foreign-host", "runtime-a", 1)).state());
    }
    @Test void readyListenersOnlyRunAfterAuthenticatedDeliveredLocalRegistration() throws Exception {
        var f=fixture();var listener=mock(cn.jia.agent.service.AgentSessionReadyListener.class);
        f.handler.setSessionReadyListeners(List.of(listener));
        doAnswer(call->{
            assertEquals(State.AVAILABLE,registry.lookup(scope,fence).state());
            return null;
        }).when(listener).sessionReady(TENANT,CLIENT,AGENT);
        register(f,SkillExecutionFixtures.catalog());
        verify(listener).sessionReady(TENANT,CLIENT,AGENT);
        clearInvocations(listener);
        doThrow(new java.io.IOException("receipt unavailable")).when(f.session).sendMessage(any());
        register(f,SkillExecutionFixtures.catalog());verifyNoInteractions(listener);
    }
    @Test void failedDurableReadyCallbackDoesNotKeepARegisteredTransport() throws Exception {
        var f=fixture();var listener=mock(cn.jia.agent.service.AgentSessionReadyListener.class);
        f.handler.setSessionReadyListeners(List.of(listener));
        doThrow(new IllegalStateException("schedule unavailable")).when(listener).sessionReady(TENANT,CLIENT,AGENT);
        register(f,SkillExecutionFixtures.catalog());
        assertEquals(State.DISCONNECTED,registry.lookup(scope,fence).state());
        assertTrue(f.frames.stream().anyMatch(frame->frame.contains("AGENT_REGISTRATION_UNAVAILABLE")));
        verify(f.auth,atLeastOnce()).disconnect("socket-a");
        verify(f.session).close(CloseStatus.SERVER_ERROR);
    }
    @Test void sameSocketCapabilityRefreshDoesNotWithdrawAcknowledgedAuthority() throws Exception {
        var f = fixture(); register(f, SkillExecutionFixtures.catalog());
        clearInvocations(f.auth);
        doAnswer(inv -> {
            // Concurrent HTTP admissions and directory queries can occur at both
            // stages of a readiness refresh, before the replacement is committed.
            assertEquals(State.AVAILABLE, registry.lookup(scope, fence).state());
            return new cn.jia.agent.entity.AgentRegisterResultDTO(AGENT, TOKEN, "online");
        }).when(f.service).register(any());
        doAnswer(inv -> {
            assertEquals(State.AVAILABLE, registry.lookup(scope, fence).state());
            f.frames.add(((TextMessage) inv.getArgument(0)).getPayload());
            return null;
        }).when(f.session).sendMessage(any());
        register(f, SkillExecutionFixtures.catalog());
        verify(f.auth, never()).disconnect("socket-a");
        verify(f.service, times(2)).register(any());
        assertEquals(State.AVAILABLE, registry.lookup(scope, fence).state());
    }

    @Test void actualAuthenticationRecordIsProjectedBeforeRegistrationReceiptSerialization() throws Exception {
        var f = fixture();
        when(f.auth.bind(eq("socket-a"), any(AgentRuntimeAuthenticationService.Proof.class), any()))
                .thenReturn(new AgentRuntimeAuthenticationService.Receipt("native-runtime-v1", TENANT, CLIENT, OWNER,
                        AGENT, "runtime-a", UnifiedRuntimeTestSupport.INSTALLATION, "host-1", 9007199254740993L, true));
        register(f, SkillExecutionFixtures.catalog());
        var frames = f.frames.stream().map(json::readTree)
                .filter(frame -> "agent.registered".equals(frame.path("messageType").asText())).toList();
        assertEquals(1, frames.size());
        var auth = frames.getFirst().path("data").path("runtimeAuth");
        assertEquals(UnifiedRuntimeTestSupport.INSTALLATION, auth.path("runtimeInstallationId").asText());
        assertFalse(auth.has("installationId"));
        assertTrue(auth.path("sessionGeneration").isTextual());
        assertEquals("9007199254740993", auth.path("sessionGeneration").asText());
        assertEquals(OWNER, auth.path("ownerJiacn").asText());
        assertFalse(auth.has("tokenDigest")); assertFalse(auth.has("sessionToken"));
    }
    @Test void missingOrMalformedReportReplacesPriorDirectoryWithoutDisruptingOtherRegistration() throws Exception {
        var f = fixture(); register(f, SkillExecutionFixtures.catalog());
        register(f, null); assertEquals(State.INVALID_DECLARATION, registry.lookup(scope, fence).state());
        register(f, SkillExecutionFixtures.catalog());
        var report = SkillExecutionFixtures.catalog(); report.put("ownerJiacn", "foreign-owner"); register(f, report);
        assertEquals(State.INVALID_DECLARATION, registry.lookup(scope, fence).state());
        assertTrue(f.frames.stream().anyMatch(frame -> frame.contains("agent.registered")));
    }
    @Test void duplicateJsonKeysRejectAndRemovePriorDirectoryWithoutNormalizingThemAway() throws Exception {
        var f = fixture(); register(f, SkillExecutionFixtures.catalog());
        String duplicated = wire(f, SkillExecutionFixtures.catalog()).replace("\"catalogRevision\":\"7\"", "\"catalogRevision\":\"7\",\"catalogRevision\":\"8\"");
        assertTrue(duplicated.contains("\"catalogRevision\":\"8\""));
        f.handler.handleTextMessage(f.session, new TextMessage(duplicated));
        assertEquals(State.DISCONNECTED, registry.lookup(scope, fence).state());
        assertTrue(f.frames.stream().anyMatch(frame -> frame.contains("MESSAGE_INVALID")));
    }
    @Test void failedAuthenticationOrReceiptDeliveryNeverRetainsPriorDirectory() throws Exception {
        var f = fixture(); register(f, SkillExecutionFixtures.catalog());
        when(f.auth.bind(anyString(), any(AgentRuntimeAuthenticationService.Proof.class), any()))
                .thenThrow(new IllegalArgumentException("private"));
        register(f, SkillExecutionFixtures.catalog()); assertEquals(State.DISCONNECTED, registry.lookup(scope, fence).state());
        f = fixture(); register(f, SkillExecutionFixtures.catalog());
        doThrow(new java.io.IOException("private")).when(f.session).sendMessage(any());
        register(f, SkillExecutionFixtures.catalog()); assertEquals(State.DISCONNECTED, registry.lookup(scope, fence).state());
    }
    @Test void recommendationObservationIsNotOrdinaryAuthorityAndCannotHideAmbiguityOrUnhealthyRegistration() throws Exception {
        var f=fixture();register(f,SkillExecutionFixtures.catalog());
        var channel=mock(AgentRuntimeAuthenticationService.RecommendationChannel.class);
        when(channel.issued(notNull())).thenReturn(true);
        when(channel.matches("socket-a",TENANT,CLIENT,OWNER,AGENT,"runtime-a")).thenReturn(true);
        when(f.auth.isCurrentBinding(any(),any(),any(),any(),any(),any())).thenReturn(false);
        assertEquals(State.AVAILABLE,registry.forRecommendation(scope,fence,channel).state());
        assertEquals(State.DISCONNECTED,registry.lookup(scope,fence).state());
        registry.register("other-session",scope,fence,null,()->true);
        assertEquals(State.AMBIGUOUS,registry.forRecommendation(scope,fence,channel).state());
        registry.remove("other-session");
        when(f.auth.isCurrentBinding(any(),any(),any(),any(),any(),any())).thenReturn(true);
        var wrong=mock(AgentRuntimeAuthenticationService.RecommendationChannel.class);
        assertEquals(State.FENCED,registry.forRecommendation(scope,fence,wrong).state());
        assertEquals(State.FENCED,registry.forRecommendation(scope,fence,new cn.jia.agent.service.RecommendationCatalogLookup.Observation() {}).state());
        String unhealthy=wire(f,SkillExecutionFixtures.catalog()).replace("\"durableStateHealthy\":true","\"durableStateHealthy\":false");
        f.handler.handleTextMessage(f.session,new TextMessage(unhealthy));
        assertEquals(State.DISCONNECTED,registry.forRecommendation(scope,fence,channel).state());
    }
    @Test void disconnectUnhealthyStateAndRevokedBindingNeverReturnCachedFacts() throws Exception {
        var f = fixture(); register(f, SkillExecutionFixtures.catalog());
        when(f.auth.isCurrentBinding(any(), any(), any(), any(), any(), any())).thenReturn(false);
        assertEquals(State.DISCONNECTED, registry.lookup(scope, fence).state());
        when(f.auth.isCurrentBinding(any(), any(), any(), any(), any(), any())).thenReturn(true);
        f.handler.afterConnectionClosed(f.session, CloseStatus.NORMAL);
        assertEquals(State.DISCONNECTED, registry.lookup(scope, fence).state());
        register(f, SkillExecutionFixtures.catalog());
        String unhealthy = wire(f, SkillExecutionFixtures.catalog()).replace("\"durableStateHealthy\":true", "\"durableStateHealthy\":false");
        f.handler.handleTextMessage(f.session, new TextMessage(unhealthy));
        assertEquals(State.DISCONNECTED, registry.lookup(scope, fence).state());
    }
    @Test void presenceCannotReplaceDirectoryOrGrantReadiness() throws Exception {
        var f = fixture(); register(f, SkillExecutionFixtures.catalog());
        var runtime = new cn.jia.agent.entity.AgentRuntimeDTO(); runtime.setAgentId(AGENT); runtime.setStatus("online");
        when(f.service.updateStatus(eq(AGENT), any())).thenReturn(runtime);
        String wire = wire(f, Map.of("forged", true)).replace("agent.register", "agent.presence");
        var root=json.readTree(wire); ((tools.jackson.databind.node.ObjectNode)root.get("data")).put("status","online"); wire=json.writeValueAsString(root);
        f.handler.handleTextMessage(f.session, new TextMessage(wire));
        assertEquals("7", registry.lookup(scope, fence).catalog().catalogRevision());
    }
}
