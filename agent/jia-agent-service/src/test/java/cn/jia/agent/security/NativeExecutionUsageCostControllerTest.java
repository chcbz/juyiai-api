package cn.jia.agent.security;

import cn.jia.agent.api.NativeExecutionUsageCostController;
import cn.jia.agent.service.usage.NativeExecutionUsageCostRecorder;
import cn.jia.core.entity.JsonRequest;
import cn.jia.economy.usage.TokenUsageCost;
import cn.jia.economy.usage.UsageCostService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.ObjectMapper;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NativeExecutionUsageCostControllerTest {
    private static final String PATH="/internal/agent/executions/exec/runs/run/usage";
    @Test void nativeReceiptRouteDoesNotFallBackToBrowserCredentials() {
        assertTrue(AgentRuntimeAuthenticationFilter.selectsRuntimeCredentialLane(new MockHttpServletRequest("POST",PATH)));
        assertTrue(AgentRuntimeAuthenticationFilter.allowed(new MockHttpServletRequest("POST",PATH)));
        assertTrue(AgentRuntimeAuthenticationFilter.allowed(new MockHttpServletRequest("GET",PATH+"/receipts/key")));
        assertTrue(AgentRuntimeAuthenticationFilter.allowed(new MockHttpServletRequest("GET",PATH+"/records/"+"a".repeat(64))));
        assertFalse(AgentRuntimeAuthenticationFilter.allowed(new MockHttpServletRequest("GET",PATH+"/records/not-a-digest")));
        assertFalse(AgentRuntimeAuthenticationFilter.allowed(new MockHttpServletRequest("POST",PATH+"/records/"+"a".repeat(64))));
        assertFalse(AgentRuntimeAuthenticationFilter.allowed(new MockHttpServletRequest("POST",PATH+"/receipts/key")));
        for(String method : new String[]{"GET","PUT","DELETE"})
            assertFalse(AgentRuntimeAuthenticationFilter.allowed(new MockHttpServletRequest(method,PATH)));
    }
    @Test void responseAcknowledgesEvidenceWithTrustedScopeAndDecimalRevisionNotTaskCompletion() {
        var recorder=mock(NativeExecutionUsageCostRecorder.class);
        var auth=mock(AgentRuntimeAuthenticationService.class);
        var principal=new AgentRuntimeAuthentication(new AgentRuntimeAuthentication.Scope("0","client","executor","agent","instance"));
        principal.setDetails(new AgentRuntimeAuthenticationService.Proof(principal.getPrincipal(),"installation","host",1,"digest",1,1));
        when(auth.withNativeFence(eq(principal),any())).thenAnswer(i -> ((Supplier<?>) i.getArgument(1)).get());
        when(recorder.record(any(),eq("exec"),eq("run"),any())).thenAnswer(i -> {
            var scope=(cn.jia.agent.service.PersonalWorkspaceExecutionService.RuntimeScope)i.getArgument(0);
            assertEquals("executor",scope.executorOwnerJiacn()); assertEquals("agent",scope.agentId());
            return new UsageCostService.Receipt("b".repeat(64),2,TokenUsageCost.Status.PENDING_USAGE,null,false);
        });
        var body=new NativeExecutionUsageCostController.Request("exec",NativeExecutionUsageCostRecorder.Phase.TERMINAL,"requested-only",new ObjectMapper().readTree("{\"status\":\"UNVERIFIED\"}"));
        var controller=new NativeExecutionUsageCostController(recorder,auth,null,null,null,null);
        var response=controller.record("exec","run",new JsonRequest<>(1,"fixture",body),principal);
        assertEquals(200,response.getStatusCode().value());
        var receipt=response.getBody().getData();
        assertEquals("exec",receipt.executionId());assertEquals("2",receipt.revision());
        assertEquals("PENDING_USAGE",receipt.status());assertNull(receipt.costYuan());
        verify(auth).withNativeFence(eq(principal),any());
    }
    @Test void v2EvidenceUsesOriginalOwnerAndReadbackCannotBorrowAnotherRun() throws Exception {
        var executions=mock(cn.jia.agent.dao.PersonalWorkspaceExecutionDao.class);
        var auth=mock(AgentRuntimeAuthenticationService.class);var journal=mock(cn.jia.economy.usage.UsageCaptureJournal.class);
        var principal=new AgentRuntimeAuthentication(new AgentRuntimeAuthentication.Scope("t","client","executor","agent","instance"));
        principal.setDetails(new AgentRuntimeAuthenticationService.Proof(principal.getPrincipal(),"installation","host",1,"digest",1,1));
        when(auth.withNativeFence(eq(principal),any())).thenAnswer(i->((Supplier<?>)i.getArgument(1)).get());
        var snapshots=mock(cn.jia.agent.dao.SkillExecutionDao.class);
        var ctrl=new NativeExecutionUsageCostController(mock(NativeExecutionUsageCostRecorder.class),auth,new cn.jia.agent.service.usage.NativeCaptureAuthority(executions,snapshots),journal,null,null);
        var json=new ObjectMapper();try(var in=getClass().getResourceAsStream("/usage/usage-capture-v2-fixtures.json")){
            var m=json.readTree(in).get("cases").get(0).get("messages").get(0);var d=m.get("request").get("body").get("data");
            String exec=d.get("executionId").asText(),run=d.get("runId").asText(),receipt=d.get("receiptId").asText();
            var row=new cn.jia.agent.entity.PersonalWorkspaceExecutionEntity().setUsageMode("RESPONSE_V2").setCaptureProtocol("METERED_CAPTURE_V2").setOwnerJiacn("original").setTaskId("original-task");
            when(executions.findForNativeUsageCost("t","client","executor","agent",exec,run)).thenReturn(row);
            var accepted=new cn.jia.economy.usage.UsageCaptureJournal.Result(cn.jia.economy.usage.UsageCaptureJournal.State.DURABLE,m.get("ack").get("body").get("data").toString());
            when(journal.register(any(),any())).thenAnswer(i->{var b=i.getArgument(0,cn.jia.economy.usage.UsageCaptureJournal.Binding.class);assertEquals("original",b.ownerJiacn());assertEquals("original-task",b.taskId());return accepted;});
            when(journal.linkMetadata(any(),eq(receipt),any())).thenReturn(accepted);
            java.util.Map<String,Object> payload=json.convertValue(d,java.util.Map.class);
            assertEquals(409,ctrl.submit(exec,run,new JsonRequest<>(1,receipt,payload),receipt,principal).getStatusCode().value());
            ((java.util.Map<String,Object>)payload.get("observation")).put("snapshotSha256","a".repeat(64));
            var evidence=new java.util.HashMap<>(payload);for(String k:java.util.List.of("expectedRevision","receiptId","evidenceSha256"))evidence.remove(k);
            payload.put("evidenceSha256",cn.jia.agent.service.usage.NativeCaptureEvidence.digest(evidence));
            when(snapshots.snapshotDigest("t","client","original",exec)).thenReturn("b".repeat(64));
            assertEquals(409,ctrl.submit(exec,run,new JsonRequest<>(1,receipt,payload),receipt,principal).getStatusCode().value());verifyNoInteractions(journal);
            when(snapshots.snapshotDigest("t","client","original",exec)).thenReturn("a".repeat(64));
            assertEquals(200,ctrl.submit(exec,run,new JsonRequest<>(1,receipt,payload),receipt,principal).getStatusCode().value());
            when(journal.readReceipt(any(),eq(receipt))).thenReturn(accepted);assertEquals(200,ctrl.receipt(exec,run,receipt,principal).getStatusCode().value());
            assertThrows(cn.jia.agent.service.PersonalWorkspaceExecutionService.Failure.class,()->ctrl.receipt(exec,"other",receipt,principal));
            when(journal.readRecord(any(),eq(d.get("recordId").asText()))).thenReturn(accepted);
            assertEquals(200,ctrl.current(exec,run,d.get("recordId").asText(),principal).getStatusCode().value());
            assertThrows(cn.jia.agent.service.PersonalWorkspaceExecutionService.Failure.class,()->ctrl.current(exec,"other",d.get("recordId").asText(),principal));
            row.setUsageMode(null);assertThrows(cn.jia.agent.service.PersonalWorkspaceExecutionService.Failure.class,()->ctrl.receipt(exec,run,receipt,principal));
            verify(journal,times(1)).readReceipt(any(),any());
        }
    }
    @Test void startedEvidenceCannotClaimUsageAndMissingPhaseCannotGuessSemantics() {
        var recorder=mock(NativeExecutionUsageCostRecorder.class);
        var controller=new NativeExecutionUsageCostController(recorder,mock(AgentRuntimeAuthenticationService.class),null,null,null,null);
        for (var phase : new NativeExecutionUsageCostRecorder.Phase[]{null, NativeExecutionUsageCostRecorder.Phase.STARTED}) {
            var body=new NativeExecutionUsageCostController.Request("exec",phase,"requested-only",new ObjectMapper().readTree("{}"));
            assertThrows(IllegalArgumentException.class, () -> controller.record("exec","run",new JsonRequest<>(1,"fixture",body),null));
        }
        verifyNoInteractions(recorder);
    }

}
