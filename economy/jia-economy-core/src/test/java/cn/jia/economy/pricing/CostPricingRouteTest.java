package cn.jia.economy.pricing;

import cn.jia.core.util.CanonicalValueEncoding;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CostPricingRouteTest {
    @Test void allMechanismsRoundtripWithoutInventingTokenFields() {
        var token=new CostPricingRoute.TokenRoute("channel","model","tier","basis");
        var unit=new CostPricingRoute.UnitRoute(new TaskQuoteCalculator.ToolKey("channel","tool","page"));
        var zero=new CostPricingRoute.ZeroRoute("free-contract-1");
        for(var route:List.of(token,unit,zero))assertEquals(route,CostPricingRoute.fromCanonical(route.canonical()));
        assertEquals(Map.of("type","TOKEN","channel","channel","model","model","tier","tier","basis","basis"),token.canonical());
        assertEquals(Map.of("type","UNIT","channel","channel","tool","tool","unit","page"),unit.canonical());
        assertEquals(Map.of("type","ZERO","contractVersion","free-contract-1"),zero.canonical());
        assertFalse(unit.canonical().containsKey("tier"));assertFalse(zero.canonical().containsKey("channel"));
    }
    @Test void canonicalBusinessBytesPreserveStoredRouteAndReceiptIdentities() {
        var route=new CostPricingRoute.TokenRoute("channel","model","tier","basis");
        // Before relocation the DB and HTTP adapter independently produced these exact fields.
        var old=new LinkedHashMap<String,Object>();old.put("basis","basis");old.put("tier","tier");old.put("model","model");old.put("channel","channel");old.put("type","TOKEN");
        assertArrayEquals(CanonicalValueEncoding.encode(old),CanonicalValueEncoding.encode(route.canonical()));
        assertEquals(CanonicalValueEncoding.digest(old),CanonicalValueEncoding.digest(route.canonical()));
    }
    @Test void missingUnknownAndMixedMechanismsAreNeverInferredAsFree() {
        for(var value:List.<Map<String,Object>>of(Map.of(),Map.of("type","ZERO"),Map.of("type","OTHER"),
                Map.of("type","ZERO","contractVersion","free","tier","default"),
                Map.of("type","TOKEN","channel","c","model","m","basis","b"),
                Map.of("type","UNIT","channel","c","tool","t","unit",1)))
            assertThrows(IllegalArgumentException.class,()->CostPricingRoute.fromCanonical(value));
        assertThrows(IllegalArgumentException.class,()->CostPricingRoute.fromCanonical(null));
    }
    @Test void directConstructionCannotBypassIdentityBoundsOrUnicodeValidation() {
        for(String bad:Arrays.asList(null,""," "," trailing ","\ud800","x".repeat(101)))
            assertThrows(IllegalArgumentException.class,()->new CostPricingRoute.TokenRoute(bad,"m","t","b"));
        assertThrows(IllegalArgumentException.class,()->new CostPricingRoute.TokenRoute("c","x".repeat(201),"t","b"));
        assertThrows(IllegalArgumentException.class,()->new CostPricingRoute.ZeroRoute(""));
        assertThrows(IllegalArgumentException.class,()->new CostPricingRoute.UnitRoute(new TaskQuoteCalculator.ToolKey("c","tool","x".repeat(101))));
    }
}
