package cn.jia.agent.service.recommendation;

import cn.jia.agent.mapper.skill.CapabilityQuoteSpecMapper.SpecKey;
import cn.jia.agent.security.CostProfileReportJson;
import cn.jia.agent.security.RegisteredPricingProfiles;
import cn.jia.agent.security.RegisteredPricingProfiles.CapabilityObserved;
import cn.jia.agent.security.RegisteredPricingProfiles.Observed;
import cn.jia.agent.skill.PublishedCapabilityQuoteSpecs;
import cn.jia.agent.skill.quote.*;
import cn.jia.agent.requirement.ConfirmedScope;
import cn.jia.economy.pricing.*;
import cn.jia.economy.task.ScopedTaskQuoteSnapshot.PricingProfilePin;
import cn.jia.economy.task.TaskQuoteBatches;
import java.util.*;
import org.springframework.stereotype.Service;

/** Ordinary confirmed-scope quote inputs. Reads authoritative local facts, never experiment plans,
 * text inference, provider APIs or Runtime. The original intent transaction still owns task authority. */
@Service
final class ScopedGroupQuoteInputs {
    private final RegisteredPricingProfiles profiles;
    private final PublishedCapabilityQuoteSpecs specifications;
    private final ResolvedCostProfiles routes;
    private final ScopedGroupPolicies policies;
    ScopedGroupQuoteInputs(RegisteredPricingProfiles profiles,PublishedCapabilityQuoteSpecs specifications,
            ResolvedCostProfiles routes,ScopedGroupPolicies policies){
        this.profiles=profiles;this.specifications=specifications;this.routes=routes;this.policies=policies;
    }
    record Read(TaskQuoteIntentService.Fresh fresh,Map<String,CandidateGroupOffers.Basis> bases,
                Map<String,CapabilityObserved> capabilities,Map<String,Observed> adoptedReports,
                Map<SpecKey,String> specShas,ResolvedCostProfiles.ScopedPrepared resolved,
                Map<GroupMeanPricing.ScopeInput,ScopedGroupPolicies.Selected> selectedPolicies) {
        Read {bases=Map.copyOf(bases);capabilities=Map.copyOf(capabilities);adoptedReports=Map.copyOf(adoptedReports);
            specShas=Map.copyOf(specShas);selectedPolicies=Map.copyOf(selectedPolicies);}
        @Override public String toString(){return "ScopedQuoteInputs[private]";}
    }
    private static List<SpecKey> keys(ConfirmedScope scope){return scope.workItems().stream()
            .map(i->new SpecKey(i.capabilityId(),i.capabilityVersion())).distinct().toList();}
    private static Map<SpecKey,String> hashes(Map<SpecKey,CapabilityQuoteSpec> specs){
        var result=new HashMap<SpecKey,String>();specs.forEach((key,value)->result.put(key,value.specSha()));return Map.copyOf(result);
    }
    /** Only relevant capability facts affect this quote; enabling an unrelated skill is not drift. */
    private static Map<String,CapabilityObserved> relevant(Map<String,CapabilityObserved> raw,Map<SpecKey,String> specs){
        var result=new HashMap<String,CapabilityObserved>();
        raw.forEach((agent,r)->{
            var set=r.capabilities().stream().filter(c->c.specSha().equals(specs.get(new SpecKey(c.capabilityId(),c.capabilityVersion()))))
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            result.put(agent,new CapabilityObserved(r.agent(),r.provider(),r.binding(),r.installation(),r.host(),r.instance(),r.generation(),set));
        });return Map.copyOf(result);
    }
    private static boolean covers(CapabilityObserved observed,Map<SpecKey,String> specs){
        return observed!=null && specs.entrySet().stream().allMatch(e->observed.capabilities().stream().anyMatch(c->
                c.capabilityId().equals(e.getKey().capabilityId()) && c.capabilityVersion().equals(e.getKey().version()) && c.specSha().equals(e.getValue())));
    }
    private static void sameIdentity(CapabilityObserved c,Observed r){
        if(c==null || !c.agent().equals(r.agent()) || !c.provider().equals(r.provider()) || c.binding()!=r.binding()
                || !c.installation().equals(r.installation()) || !c.host().equals(r.host()) || !c.instance().equals(r.instance())
                || c.generation()!=r.generation())throw new TaskQuoteIntentService.Stale();
    }
    Read read(TaskQuoteIntentService.Fresh fresh){
        var scope=Objects.requireNonNull(fresh.confirmedScope(),"Confirmed scope required");var domain=fresh.key().task().scope();
        var specs=specifications.readActive(domain.tenantId(),domain.clientId(),keys(scope));var specShas=hashes(specs);
        var metadata=RecommendationMaterials.quoteMetadata(fresh.metadata());
        // A missing authorized reference is not missing cost metadata, even for INPUT_COUNT without a MIME filter.
        if(scope.workItems().stream().flatMap(i->i.inputRefs().stream()).anyMatch(ref->!metadata.containsKey(ref)))throw new TaskQuoteIntentService.Stale();
        var declared=new HashMap<ScopedCostInputs.Capability,CapabilityQuoteSpec>();
        specs.forEach((key,value)->declared.put(new ScopedCostInputs.Capability(key.capabilityId(),key.version()),value));
        var inputs=ScopedCostInputs.prepare(scope,declared,metadata);
        var capabilities=relevant(profiles.readCapabilities(domain.tenantId(),domain.clientId(),fresh.candidates(),false),specShas);
        var potential=fresh.candidates().stream().filter(t->covers(capabilities.get(t.agent()),specShas)).toList();
        var reports=potential.isEmpty()?Map.<String,Observed>of():profiles.read(domain.tenantId(),domain.clientId(),potential,false);
        var decoded=new HashMap<String,CostProfileReport>();var measured=new LinkedHashMap<String,List<MeasuredCostProfile>>();
        for(var candidate:fresh.candidates()){
            var observed=reports.get(candidate.agent());List<MeasuredCostProfile> measurements=List.of();
            if(observed!=null){
                sameIdentity(capabilities.get(candidate.agent()),observed);
                // The registration reader owns stored SHA/sequence/installation validation.
                measurements=decoded.computeIfAbsent(observed.canonical(),CostProfileReportJson::read).profiles();
            }
            measured.put(candidate.agent(),measurements);
        }
        var resolved=routes.prepareScoped(domain,inputs,measured);
        var lookup=fresh.candidates().stream().flatMap(t->resolved.input(t.agent()).stream()).distinct()
                .sorted(Comparator.comparing(GroupMeanPricing.ScopeInput::key)).toList();
        var selected=lookup.isEmpty()?Map.<GroupMeanPricing.ScopeInput,ScopedGroupPolicies.Selected>of():policies.read(domain,lookup,false);
        if(!new HashSet<>(lookup).containsAll(selected.keySet()))throw new ScopedGroupPolicies.Corrupt();
        var bases=new LinkedHashMap<String,CandidateGroupOffers.Basis>();var adoptedReports=new HashMap<String,Observed>();
        for(var candidate:fresh.candidates()){
            String agent=candidate.agent();var measurement=resolved.selected(agent);
            // Multiple individual capabilities do not prove an actual composite executor. A complete
            // currently authorized registered measurement is the adapter's combination declaration.
            boolean capable=covers(capabilities.get(agent),specShas) && (scope.workItems().size()==1 || measurement.isPresent());
            if(!capable){bases.put(agent,new CandidateGroupOffers.Unavailable());continue;}
            if(measurement.isPresent())adoptedReports.put(agent,Objects.requireNonNull(reports.get(agent)));
            TaskQuoteBatches.Reason missing=null;
            if(resolved.inputStatus()==CapabilityQuoteSpec.MatchStatus.COST_INPUT_MISSING)missing=TaskQuoteBatches.Reason.COST_INPUT_MISSING;
            else if(resolved.inputStatus()==CapabilityQuoteSpec.MatchStatus.OUT_OF_DISTRIBUTION)missing=TaskQuoteBatches.Reason.OUT_OF_DISTRIBUTION;
            else if(resolved.configuration(agent).isEmpty())missing=TaskQuoteBatches.Reason.BILLING_ROUTE_MISSING;
            if(missing!=null){bases.put(agent,new CandidateGroupOffers.Missing(missing));continue;}
            var input=resolved.input(agent).orElseThrow();var policy=selected.get(input);
            if(policy==null){bases.put(agent,new CandidateGroupOffers.Missing(TaskQuoteBatches.Reason.NO_BASELINE));continue;}
            policy.policy().requireInput(input);
            var group=resolved.group(agent,policy.policy().featureVersion(),policy.policy().sha()).orElseThrow();
            if(!group.key().equals(policy.group().key()) || !input.equals(policy.group().input()))throw new ScopedGroupPolicies.Corrupt();
            var observed=reports.get(agent);var config=resolved.configuration(agent).orElseThrow();var m=config.measurement();
            var pin=new PricingProfilePin(observed.installation(),observed.instance(),observed.generation(),observed.seq(),observed.sha(),
                    m.profileId(),m.revision(),m.measurementSha(),config.configSha());
            bases.put(agent,new CandidateGroupOffers.ScopedGroup(group,pin,policy.policy().minSupport()));
        }
        return new Read(fresh,bases,capabilities,adoptedReports,specShas,resolved,selected);
    }
    /** Inside the original finish transaction, before the shared group locks and wallet batch writer. */
    void recheck(Read prepared){
        var fresh=prepared.fresh();var domain=fresh.key().task().scope();
        var current=relevant(profiles.readCapabilities(domain.tenantId(),domain.clientId(),fresh.candidates(),true),prepared.specShas());
        if(!current.equals(prepared.capabilities()))throw new TaskQuoteIntentService.Stale();
        var adoptedTargets=fresh.candidates().stream().filter(t->prepared.adoptedReports().containsKey(t.agent())).toList();
        if(!adoptedTargets.isEmpty() && !prepared.adoptedReports().equals(profiles.read(domain.tenantId(),domain.clientId(),adoptedTargets,true)))throw new TaskQuoteIntentService.Stale();
        if(!prepared.specShas().equals(hashes(specifications.lockActive(domain.tenantId(),domain.clientId(),keys(fresh.confirmedScope())))))throw new TaskQuoteIntentService.Stale();
        routes.recheckScoped(domain,prepared.resolved());
        var inputs=prepared.selectedPolicies().keySet().stream().sorted(Comparator.comparing(GroupMeanPricing.ScopeInput::key)).toList();
        if(inputs.isEmpty())return;
        var policiesNow=policies.read(domain,inputs,true);
        if(!policiesNow.keySet().equals(prepared.selectedPolicies().keySet()))throw new TaskQuoteIntentService.Stale();
        prepared.selectedPolicies().forEach((input,old)->{
            var now=policiesNow.get(input);
            if(!old.approvalSha().equals(now.approvalSha()) || !old.policy().sha().equals(now.policy().sha())
                    || !old.group().key().equals(now.group().key()))throw new TaskQuoteIntentService.Stale();
        });
    }
}
