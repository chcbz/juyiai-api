package cn.jia.agent.api;

import cn.jia.agent.position.PositionSkillException;
import cn.jia.agent.service.PositionAuthority.Scope;
import cn.jia.economy.pricing.CostRouteBindings;
import cn.jia.economy.pricing.CostRouteReference;
import cn.jia.economy.pricing.CostPricingRoute;
import cn.jia.economy.pricing.CostPricingRoute.*;
import cn.jia.economy.service.EconomyScope;
import java.util.*;
import org.springframework.stereotype.Service;
import static cn.jia.agent.position.PositionSkillException.require;

/** Invoked only under the original installed model-maintenance authority and receipt transaction. */
@Service
final class CostRouteMaintenanceOperations {
    private final CostRouteBindings bindings;
    CostRouteMaintenanceOperations(CostRouteBindings bindings){this.bindings=bindings;}
    record Change(CostRouteReference reference,long expectedRevision,CostPricingRoute route,Map<String,Object> intent){}
    static Change prepare(CostRouteReference reference,String revision,String action,Map<String,Object> value){
        require(reference!=null && revision!=null && revision.matches("0|[1-9][0-9]{0,18}"),400,"COST_ROUTE_INPUT_INVALID");
        long expected;
        try{expected=Long.parseLong(revision);}catch(NumberFormatException e){throw invalid();}
        require(expected<Long.MAX_VALUE,400,"COST_ROUTE_INPUT_INVALID");
        CostPricingRoute route;
        if("PUBLISH".equals(action))route=parse(value);
        else{require("WITHDRAW".equals(action)&&value==null,400,"COST_ROUTE_INPUT_INVALID");route=null;}
        if(route instanceof TokenRoute token)require(reference.normalizationBasis().equals(token.normalizationBasis()),400,"COST_ROUTE_BASIS_MISMATCH");
        var intent=new HashMap<String,Object>();intent.put("reference",reference.canonical());intent.put("expectedRevision",revision);intent.put("action",action);
        if(route!=null)intent.put("route",value(route));
        return new Change(reference,expected,route,Map.copyOf(intent));
    }
    Map<String,Object> read(Scope scope,CostRouteReference reference){
        require(reference!=null,400,"COST_ROUTE_INPUT_INVALID");
        try{return bindings.inspect(domain(scope),reference).map(CostRouteMaintenanceOperations::view)
                .orElseGet(()->Map.of("reference",reference.canonical(),"revision","0","state","MISSING"));}
        catch(CostRouteBindings.Corrupt e){throw new PositionSkillException(503,"COST_ROUTE_BINDING_CORRUPT");}
    }
    Map<String,Object> apply(Scope scope,Change change){
        try{
            if(change.route()!=null)return view(bindings.publish(domain(scope),change.reference(),change.expectedRevision(),change.route()));
            long revision=bindings.withdraw(domain(scope),change.reference(),change.expectedRevision());
            return Map.of("reference",change.reference().canonical(),"revision",Long.toString(revision),"state","WITHDRAWN");
        }catch(CostRouteBindings.Conflict e){throw new PositionSkillException(409,"COST_ROUTE_BINDING_CONFLICT");}
        catch(CostRouteBindings.Corrupt e){throw new PositionSkillException(503,"COST_ROUTE_BINDING_CORRUPT");}
    }
    private static EconomyScope domain(Scope scope){return new EconomyScope(scope.tenantId(),scope.clientId());}
    private static Map<String,Object> view(CostRouteBindings.Binding b){
        var view=new HashMap<String,Object>();view.put("reference",b.reference().canonical());view.put("revision",Long.toString(b.revision()));view.put("sha256",b.sha());
        view.put("state",b.route()==null?"WITHDRAWN":"ACTIVE");if(b.route()!=null)view.put("route",value(b.route()));return Map.copyOf(view);
    }
    private static CostPricingRoute parse(Map<String,Object> m){
        try{return CostPricingRoute.fromCanonical(m);}catch(IllegalArgumentException e){throw invalid();}
    }
    private static Map<String,Object> value(CostPricingRoute route){return route.canonical();}
    private static PositionSkillException invalid(){return new PositionSkillException(400,"COST_ROUTE_INPUT_INVALID");}
}
