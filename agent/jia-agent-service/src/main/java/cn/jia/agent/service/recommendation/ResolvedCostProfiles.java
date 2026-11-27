package cn.jia.agent.service.recommendation;

import cn.jia.agent.skill.quote.CostProfile;
import cn.jia.agent.skill.quote.MeasuredCostProfile;
import cn.jia.economy.pricing.CostRouteBindings;
import cn.jia.economy.pricing.CostRouteReference;
import cn.jia.economy.service.EconomyScope;
import java.util.*;
import org.springframework.stereotype.Service;

/** Resolves already authorized measurements in one domain batch. Does not acquire execution authority or contact Runtime. */
@Service
public final class ResolvedCostProfiles {
    private final CostRouteBindings bindings;
    public ResolvedCostProfiles(CostRouteBindings bindings){this.bindings=bindings;}

    public static final class Prepared {
        private final EconomyScope domain;
        private final Map<MeasuredCostProfile,CostProfile> profiles;
        private final Map<CostRouteReference,CostRouteBindings.Binding> adopted;
        private Prepared(EconomyScope domain,Map<MeasuredCostProfile,CostProfile> profiles,Map<CostRouteReference,CostRouteBindings.Binding> adopted){
            this.domain=domain;this.profiles=Map.copyOf(profiles);this.adopted=Map.copyOf(adopted);
        }
        /** Missing entry means cost routing is incomplete, not that the candidate cannot execute. */
        public Optional<CostProfile> find(MeasuredCostProfile measurement){return Optional.ofNullable(profiles.get(measurement));}
        public Map<CostRouteReference,CostRouteBindings.Binding> adopted(){return adopted;}
        @Override public String toString(){return "ResolvedCostProfiles[private]";}
    }
    public Prepared prepare(EconomyScope domain,Collection<MeasuredCostProfile> measurements){
        Objects.requireNonNull(domain);
        var unique=List.copyOf(new LinkedHashSet<>(measurements));
        var references=unique.stream().flatMap(p->p.routeRefs().stream()).distinct().sorted(Comparator.comparing(CostRouteReference::sha)).toList();
        var read=references.isEmpty()?Map.<CostRouteReference,CostRouteBindings.Binding>of():bindings.read(domain,references,false);
        // The Economy port owns stored identity/digest validation; corruption propagates, never becomes absence.
        var resolved=new HashMap<MeasuredCostProfile,CostProfile>();var adopted=new HashMap<CostRouteReference,CostRouteBindings.Binding>();
        for(var measurement:unique){
            if(!read.keySet().containsAll(measurement.routeRefs()))continue;
            var routes=measurement.routeRefs().stream().map(ref->new CostProfile.Route(ref,read.get(ref).route())).toList();
            resolved.put(measurement,new CostProfile(measurement,routes));
            for(var ref:measurement.routeRefs())adopted.put(ref,read.get(ref));
        }
        return new Prepared(domain,resolved,adopted);
    }
    /** Called inside the original quote-finish transaction. Newly available mappings for fallback candidates are irrelevant. */
    public void recheck(EconomyScope domain,Prepared prepared){
        if(!prepared.domain.equals(domain))throw new IllegalArgumentException("Cost route domain changed");
        var references=prepared.adopted.keySet().stream().sorted(Comparator.comparing(CostRouteReference::sha)).toList();
        if(references.isEmpty())return;
        var current=bindings.read(domain,references,true);
        if(!current.equals(prepared.adopted))throw new TaskQuoteIntentService.Stale();
    }
}
