package cn.jia.agent.service.recommendation;

import cn.jia.agent.skill.quote.CostProfile;
import cn.jia.agent.skill.quote.ScopedCostInputs;
import cn.jia.agent.skill.quote.CapabilityQuoteSpec;
import cn.jia.economy.pricing.GroupMeanPricing;
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
    /** The complete requested candidate set is retained, including candidates without a measured
     * plan. Absence is missing cost evidence, not an authorization or capability verdict.
     */
    public static final class ScopedPrepared {
        private final ScopedCostInputs inputs;
        private final Set<String> candidates;
        private final Map<String,MeasuredCostProfile> selected;
        private final Prepared routes;
        private ScopedPrepared(ScopedCostInputs inputs,Set<String> candidates,Map<String,MeasuredCostProfile> selected,Prepared routes){
            this.inputs=inputs;this.candidates=Set.copyOf(candidates);this.selected=Map.copyOf(selected);this.routes=routes;
        }
        public Set<String> candidates(){return candidates;}
        public Optional<MeasuredCostProfile> selected(String candidate){
            if(!candidates.contains(candidate))throw new IllegalArgumentException("Candidate was not prepared");
            return Optional.ofNullable(selected.get(candidate));
        }
        /** The exact resolved plan adopted in this preparation, for the private pricing-profile pin.
         * Never re-resolve from a newer map or substitute measurementSha for configSha. */
        public Optional<CostProfile> configuration(String candidate){return selected(candidate).flatMap(routes::find);}
        public Optional<GroupMeanPricing.ScopeGroup> group(String candidate,String featureVersion,String policySha){
            return configuration(candidate).flatMap(profile->inputs.group(featureVersion,policySha,profile));
        }
        public CapabilityQuoteSpec.MatchStatus inputStatus(){return inputs.status();}
        @Override public String toString(){return "ScopedPrepared[private]";}
    }
    /** Select actual plans before one shared route lookup. Unselected plans neither incur reads nor
     * become finish dependencies; unavailable routing never selects a lower-priority alternative.
     * Caller still owns current installation/spec authorization and separate capability proof.
     */
    public ScopedPrepared prepareScoped(EconomyScope domain,ScopedCostInputs inputs,
            Map<String,List<MeasuredCostProfile>> authorizedProfiles){
        Objects.requireNonNull(inputs);Objects.requireNonNull(authorizedProfiles);
        var selected=new HashMap<String,MeasuredCostProfile>();
        authorizedProfiles.forEach((candidate,profiles)->inputs.select(profiles).ifPresent(profile->selected.put(candidate,profile)));
        var routes=prepare(domain,inputs.status()==CapabilityQuoteSpec.MatchStatus.MATCHED?selected.values():List.of());
        return new ScopedPrepared(inputs,authorizedProfiles.keySet(),selected,routes);
    }
    /** Other finish checks (scope, authority and published specifications) remain the caller's duty. */
    public void recheckScoped(EconomyScope domain,ScopedPrepared prepared){recheck(domain,prepared.routes);}

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
