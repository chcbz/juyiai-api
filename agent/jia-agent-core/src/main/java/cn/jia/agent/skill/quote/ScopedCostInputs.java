package cn.jia.agent.skill.quote;

import cn.jia.agent.requirement.ConfirmedScope;
import cn.jia.economy.pricing.GroupMeanPricing;
import java.util.*;

/** Request-local classification of an already confirmed scope. No authorization, file parsing,
 * profile selection, price lookup or fallback issuance occurs in this pure projection.
 */
public final class ScopedCostInputs {
    public record Capability(String id,String version) {}
    private final ConfirmedScope scope;
    private final List<GroupMeanPricing.ScopeSlot> shape;
    private final List<CapabilityQuoteSpec.Match> matches;
    private ScopedCostInputs(ConfirmedScope scope,List<GroupMeanPricing.ScopeSlot> shape,List<CapabilityQuoteSpec.Match> matches){
        this.scope=scope;this.shape=List.copyOf(shape);this.matches=List.copyOf(matches);
    }
    /** Caller has read current authorized published specifications and the fixed input catalogue.
     * All items are checked even when an earlier item lacks cost metadata: corruption/invalid
     * business constraints must not be hidden by a prior fallback condition.
     */
    public static ScopedCostInputs prepare(ConfirmedScope scope,Map<Capability,CapabilityQuoteSpec> specifications,
            Map<String,CapabilityQuoteSpec.InputMetadata> inputs){
        Objects.requireNonNull(scope);Objects.requireNonNull(specifications);Objects.requireNonNull(inputs);
        var shape=new ArrayList<GroupMeanPricing.ScopeSlot>();var matches=new ArrayList<CapabilityQuoteSpec.Match>();
        var ordinals=new HashMap<String,Integer>();
        for(var item:scope.workItems()){
            var spec=specifications.get(new Capability(item.capabilityId(),item.capabilityVersion()));
            if(spec==null)throw new IllegalArgumentException("Published scope specification required");
            int slot=shape.size();
            var dependencies=item.dependsOn().stream().map(ordinals::get).sorted().toList();
            matches.add(spec.classify(item,inputs));
            shape.add(new GroupMeanPricing.ScopeSlot(slot,item.capabilityId(),item.capabilityVersion(),spec.specSha(),dependencies));
            ordinals.put(item.itemId(),slot);
        }
        return new ScopedCostInputs(scope,shape,matches);
    }
    public List<CapabilityQuoteSpec.Match> matches(){return matches;}
    public CapabilityQuoteSpec.MatchStatus status(){
        if(matches.stream().anyMatch(m->m.status()==CapabilityQuoteSpec.MatchStatus.COST_INPUT_MISSING))
            return CapabilityQuoteSpec.MatchStatus.COST_INPUT_MISSING;
        if(matches.stream().anyMatch(m->m.status()==CapabilityQuoteSpec.MatchStatus.OUT_OF_DISTRIBUTION))
            return CapabilityQuoteSpec.MatchStatus.OUT_OF_DISTRIBUTION;
        return CapabilityQuoteSpec.MatchStatus.MATCHED;
    }
    /** Selection priority is evaluated before route availability; never silently choose a different
     * execution plan because its prices happen to be available. Installation authority is external.
     */
    public Optional<MeasuredCostProfile> select(List<MeasuredCostProfile> authorizedProfiles){
        var selected=MeasuredCostProfile.select(authorizedProfiles,scope.workItems());
        selected.ifPresent(this::checkShape);
        return selected;
    }
    private void checkShape(MeasuredCostProfile measurement){
        if(!measurement.matches(scope.workItems()) || !measurement.scopeShape().equals(shape.stream().map(GroupMeanPricing.ScopeSlot::canonical).toList()))
            throw new IllegalArgumentException("Cost profile differs from confirmed capability semantics");
    }
    public Optional<GroupMeanPricing.ScopeGroup> group(String featureVersion,String policySha,CostProfile profile){
        checkShape(profile.measurement());
        if(status()!=CapabilityQuoteSpec.MatchStatus.MATCHED)return Optional.empty();
        return Optional.of(new GroupMeanPricing.ScopeGroup(featureVersion,policySha,shape,
                matches.stream().map(CapabilityQuoteSpec.Match::bucketId).toList(),profile.compatibleConfiguration()));
    }
    @Override public String toString(){return "ScopedCostInputs[private]";}
}
