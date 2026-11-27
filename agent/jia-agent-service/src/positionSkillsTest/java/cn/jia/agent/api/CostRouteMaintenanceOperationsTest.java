package cn.jia.agent.api;

import cn.jia.agent.position.PositionSkillException;
import cn.jia.agent.service.PositionAuthority.Scope;
import cn.jia.economy.pricing.*;
import cn.jia.economy.pricing.TaskReferencePricing.*;
import cn.jia.economy.service.EconomyScope;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CostRouteMaintenanceOperationsTest {
    private static final CostRouteReference REF=new CostRouteReference("MODEL_RESPONSE","INFERENCE","namespace","model","TOKENS_V1");
    private static final Map<String,Object> TOKEN=Map.of("type","TOKEN","channel","channel","model","model","tier","tier","basis","TOKENS_V1");
    private final CostRouteBindings store=mock(CostRouteBindings.class);
    private final CostRouteMaintenanceOperations operations=new CostRouteMaintenanceOperations(store);
    private final Scope scope=new Scope("tenant","client","owner");
    private final EconomyScope domain=new EconomyScope("tenant","client");
    @Test void publishUsesDomainNotOwnerAndKeepsExactChannelTier(){
        var change=CostRouteMaintenanceOperations.prepare(REF,"0","PUBLISH",TOKEN);
        when(store.publish(domain,REF,0,change.route())).thenReturn(new CostRouteBindings.Binding(REF,1,"a".repeat(64),change.route()));
        assertEquals("ACTIVE",operations.apply(scope,change).get("state"));
        assertEquals("ACTIVE",operations.apply(new Scope("tenant","client","other-owner"),change).get("state"));
        verify(store,times(2)).publish(domain,REF,0,new TokenRoute("channel","model","tier","TOKENS_V1"));
    }
    @Test void lookupExposesMissingAndWithdrawnRevisionsWithoutCallingThemFree(){
        when(store.inspect(domain,REF)).thenReturn(Optional.empty());
        assertEquals(Map.of("reference",REF.canonical(),"revision","0","state","MISSING"),operations.read(scope,REF));
        when(store.inspect(domain,REF)).thenReturn(Optional.of(new CostRouteBindings.Binding(REF,3,"b".repeat(64),null)));
        var result=operations.read(scope,REF);assertEquals("WITHDRAWN",result.get("state"));assertEquals("3",result.get("revision"));assertFalse(result.containsKey("route"));
    }
    @Test void explicitUnitAndZeroHaveDifferentCanonicalIntentsAndNoDefaults(){
        var unit=CostRouteMaintenanceOperations.prepare(REF,"0","PUBLISH",Map.of("type","UNIT","channel","tools","tool","processor","unit","seconds"));
        assertInstanceOf(UnitRoute.class,unit.route());
        var zero=CostRouteMaintenanceOperations.prepare(REF,"0","PUBLISH",Map.of("type","ZERO","contractVersion","approved-free-1"));
        assertEquals(new ZeroRoute("approved-free-1"),zero.route());assertNotEquals(unit.intent(),zero.intent());
        assertThrows(PositionSkillException.class,()->CostRouteMaintenanceOperations.prepare(REF,"0","PUBLISH",Map.of("type","ZERO")));
        var extra=new HashMap<>(TOKEN);extra.put("owner","injected");
        assertThrows(PositionSkillException.class,()->CostRouteMaintenanceOperations.prepare(REF,"0","PUBLISH",extra));
        assertThrows(PositionSkillException.class,()->CostRouteMaintenanceOperations.prepare(REF,"0","PUBLISH",null));
    }
    @Test void withdrawalRequiresExpectedRevisionAndDoesNotPublishZero(){
        var change=CostRouteMaintenanceOperations.prepare(REF,"3","WITHDRAW",null);
        when(store.withdraw(domain,REF,3)).thenReturn(4L);
        assertEquals("4",operations.apply(scope,change).get("revision"));verify(store).withdraw(domain,REF,3);
        assertThrows(PositionSkillException.class,()->CostRouteMaintenanceOperations.prepare(REF,"03","WITHDRAW",null));
        assertThrows(PositionSkillException.class,()->CostRouteMaintenanceOperations.prepare(REF,"3","WITHDRAW",TOKEN));
        assertThrows(PositionSkillException.class,()->CostRouteMaintenanceOperations.prepare(REF,"9223372036854775808","WITHDRAW",null));
    }
    @Test void conflictAndCorruptionAreNotSuccessfulFallbacks(){
        var change=CostRouteMaintenanceOperations.prepare(REF,"0","PUBLISH",TOKEN);
        when(store.publish(domain,REF,0,change.route())).thenThrow(new CostRouteBindings.Conflict());
        assertEquals(409,assertThrows(PositionSkillException.class,()->operations.apply(scope,change)).status());
        when(store.inspect(domain,REF)).thenThrow(new CostRouteBindings.Corrupt());
        assertEquals(503,assertThrows(PositionSkillException.class,()->operations.read(scope,REF)).status());
        var wrong=new HashMap<>(TOKEN);wrong.put("basis","OTHER");
        assertEquals(400,assertThrows(PositionSkillException.class,()->CostRouteMaintenanceOperations.prepare(REF,"0","PUBLISH",wrong)).status());
    }
}
