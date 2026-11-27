package cn.jia.agent.skill.quote;

import cn.jia.core.util.CanonicalValueEncoding;
import cn.jia.economy.pricing.CostPricingRoute;
import cn.jia.economy.pricing.CostRouteReference;
import java.util.*;

/** Backend-resolved pricing configuration. Never accepted as a Runtime measurement or execution permission. */
public record CostProfile(MeasuredCostProfile measurement,List<Route> routes) {
    public CostProfile {
        Objects.requireNonNull(measurement);routes=List.copyOf(Objects.requireNonNull(routes));
        var byReference=new HashMap<CostRouteReference,Route>();
        for(var route:routes)if(byReference.put(route.reference(),route)!=null)throw new IllegalArgumentException("Duplicate resolved cost route");
        if(!byReference.keySet().equals(Set.copyOf(measurement.routeRefs())))throw new IllegalArgumentException("Incomplete resolved cost configuration");
        // Retain the measured reference order, not map iteration or the order returned by SQL.
        routes=measurement.routeRefs().stream().map(byReference::get).toList();
    }
    public record Route(CostRouteReference reference,CostPricingRoute pricing) {
        public Route {
            Objects.requireNonNull(reference);Objects.requireNonNull(pricing);
            if(pricing instanceof CostPricingRoute.TokenRoute t && !reference.normalizationBasis().equals(t.normalizationBasis()))
                throw new IllegalArgumentException("Cost route basis mismatch");
        }
        public Map<String,Object> canonical(){return Map.of("reference",reference.canonical(),"pricing",pricing.canonical());}
    }
    /** Excludes installation/profile identity and mutable mapping revision, but includes resolved mapping semantics. */
    public Map<String,Object> compatibleConfiguration() {
        return Map.of("measurement",measurement.compatibleMeasurement(),"routes",routes.stream().map(Route::canonical).toList());
    }
    public String configSemanticSha(){return CanonicalValueEncoding.digest(compatibleConfiguration());}
    /** Private identity binds the full measured configuration; mapping revision pins are separately held for finish. */
    public Map<String,Object> canonical(){return Map.of("measurement",measurement.canonical(),"routes",routes.stream().map(Route::canonical).toList());}
    public String configSha(){return CanonicalValueEncoding.digest(canonical());}
    @Override public String toString(){return "CostProfile[private]";}
}
