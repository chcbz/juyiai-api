package cn.jia.economy.pricing;

import cn.jia.economy.service.EconomyScope;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Internal Economy port. Maintenance caller owns the original authorization transaction. */
public interface CostRouteBindings {
    record Binding(CostRouteReference reference,long revision,String sha,TaskReferencePricing.Route route) {
        @Override public String toString(){return "CostRouteBinding[private]";}
    }
    /** Missing/withdrawn references are absent. Corrupt stored evidence is not absence. */
    Map<CostRouteReference,Binding> read(EconomyScope scope,List<CostRouteReference> references,boolean lock);
    /** Authorized maintenance lookup includes tombstones so a subsequent CAS can use their revision. */
    Optional<Binding> inspect(EconomyScope scope,CostRouteReference reference);
    Binding publish(EconomyScope scope,CostRouteReference reference,long expectedRevision,TaskReferencePricing.Route route);
    /** Returns the tombstone revision. Never deletes a referenced historical mapping. */
    long withdraw(EconomyScope scope,CostRouteReference reference,long expectedRevision);
    final class Corrupt extends IllegalStateException {public Corrupt(){super("COST_ROUTE_BINDING_CORRUPT");}}
    final class Conflict extends RuntimeException {public Conflict(){super("COST_ROUTE_BINDING_CONFLICT");}}
}
