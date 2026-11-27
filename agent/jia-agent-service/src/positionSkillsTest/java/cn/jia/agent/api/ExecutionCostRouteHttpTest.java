package cn.jia.agent.api;

import cn.jia.agent.mapper.PrivateToolCallReceiptMapper;
import cn.jia.agent.position.PositionSkillException;
import cn.jia.agent.security.*;
import cn.jia.agent.service.*;
import cn.jia.agent.service.PersonalWorkspaceExecutionService.*;
import cn.jia.economy.pricing.*;
import cn.jia.economy.pricing.TaskReferencePricing.*;
import cn.jia.economy.service.EconomyScope;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ExecutionCostRouteHttpTest {
    private final SkillExecutionService runs=mock(SkillExecutionService.class);
    private final InstalledDomainAuthority authority=mock(InstalledDomainAuthority.class);
    private final PrivateToolCallReceipts receipts=mock(PrivateToolCallReceipts.class);
    private final CostRouteBindings store=mock(CostRouteBindings.class);
    private final AgentRuntimeAuthentication principal=mock(AgentRuntimeAuthentication.class);
    private final RuntimeScope actor=new RuntimeScope("tenant","client","owner","agent","instance");
    private final AtomicReference<RuntimeScope> subject=new AtomicReference<>(actor);
    private final Map<PrivateToolCallReceiptMapper.Key,PrivateToolCallReceiptMapper.Receipt> saved=new HashMap<>();
    private MockMvc mvc;
    private static final CostRouteReference REF=new CostRouteReference("MODEL_RESPONSE","INFERENCE","namespace","model","TOKENS_V1");
    private static final EconomyScope DOMAIN=new EconomyScope("tenant","client");
    private static final Map<String,Object> TARGET=Map.of("type","TOKEN","channel","channel","model","model","tier","tier","basis","TOKENS_V1");
    @BeforeEach void setup(){
        var id=new AgentRuntimeAuthentication.Scope("tenant","client","owner","agent","instance");
        when(principal.isAuthenticated()).thenReturn(true);when(principal.getPrincipal()).thenReturn(id);
        when(principal.getDetails()).thenReturn(new AgentRuntimeAuthenticationService.Proof(id,"install","host",1,"digest",1,1));
        doAnswer(i->i.<Function<SkillExecutionService.RunningSkills,Object>>getArgument(5).apply(new SkillExecutionService.RunningSkills(subject.get(),"install","host",List.of())))
                .when(runs).withRunningSkills(eq(actor),eq("e"),eq("r"),any(),anyString(),any());
        when(receipts.find(any())).thenAnswer(i->saved.get(i.getArgument(0)));
        when(receipts.apply(any(),anyString(),anyString(),any())).thenAnswer(i->{
            var result=i.<java.util.function.Supplier<Map<String,Object>>>getArgument(3).get();
            saved.put(i.getArgument(0),new PrivateToolCallReceiptMapper.Receipt(i.getArgument(1),i.getArgument(2),SkillCanonicalJson.write(result)));return result;
        });
        when(store.publish(eq(DOMAIN),eq(REF),anyLong(),any())).thenAnswer(i->new CostRouteBindings.Binding(REF,1,"b".repeat(64),i.getArgument(3)));
        mvc=MockMvcBuilders.standaloneSetup(new ExecutionCostRouteController(runs,authority,receipts,new CostRouteMaintenanceOperations(store))).build();
    }
    @SuppressWarnings("unchecked")
    private Map<String,Object> publish(){
        try(var stream=getClass().getResourceAsStream("/cost-route-maintenance.json")){
            assertNotNull(stream);var value=new tools.jackson.databind.ObjectMapper().readValue(stream,Map.class);
            assertEquals(REF.canonical(),value.get("reference"));assertEquals(TARGET,value.get("route"));return new HashMap<>(value);
        }catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}
    }
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(String verb,Map<String,Object> fields){
        var data=new HashMap<>(fields);data.put("snapshotSha256","a".repeat(64));data.put("fence",Map.of("version","1","token","fixture"));
        return post("/internal/agent/executions/e/runs/r/business/cost-route-bindings/"+verb).principal(principal).contentType("application/json")
                .content(SkillCanonicalJson.write(Map.of("protocolVersion",1,"requestId","request","data",data)));
    }
    @Test void commitReceiptAndReplayUseCompleteCanonicalIntentWithoutSecondMutation()throws Exception{
        mvc.perform(request("receipt",publish())).andExpect(status().isNotFound());verifyNoInteractions(store);
        mvc.perform(request("commit",publish())).andExpect(status().isOk());
        mvc.perform(request("receipt",publish())).andExpect(status().isOk());
        mvc.perform(request("commit",publish())).andExpect(status().isOk());
        verify(store,times(1)).publish(eq(DOMAIN),eq(REF),eq(0L),any());
        var changed=publish();var route=new HashMap<>(TARGET);route.put("channel","other");changed.put("route",route);
        mvc.perform(request("receipt",changed)).andExpect(status().isNotFound());
        verifyNoMoreInteractions(store);
    }
    @Test void crossOwnerMutationAndRevokedAuthorityCannotWriteOrReplay()throws Exception{
        subject.set(new RuntimeScope("tenant","client","other","agent","instance","owner"));
        mvc.perform(request("commit",publish())).andExpect(status().isForbidden());verifyNoInteractions(store);subject.set(actor);
        mvc.perform(request("commit",publish())).andExpect(status().isOk());
        doThrow(new PositionSkillException(403,"POSITION_PUBLISH_FORBIDDEN")).when(authority).requireInstalled(any(),eq("agent"),eq("install"),eq("host"),eq(true),anyList(),eq(ExecutionModelPriceController.REQUIRED));
        mvc.perform(request("receipt",publish())).andExpect(status().isForbidden());
        mvc.perform(request("lookup",Map.of("reference",REF.canonical()))).andExpect(status().isForbidden());
        verify(store,times(1)).publish(eq(DOMAIN),eq(REF),eq(0L),any());verifyNoMoreInteractions(store);
    }
    @Test void lookupReturnsWithdrawnRevisionAndWithdrawalUsesSameWriteReceiptPath()throws Exception{
        when(store.inspect(DOMAIN,REF)).thenReturn(Optional.of(new CostRouteBindings.Binding(REF,3,"c".repeat(64),null)));
        var body=mvc.perform(request("lookup",Map.of("reference",REF.canonical()))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(body.contains("WITHDRAWN"));assertTrue(body.contains("\"revision\":\"3\""));
        when(store.withdraw(DOMAIN,REF,3)).thenReturn(4L);
        var change=Map.<String,Object>of("reference",REF.canonical(),"expectedRevision","3","action","WITHDRAW");
        mvc.perform(request("commit",change)).andExpect(status().isOk());mvc.perform(request("receipt",change)).andExpect(status().isOk());
        verify(store,times(1)).withdraw(DOMAIN,REF,3);verify(store,never()).publish(any(),any(),anyLong(),any());
    }
    @Test void conflictCorruptionAndBadRouteAreNotPersistedAsSuccessfulReceipts()throws Exception{
        var invalid=publish();invalid.put("route",Map.of("type","ZERO"));
        mvc.perform(request("commit",invalid)).andExpect(status().isBadRequest());verifyNoInteractions(store);
        when(store.publish(eq(DOMAIN),eq(REF),eq(0L),any())).thenThrow(new CostRouteBindings.Conflict());
        mvc.perform(request("commit",publish())).andExpect(status().isConflict());assertTrue(saved.isEmpty());
        when(store.inspect(DOMAIN,REF)).thenThrow(new CostRouteBindings.Corrupt());
        mvc.perform(request("lookup",Map.of("reference",REF.canonical()))).andExpect(status().isServiceUnavailable());
    }
}
