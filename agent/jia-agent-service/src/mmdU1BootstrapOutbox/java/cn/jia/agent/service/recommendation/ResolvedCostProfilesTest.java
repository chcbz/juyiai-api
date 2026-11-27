package cn.jia.agent.service.recommendation;

import cn.jia.agent.skill.quote.MeasuredCostProfile;
import cn.jia.economy.pricing.*;
import cn.jia.economy.service.EconomyScope;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ResolvedCostProfilesTest {
    private static final String H="a".repeat(64);
    private static final EconomyScope DOMAIN=new EconomyScope("tenant","client");
    private final CostRouteBindings bindings=mock(CostRouteBindings.class);
    private final ResolvedCostProfiles service=new ResolvedCostProfiles(bindings);
    private static CostRouteReference ref(String namespace){return new CostRouteReference("MODEL","INFERENCE",namespace,"same-model","TOKENS_V1");}
    private static MeasuredCostProfile profile(String install,List<CostRouteReference> refs){
        return new MeasuredCostProfile("profile",1,0,List.of(new MeasuredCostProfile.Slot(0,"text.summary","1",H,List.of(),"summary","1",H,install)),"native-1","context-1",H,H,"capture-1",refs);
    }
    private static CostRouteBindings.Binding binding(CostRouteReference ref,long revision,String channel){
        var route=new CostPricingRoute.TokenRoute(channel,"same-model","tier","TOKENS_V1");
        var sha=cn.jia.core.util.CanonicalValueEncoding.digest(Map.of("reference",ref.canonical(),"revision",Long.toString(revision),"route",route.canonical()));
        return new CostRouteBindings.Binding(ref,revision,sha,route);
    }
    private static List<CostRouteReference> ordered(CostRouteReference... refs){return Arrays.stream(refs).distinct().sorted(Comparator.comparing(CostRouteReference::sha)).toList();}

    @Test void sharedReferencesAreReadOnceAndDifferentNamespacesKeepDifferentPrices(){
        var a=ref("provider-a");var b=ref("provider-b");var pa=profile("a",List.of(a));var another=profile("other",List.of(a));var pb=profile("b",List.of(b));
        when(bindings.read(DOMAIN,ordered(a,b),false)).thenReturn(Map.of(a,binding(a,1,"channel-a"),b,binding(b,1,"channel-b")));
        var p=service.prepare(DOMAIN,List.of(pa,another,pa,pb));
        assertEquals(p.find(pa).orElseThrow().configSemanticSha(),p.find(another).orElseThrow().configSemanticSha());
        assertNotEquals(p.find(pa).orElseThrow().configSemanticSha(),p.find(pb).orElseThrow().configSemanticSha());
        assertEquals(2,p.adopted().size());verify(bindings).read(DOMAIN,ordered(a,b),false);verifyNoMoreInteractions(bindings);
    }
    @Test void completeProfilesOnlyAdoptTheirMappingsAndMissingDataDoesNotRevokeMeasurement(){
        var a=ref("a");var b=ref("b");var c=ref("c");var complete=profile("a",List.of(a));var partial=profile("b",List.of(b,c));
        when(bindings.read(DOMAIN,ordered(a,b,c),false)).thenReturn(Map.of(a,binding(a,1,"a"),b,binding(b,1,"b")));
        var p=service.prepare(DOMAIN,List.of(complete,partial));
        assertTrue(p.find(complete).isPresent());assertTrue(p.find(partial).isEmpty());
        assertEquals(Set.of(a),p.adopted().keySet());assertEquals(2,partial.routeRefs().size());
        when(bindings.read(DOMAIN,ordered(a),true)).thenReturn(p.adopted());
        service.recheck(DOMAIN,p);verify(bindings).read(DOMAIN,ordered(a),true);
        verify(bindings).read(DOMAIN,ordered(a,b,c),false);
        // Newly available c or withdrawal of unused b cannot change this prepared fallback.
        verifyNoMoreInteractions(bindings);
    }
    @Test void lockRecheckRejectsWithdrawalOrRevisionChangeEvenWhenRouteSemanticsStayEqual(){
        var ref=ref("provider");var measurement=profile("i",List.of(ref));var old=binding(ref,1,"channel");
        when(bindings.read(DOMAIN,ordered(ref),false)).thenReturn(Map.of(ref,old));var p=service.prepare(DOMAIN,List.of(measurement));
        when(bindings.read(DOMAIN,ordered(ref),true)).thenReturn(Map.of(ref,old));assertDoesNotThrow(()->service.recheck(DOMAIN,p));
        when(bindings.read(DOMAIN,ordered(ref),true)).thenReturn(Map.of(ref,binding(ref,2,"channel")));
        assertThrows(TaskQuoteIntentService.Stale.class,()->service.recheck(DOMAIN,p));
        when(bindings.read(DOMAIN,ordered(ref),true)).thenReturn(Map.of());assertThrows(TaskQuoteIntentService.Stale.class,()->service.recheck(DOMAIN,p));
    }
    @Test void corruptMappingIsNotDowngradedToMissingCostEvidence(){
        var a=ref("provider");var measurement=profile("i",List.of(a));
        when(bindings.read(DOMAIN,ordered(a),false)).thenThrow(new CostRouteBindings.Corrupt());
        assertThrows(CostRouteBindings.Corrupt.class,()->service.prepare(DOMAIN,List.of(measurement)));
        doReturn(Map.of(a,binding(a,1,"channel"))).when(bindings).read(DOMAIN,ordered(a),false);var p=service.prepare(DOMAIN,List.of(measurement));
        when(bindings.read(DOMAIN,ordered(a),true)).thenThrow(new CostRouteBindings.Corrupt());
        assertThrows(CostRouteBindings.Corrupt.class,()->service.recheck(DOMAIN,p));
    }
    @Test void missingMappingAndEmptyCandidateSetNeedNoFinishReadAndCannotCrossDomains(){
        var a=ref("provider");when(bindings.read(DOMAIN,ordered(a),false)).thenReturn(Map.of());
        var p=service.prepare(DOMAIN,List.of(profile("i",List.of(a))));service.recheck(DOMAIN,p);
        assertThrows(IllegalArgumentException.class,()->service.recheck(new EconomyScope("other","client"),p));
        assertThrows(UnsupportedOperationException.class,()->p.adopted().put(a,binding(a,1,"channel")));
        service.recheck(DOMAIN,service.prepare(DOMAIN,List.of()));
        verify(bindings).read(DOMAIN,ordered(a),false);verifyNoMoreInteractions(bindings);
    }
    @Test void candidateCountDoesNotBecomePerCandidateMappingCalls(){
        var profiles=new ArrayList<MeasuredCostProfile>();var mapping=new HashMap<CostRouteReference,CostRouteBindings.Binding>();
        for(int i=0;i<129;i++){var ref=ref("namespace-"+i);profiles.add(profile("i-"+i,List.of(ref)));mapping.put(ref,binding(ref,1,"channel-"+i));}
        var refs=mapping.keySet().stream().sorted(Comparator.comparing(CostRouteReference::sha)).toList();
        when(bindings.read(DOMAIN,refs,false)).thenReturn(mapping);var p=service.prepare(DOMAIN,profiles);
        assertEquals(129,p.adopted().size());assertTrue(profiles.stream().allMatch(x->p.find(x).isPresent()));
        verify(bindings).read(DOMAIN,refs,false);verifyNoMoreInteractions(bindings);
        // Economy owns bounded 128-key SQL paging; this is a port batching assertion, not SQL performance evidence.
    }
}
