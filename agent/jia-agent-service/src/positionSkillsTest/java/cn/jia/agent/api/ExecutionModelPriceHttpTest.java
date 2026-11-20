package cn.jia.agent.api;

import cn.jia.agent.position.PositionSkillException;
import cn.jia.agent.security.*;
import cn.jia.agent.service.*;
import cn.jia.agent.service.PersonalWorkspaceExecutionService.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.*;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ExecutionModelPriceHttpTest {
    @Test void cancellationDuringPackageReviewSuppressesReadbackAndCommit() throws Exception {
        var executions=mock(SkillExecutionService.class);var positions=mock(cn.jia.agent.position.PositionSkillService.class);var products=mock(cn.jia.agent.skill.SkillPackagePublicationService.class);
        var mvc=MockMvcBuilders.standaloneSetup(new ExecutionSkillProductsController(executions,mock(InstalledDomainAuthority.class),mock(PrivateToolCallReceipts.class),positions,products)).build();
        var identity=new AgentRuntimeAuthentication.Scope("tenant","client","owner","agent","instance");var principal=mock(AgentRuntimeAuthentication.class);when(principal.isAuthenticated()).thenReturn(true);when(principal.getPrincipal()).thenReturn(identity);when(principal.getDetails()).thenReturn(new AgentRuntimeAuthenticationService.Proof(identity,"install","host",1,"digest",1,1));
        var actor=new RuntimeScope("tenant","client","owner","agent","instance");var active=new java.util.concurrent.atomic.AtomicBoolean(true);
        doAnswer(i->{if(!active.get())throw new Failure(Reason.TASK_CONFLICT);return i.<Function<SkillExecutionService.RunningSkills,Object>>getArgument(5).apply(new SkillExecutionService.RunningSkills(actor,"install","host",List.of()));}).when(executions).withRunningSkills(eq(actor),eq("e"),eq("r"),any(),anyString(),any());
        var scope=new PositionAuthority.Scope("tenant","client","owner");var grant=new PositionAuthority.Grant("appointment",1,2,"agent",1,null,true);when(positions.currentManagementGrant(scope,"agent")).thenReturn(grant);
        java.util.function.BiFunction<String,String,org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder> req=(route,data)->post("/internal/agent/executions/e/runs/r/business/"+route).principal(principal).contentType("application/json").content("{\"protocolVersion\":1,\"requestId\":\"req\",\"data\":{\"snapshotSha256\":\""+"a".repeat(64)+"\",\"fence\":{\"version\":\"1\",\"token\":\"fixture\"},\"payload\":"+data+"}}");
        when(products.readPackage(scope,grant,"ver","SKILL.md")).thenAnswer(i->{active.set(false);return Map.of("data","private-candidate-body");});
        var denied=mvc.perform(req.apply("skill-package-files/lookup","{\"productVersionId\":\"ver\",\"path\":\"SKILL.md\"}")).andExpect(status().isConflict()).andReturn();assertFalse(denied.getResponse().getContentAsString().contains("private-candidate-body"));
        active.set(true);when(products.prepare(eq(scope),eq(grant),any(),any())).thenAnswer(i->{active.set(false);return mock(cn.jia.agent.skill.SkillPackagePublicationService.Prepared.class);});
        mvc.perform(req.apply("skill-product-versions/commit","""
            {"productId":"p","productVersionId":"v","name":"name","description":"description","skillKey":"x","skillVersion":"1.0.0","priceWen":"0","approvedPermissionsManifest":"[]","expectedProductVersion":"0","files":[{"path":"SKILL.md","content":"example","encoding":"utf8"}]}
            """)).andExpect(status().isConflict());verify(products,never()).commitPrepared(any());
    }
    @Test void productReadsReusePublicationGrantAndDoNotExposeAnotherExecutorsCatalog() throws Exception {
        var executions=mock(SkillExecutionService.class);var authority=mock(InstalledDomainAuthority.class);var positions=mock(cn.jia.agent.position.PositionSkillService.class);var products=mock(cn.jia.agent.skill.SkillPackagePublicationService.class);
        var mvc=MockMvcBuilders.standaloneSetup(new ExecutionSkillProductsController(executions,authority,mock(PrivateToolCallReceipts.class),positions,products)).build();
        var identity=new AgentRuntimeAuthentication.Scope("tenant","client","owner","agent","instance");var principal=mock(AgentRuntimeAuthentication.class);when(principal.isAuthenticated()).thenReturn(true);when(principal.getPrincipal()).thenReturn(identity);when(principal.getDetails()).thenReturn(new AgentRuntimeAuthenticationService.Proof(identity,"install","host",1,"digest",1,1));
        var actor=new RuntimeScope("tenant","client","owner","agent","instance");var subject=new java.util.concurrent.atomic.AtomicReference<>(actor);
        doAnswer(i->i.<Function<SkillExecutionService.RunningSkills,Object>>getArgument(5).apply(new SkillExecutionService.RunningSkills(subject.get(),"install","host",List.of()))).when(executions).withRunningSkills(eq(actor),eq("e"),eq("r"),any(),anyString(),any());
        var scope=new PositionAuthority.Scope("tenant","client","owner");var grant=new PositionAuthority.Grant("appointment",1,2,"agent",1,null,true);when(positions.currentManagementGrant(scope,"agent")).thenReturn(grant);
        var req=post("/internal/agent/executions/e/runs/r/business/skill-products/lookup").principal(principal).contentType("application/json").content("{\"protocolVersion\":1,\"requestId\":\"req\",\"data\":{\"snapshotSha256\":\""+"a".repeat(64)+"\",\"fence\":{\"version\":\"1\",\"token\":\"fixture\"},\"payload\":{\"offset\":\"0\",\"limit\":\"10\"}}}");
        mvc.perform(req).andExpect(status().isOk());verify(products).listProducts(scope,grant,0,10);
        subject.set(new RuntimeScope("tenant","client","other","agent","instance","owner"));mvc.perform(req).andExpect(status().isForbidden());verifyNoMoreInteractions(products);
    }
    @Test void selectionIsIrrevocablyBoundToOneOfferedInstallationUnderTheExecutionLock() throws Exception {
        var executions=mock(SkillExecutionService.class);var mapper=mock(cn.jia.agent.mapper.PrivateToolCallReceiptMapper.class);
        var saved=new HashMap<cn.jia.agent.mapper.PrivateToolCallReceiptMapper.Key,cn.jia.agent.mapper.PrivateToolCallReceiptMapper.Receipt>();
        doAnswer(i->saved.get(i.getArgument(0,Object.class))).when(mapper).lock(any());
        doAnswer(i->{saved.put(i.getArgument(0),i.getArgument(1));return 1;}).when(mapper).insert(any(),any(),anyLong());
        var authority=mock(InstalledDomainAuthority.class);
        var mvc=MockMvcBuilders.standaloneSetup(new ExecutionSkillSelection(executions,new PrivateToolCallReceipts(mapper),authority)).build();
        var identity=new AgentRuntimeAuthentication.Scope("tenant","client","owner","agent","instance");var principal=mock(AgentRuntimeAuthentication.class);
        when(principal.isAuthenticated()).thenReturn(true);when(principal.getPrincipal()).thenReturn(identity);
        when(principal.getDetails()).thenReturn(new AgentRuntimeAuthenticationService.Proof(identity,"install","host",1,"digest",1,1));
        var actor=new RuntimeScope("tenant","client","owner","agent","instance");
        doAnswer(i->i.<Function<SkillExecutionService.RunningSkills,Object>>getArgument(5).apply(new SkillExecutionService.RunningSkills(actor,"install","host",List.of(Map.of("skill",Map.of("installationId","a")),Map.of("skill",Map.of("installationId","b"))))))
                .when(executions).withRunningSkills(eq(actor),eq("e"),eq("r"),any(),anyString(),any());
        java.util.function.Function<String,org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder> req=id->post("/internal/agent/executions/e/runs/r/skill-selection").principal(principal).contentType("application/json").content("{\"protocolVersion\":1,\"requestId\":\"req\",\"data\":{\"snapshotSha256\":\""+"a".repeat(64)+"\",\"fence\":{\"version\":\"1\",\"token\":\"test\"},\"installationId\":\""+id+"\"}}");
        mvc.perform(req.apply("unknown")).andExpect(status().isForbidden());mvc.perform(req.apply("b")).andExpect(status().isOk());
        mvc.perform(req.apply("b")).andExpect(status().isOk());mvc.perform(req.apply("a")).andExpect(status().isConflict());verify(mapper,times(1)).insert(any(),any(),anyLong());
        doThrow(new PositionSkillException(403,"SKILL_SELECTION_REVOKED")).when(authority).requireCurrentChoice(any(),anyString(),anyString(),anyString(),anyMap());
        mvc.perform(req.apply("b")).andExpect(status().isForbidden());
    }
    @Test void multipleCandidatesRequireTheDurableBindingAndTheTrustedIsolationHeader() {
        var receipts=mock(PrivateToolCallReceipts.class);
        var actor=new RuntimeScope("tenant","client","owner","agent","instance");
        var fence=new ConversationFence(1,"test");var digest="a".repeat(64);
        var a=Map.<String,Object>of("skill",Map.of("installationId","a"));var b=Map.<String,Object>of("skill",Map.of("installationId","b"));
        var candidates=List.of(a,b);var call=new RunningExecutionEndpoint.Call<>(actor,"exec","run",fence,digest,"b",null);
        assertThrows(PositionSkillException.class,()->ExecutionSkillSelection.bound(call,candidates,receipts));
        when(receipts.find(RunningExecutionEndpoint.receiptKey(call,"native-skill-selection"))).thenReturn(new cn.jia.agent.mapper.PrivateToolCallReceiptMapper.Receipt("native-skill-selection",digest,"{\"installationId\":\"b\"}"));
        assertEquals(List.of(b),ExecutionSkillSelection.bound(call,candidates,receipts));
        for(String wrong:new String[]{null,"a"})assertThrows(PositionSkillException.class,()->ExecutionSkillSelection.bound(new RunningExecutionEndpoint.Call<>(actor,"exec","run",fence,digest,wrong,null),candidates,receipts));
        assertThrows(PositionSkillException.class,()->ExecutionSkillSelection.bound(call,List.of(b,b),receipts));
    }
    @Test void lookupUsesFrozenGrantAndCannotReadAfterRevocationOrExpiredLease() throws Exception {
        var executions=mock(SkillExecutionService.class);var authority=mock(InstalledDomainAuthority.class);
        var prices=mock(ModelMaintenanceOperations.class);
        var mvc=MockMvcBuilders.standaloneSetup(new ExecutionModelPriceController(executions,authority,prices,mock(PrivateToolCallReceipts.class))).build();
        var identity=new AgentRuntimeAuthentication.Scope("tenant","client","executor","agent","instance");
        var principal=mock(AgentRuntimeAuthentication.class);
        when(principal.isAuthenticated()).thenReturn(true);when(principal.getPrincipal()).thenReturn(identity);
        when(principal.getDetails()).thenReturn(new AgentRuntimeAuthenticationService.Proof(identity,"install","host",1,"digest",1,1));
        var caller=new RuntimeScope("tenant","client","executor","agent","instance");
        var scope=new PositionAuthority.Scope("tenant","client","executor");
        var grant=new SkillExecutionService.RunningSkills(new RuntimeScope("tenant","client","task-owner","agent","instance","executor"),"install","host",List.of(Map.of("skill",Map.of("key","model-maintenance","installationId","si"))));
        String digest="a".repeat(64);var fence=new ConversationFence(1,"fixture");
        var admitted=doAnswer(i->i.<Function<SkillExecutionService.RunningSkills,Object>>getArgument(5).apply(grant));
        admitted.when(executions).withRunningSkills(eq(caller),eq("e"),eq("r"),eq(fence),eq(digest),any());
        when(prices.readPrice(scope,"fixture")).thenReturn(Map.of("configured",true,"model","fixture"));
        var request=post("/internal/agent/executions/e/runs/r/business/model-prices/lookup").principal(principal).contentType("application/json")
                .content("{\"protocolVersion\":1,\"requestId\":\"test\",\"data\":{\"model\":\"fixture\",\"snapshotSha256\":\""+digest+"\",\"fence\":{\"version\":\"1\",\"token\":\"fixture\"}}}");
        var response=mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse();
        assertTrue(response.getContentAsString().contains("\"configured\":true"));
        doThrow(new PositionSkillException(403,"MODEL_SKILL_REQUIRED")).when(authority).requireInstalled(scope,"agent","install","host",true,grant.selections(),ExecutionModelPriceController.REQUIRED);
        mvc.perform(request).andExpect(status().isForbidden());
        doThrow(new Failure(Reason.TASK_CONFLICT)).when(executions).withRunningSkills(eq(caller),eq("e"),eq("r"),eq(fence),eq(digest),any());
        mvc.perform(request).andExpect(status().isConflict());verify(prices,times(1)).readPrice(scope,"fixture");
    }
    @Test void commitAndReceiptUseTheSameIntentAndNeverReapplyUnknownWrites() throws Exception {
        var executions=mock(SkillExecutionService.class);var authority=mock(InstalledDomainAuthority.class);
        var operations=mock(ModelMaintenanceOperations.class);var receipts=mock(PrivateToolCallReceipts.class);
        var mvc=MockMvcBuilders.standaloneSetup(new ExecutionModelPriceController(executions,authority,operations,receipts)).build();
        var identity=new AgentRuntimeAuthentication.Scope("tenant","client","executor","agent","instance");
        var principal=mock(AgentRuntimeAuthentication.class);when(principal.isAuthenticated()).thenReturn(true);when(principal.getPrincipal()).thenReturn(identity);
        when(principal.getDetails()).thenReturn(new AgentRuntimeAuthenticationService.Proof(identity,"install","host",1,"digest",1,1));
        var actor=new RuntimeScope("tenant","client","executor","agent","instance");var cross=new RuntimeScope("tenant","client","other","agent","instance","executor");
        var subject=new java.util.concurrent.atomic.AtomicReference<>(actor);
        doAnswer(i->i.<Function<SkillExecutionService.RunningSkills,Object>>getArgument(5).apply(new SkillExecutionService.RunningSkills(subject.get(),"install","host",List.of())))
            .when(executions).withRunningSkills(eq(actor),eq("e"),eq("r"),any(),anyString(),any());
        var saved=new HashMap<cn.jia.agent.mapper.PrivateToolCallReceiptMapper.Key,cn.jia.agent.mapper.PrivateToolCallReceiptMapper.Receipt>();
        doAnswer(i->saved.get(i.getArgument(0,Object.class))).when(receipts).find(any());
        doAnswer(i->{var result=i.<java.util.function.Supplier<Map<String,Object>>>getArgument(3).get();
            saved.put(i.getArgument(0),new cn.jia.agent.mapper.PrivateToolCallReceiptMapper.Receipt(i.getArgument(1),i.getArgument(2),SkillCanonicalJson.write(result)));return result;})
            .when(receipts).apply(any(),anyString(),anyString(),any());
        when(operations.prepareAddPrice("fixture")).thenReturn(scope->Map.of("model","fixture","configured",true));
        var payload="{\"protocolVersion\":1,\"requestId\":\"test\",\"data\":{\"model\":\"fixture\",\"snapshotSha256\":\""+"a".repeat(64)+"\",\"fence\":{\"version\":\"1\",\"token\":\"fixture\"}}}";
        java.util.function.Function<String,org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder> request=suffix->post("/internal/agent/executions/e/runs/r/business/model-prices/"+suffix).principal(principal).contentType("application/json").content(payload);
        mvc.perform(request.apply("receipt")).andExpect(status().isNotFound());verifyNoInteractions(operations);
        subject.set(cross);mvc.perform(request.apply("commit")).andExpect(status().isForbidden());verifyNoInteractions(operations);subject.set(actor);
        mvc.perform(request.apply("commit")).andExpect(status().isOk());
        assertTrue(mvc.perform(request.apply("receipt")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString().contains("configured"));
        mvc.perform(request.apply("commit")).andExpect(status().isOk());verify(operations,times(1)).prepareAddPrice("fixture");verify(receipts,times(1)).apply(any(),anyString(),anyString(),any());
    }
    @Test void positionUpgradeRejectsBorrowedScopeAndRequiresCurrentManagementInstallationEvenForReceipt() throws Exception {
        var executions=mock(SkillExecutionService.class);var authority=mock(InstalledDomainAuthority.class);
        var positions=mock(cn.jia.agent.position.PositionSkillService.class);var receipts=mock(PrivateToolCallReceipts.class);
        var mvc=MockMvcBuilders.standaloneSetup(new ExecutionPositionUpgradeController(executions,authority,receipts,positions)).build();
        var identity=new AgentRuntimeAuthentication.Scope("tenant","client","executor","agent","instance");
        var principal=mock(AgentRuntimeAuthentication.class);when(principal.isAuthenticated()).thenReturn(true);when(principal.getPrincipal()).thenReturn(identity);
        when(principal.getDetails()).thenReturn(new AgentRuntimeAuthenticationService.Proof(identity,"install","host",1,"digest",1,1));
        var actor=new RuntimeScope("tenant","client","executor","agent","instance");var cross=new RuntimeScope("tenant","client","other","agent","instance","executor");
        var subject=new java.util.concurrent.atomic.AtomicReference<>(cross);
        doAnswer(i->i.<Function<SkillExecutionService.RunningSkills,Object>>getArgument(5).apply(new SkillExecutionService.RunningSkills(subject.get(),"install","host",List.of())))
                .when(executions).withRunningSkills(eq(actor),eq("e"),eq("r"),any(),anyString(),any());
        String payload="{\"protocolVersion\":1,\"requestId\":\"test\",\"data\":{\"skillKey\":\"fixture\",\"expectedProductVersionId\":\"old\",\"targetProductVersionId\":\"new\",\"snapshotSha256\":\""+"a".repeat(64)+"\",\"fence\":{\"version\":\"1\",\"token\":\"fixture\"}}}";
        java.util.function.Function<String,org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder> req=verb->post("/internal/agent/executions/e/runs/r/business/position-skill-versions/"+verb).principal(principal).contentType("application/json").content(payload);
        mvc.perform(req.apply("commit")).andExpect(status().isForbidden());verifyNoInteractions(positions,receipts);
        subject.set(actor);mvc.perform(req.apply("receipt")).andExpect(status().isNotFound());verifyNoInteractions(positions);
        var scope=new PositionAuthority.Scope("tenant","client","executor");
        doAnswer(i->i.<java.util.function.Supplier<?>>getArgument(3).get()).when(receipts).apply(any(),eq("position-skill-versions"),anyString(),any());
        when(positions.upgrade(eq(scope),anyString(),eq("fixture"),eq("old"),eq("new"))).thenReturn(new cn.jia.agent.position.PositionSkillService.View("op","fixture",2,null));
        var response=mvc.perform(req.apply("commit")).andExpect(status().isOk()).andReturn().getResponse();assertTrue(response.getContentAsString().contains("DEFINITION_UPDATED"));
        doThrow(new PositionSkillException(403,"MANAGEMENT_REVOKED")).when(authority).requireInstalled(eq(scope),eq("agent"),eq("install"),eq("host"),eq(true),anyList(),eq(ExecutionPositionUpgradeController.REQUIRED));
        mvc.perform(req.apply("receipt")).andExpect(status().isForbidden());verify(positions,times(1)).upgrade(eq(scope),anyString(),eq("fixture"),eq("old"),eq("new"));
    }
}
