package cn.jia.agent.skill.quote;

import cn.jia.economy.pricing.CostPricingRoute;
import cn.jia.economy.pricing.CostRouteReference;
import cn.jia.economy.pricing.TaskQuoteCalculator;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CostProfileTest {
    private static final String H="a".repeat(64);
    private static final CostRouteReference TOKEN=new CostRouteReference("MODEL","INFERENCE","provider-one","model","TOKENS_V1");
    private static final CostRouteReference UNIT=new CostRouteReference("TOOL","RENDER","provider-two","render","UNITS_V1");
    private static final CostRouteReference FREE=new CostRouteReference("LOCAL","COMPUTE","local","compute","LOCAL_V1");
    private static MeasuredCostProfile measurement(String install,List<CostRouteReference> refs){
        return new MeasuredCostProfile("profile",1,0,List.of(new MeasuredCostProfile.Slot(0,"text.summary","1",H,List.of(),"summary","1",H,install)),"native-1","context-1",H,H,"capture-1",refs);
    }
    private static CostProfile.Route token(String channel){return new CostProfile.Route(TOKEN,new CostPricingRoute.TokenRoute(channel,"model","standard","TOKENS_V1"));}
    @Test void resolvesMixedMechanismsWithoutFakeTokenFieldsAndSortsByMeasuredReference(){
        var measured=measurement("install",List.of(TOKEN,UNIT,FREE));
        var routes=List.of(token("channel"),new CostProfile.Route(UNIT,new CostPricingRoute.UnitRoute(new TaskQuoteCalculator.ToolKey("tool-channel","render","item"))),new CostProfile.Route(FREE,new CostPricingRoute.ZeroRoute("local-free-v1")));
        var profile=new CostProfile(measured,routes);
        var reversed=new ArrayList<>(routes);Collections.reverse(reversed);
        assertEquals(profile,new CostProfile(measured,reversed));
        assertEquals(measured.routeRefs(),profile.routes().stream().map(CostProfile.Route::reference).toList());
        assertEquals("CostProfile[private]",profile.toString());
    }
    @Test void mappingChangesResolvedIdentityWithoutInventingRuntimeMeasurementChange(){
        var measured=measurement("install",List.of(TOKEN));
        var a=new CostProfile(measured,List.of(token("a")));var b=new CostProfile(measured,List.of(token("b")));
        assertEquals(a.measurement().measurementSha(),b.measurement().measurementSha());
        assertNotEquals(a.configSha(),b.configSha());assertNotEquals(a.configSemanticSha(),b.configSemanticSha());
    }
    @Test void installationChangesPrivateIdentityButNotSharedResolvedSemantics(){
        var a=new CostProfile(measurement("first",List.of(TOKEN)),List.of(token("channel")));
        var b=new CostProfile(measurement("second",List.of(TOKEN)),List.of(token("channel")));
        assertNotEquals(a.configSha(),b.configSha());assertEquals(a.configSemanticSha(),b.configSemanticSha());
    }
    @Test void missingExtraDuplicateOrIncompatibleMappingsCannotFormCompleteCostProfile(){
        var measured=measurement("install",List.of(TOKEN,UNIT));
        assertThrows(IllegalArgumentException.class,()->new CostProfile(measured,List.of(token("channel"))));
        assertThrows(IllegalArgumentException.class,()->new CostProfile(measurement("i",List.of(TOKEN)),List.of(token("c"),token("c"))));
        assertThrows(IllegalArgumentException.class,()->new CostProfile(measurement("i",List.of(UNIT)),List.of(token("c"))));
        assertThrows(IllegalArgumentException.class,()->new CostProfile.Route(TOKEN,new CostPricingRoute.TokenRoute("c","model","tier","OTHER_BASIS")));
    }
}
