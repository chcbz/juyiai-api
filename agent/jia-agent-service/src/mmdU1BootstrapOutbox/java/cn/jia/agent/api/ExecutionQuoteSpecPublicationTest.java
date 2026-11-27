package cn.jia.agent.api;

import cn.jia.agent.mapper.PrivateToolCallReceiptMapper.*;
import cn.jia.agent.position.*;
import cn.jia.agent.security.*;
import cn.jia.agent.service.*;
import cn.jia.agent.service.PersonalWorkspaceExecutionService.*;
import cn.jia.agent.skill.*;
import cn.jia.core.entity.JsonRequest;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.function.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Exercises the real native producer, operation intent digest and receipt-only path. */
class ExecutionQuoteSpecPublicationTest {
    private final SkillExecutionService executions=mock(SkillExecutionService.class);
    private final PositionSkillService positions=mock(PositionSkillService.class);
    private final SkillPackagePublicationService products=mock(SkillPackagePublicationService.class);
    private final PrivateToolCallReceipts receipts=mock(PrivateToolCallReceipts.class);
    private final AgentRuntimeAuthentication auth=mock(AgentRuntimeAuthentication.class);
    private final Map<Key,Receipt> saved=new HashMap<>();
    private final ExecutionSkillProductsController controller=new ExecutionSkillProductsController(executions,
            mock(InstalledDomainAuthority.class),receipts,positions,products);
    private final PositionAuthority.Scope scope=new PositionAuthority.Scope("tenant","client","owner");
    private final PositionAuthority.Grant grant=new PositionAuthority.Grant("appointment",1,2,"agent",1,null,true);
    @BeforeEach void setup() {
        var identity=new AgentRuntimeAuthentication.Scope("tenant","client","owner","agent","instance");
        when(auth.isAuthenticated()).thenReturn(true);when(auth.getPrincipal()).thenReturn(identity);
        when(auth.getDetails()).thenReturn(new AgentRuntimeAuthenticationService.Proof(identity,"install","host",1,"digest",1,1));
        var actor=new RuntimeScope("tenant","client","owner","agent","instance");
        doAnswer(i->i.<Function<SkillExecutionService.RunningSkills,Object>>getArgument(5).apply(
                new SkillExecutionService.RunningSkills(actor,"install","host",List.of())))
                .when(executions).withRunningSkills(eq(actor),eq("e"),eq("r"),any(),anyString(),any());
        when(positions.currentManagementGrant(scope,"agent")).thenReturn(grant);
        when(receipts.find(any())).thenAnswer(i->saved.get(i.getArgument(0)));
        when(receipts.apply(any(),anyString(),anyString(),any())).thenAnswer(i->{
            var value=i.<Supplier<Map<String,Object>>>getArgument(3).get();
            saved.put(i.getArgument(0),new Receipt(i.getArgument(1),i.getArgument(2),SkillCanonicalJson.write(value)));return value;
        });
        var prepared=mock(SkillPackagePublicationService.Prepared.class);
        when(prepared.matches(scope,grant)).thenReturn(true);when(products.prepare(eq(scope),eq(grant),any(),any())).thenReturn(prepared);
        when(products.commitPrepared(prepared)).thenReturn(new SkillPackagePublicationService.Receipt("p","v",2,"a".repeat(64),null,"PUBLISHED",null));
    }
    private static Map<String,Object> spec(String bound) {
        return Map.of("schema","CAPABILITY_QUOTE_SPEC_V1","capabilityId","input.count","version","1","fields",Map.of(),
                "costDimensions",List.of(Map.of("key","count","source","INPUT_COUNT","type","INTEGER","unit","unit/items")),
                "buckets",List.of(Map.of("id","small","ranges",Map.of("count",Map.of("lo","0","hi",bound)))));
    }
    private static JsonRequest<ExecutionSkillProductsController.Input> request(Object specs) {
        var p=new HashMap<String,Object>();p.put("productId","p");p.put("productVersionId","v");p.put("name","name");
        p.put("description","description");p.put("skillKey","x");p.put("skillVersion","1.0.0");p.put("priceWen","0");
        p.put("approvedPermissionsManifest","[]");p.put("expectedProductVersion","0");
        p.put("files",List.of(Map.of("path","SKILL.md","content","example","encoding","utf8")));p.put("quoteSpecs",specs);
        return new JsonRequest<>(1,"req",new ExecutionSkillProductsController.Input(p,"a".repeat(64),new RunningExecutionEndpoint.Fence("1","fixture")));
    }
    @Test void nativeCommandForwardsDeclarationAndReceiptIdentityIncludesEveryDeclaredSemanticChange() {
        var req=request(List.of(spec("10")));assertEquals(200,controller.publishCommit("e","r",req,auth).getStatusCode().value());
        verify(products).prepare(eq(scope),eq(grant),argThat(c->c.quoteSpecs().size()==1 && c.quoteSpecs().getFirst().capabilityId().equals("input.count")),any());
        assertEquals(1,saved.size());
        assertEquals(200,controller.publishReceipt("e","r",req,auth).getStatusCode().value());
        assertEquals(200,controller.publishCommit("e","r",req,auth).getStatusCode().value());
        verify(products,times(1)).commitPrepared(any());
        var changed=request(List.of(spec("11")));
        assertEquals(404,assertThrows(PositionSkillException.class,()->controller.publishReceipt("e","r",changed,auth)).status());
        verify(products,times(1)).commitPrepared(any());
        controller.publishCommit("e","r",changed,auth);assertEquals(2,saved.size());
        assertEquals(2,saved.values().stream().map(Receipt::inputSha256).distinct().count());
        verify(products,times(2)).commitPrepared(any());
    }
    @Test void invalidOrDuplicateSpecificationsDoNotPrepareOrMutatePackages() {
        for(Object value:List.of("not-an-array",List.of(spec("10"),spec("10")),List.of(Map.of("schema","unknown"))))
            assertEquals(400,assertThrows(PositionSkillException.class,()->controller.publishCommit("e","r",request(value),auth)).status());
        verifyNoInteractions(products);assertTrue(saved.isEmpty());
    }
}
