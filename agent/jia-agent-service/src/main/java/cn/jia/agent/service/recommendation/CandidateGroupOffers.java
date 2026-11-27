package cn.jia.agent.service.recommendation;

import cn.jia.economy.common.Money;
import cn.jia.economy.pricing.GroupMeanPricing;
import cn.jia.economy.task.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** Prepare once per cost group; issue once per authorized candidate. Internal, never deserialized from HTTP. */
@Service
final class CandidateGroupOffers {
    sealed interface Basis permits Missing,ComparableGroup,ScopedGroup,Unavailable {}
    record Missing(TaskQuoteBatches.Reason reason) implements Basis { Missing { Objects.requireNonNull(reason); } }
    record ComparableGroup(GroupMeanPricing.Group group,TaskBillingService.MeasuredGroup execution,String initialIntentSha) implements Basis {
        ComparableGroup { Objects.requireNonNull(group);Objects.requireNonNull(execution); }
    }
    /** Capability denial is not missing cost data and never produces a price row. */
    record Unavailable() implements Basis {}
    record ScopedGroup(GroupMeanPricing.ScopeGroup group,ScopedTaskQuoteSnapshot.PricingProfilePin profile) implements Basis {
        ScopedGroup { Objects.requireNonNull(group);Objects.requireNonNull(profile); }
    }
    private static GroupBudgetQuotes.Key key(Basis basis){
        if(basis instanceof ComparableGroup c)return new GroupBudgetQuotes.Key(GroupMeanPricing.key(c.group()));
        if(basis instanceof ScopedGroup c)return new GroupBudgetQuotes.Key(c.group().key());
        return null;
    }
    private static String policy(Basis basis){
        return basis instanceof ScopedGroup c?c.group().policySha():((ComparableGroup)basis).group().policySha();
    }
    private static void requireScopeShape(TaskQuoteIntentService.Fresh fresh,GroupMeanPricing.ScopeGroup group){
        var scope=Objects.requireNonNull(fresh.confirmedScope(),"Scoped price requires confirmed scope");
        var items=scope.workItems();var slots=group.scopeShape();
        if(items.size()!=slots.size())throw new IllegalArgumentException("Incomplete whole-scope quote group");
        var indices=new HashMap<String,Integer>();
        for(int i=0;i<items.size();i++){
            var item=items.get(i);var slot=slots.get(i);
            if(!item.capabilityId().equals(slot.capabilityId())||!item.capabilityVersion().equals(slot.capabilityVersion())
                ||!item.dependsOn().stream().map(indices::get).sorted().toList().equals(slot.dependsOn()))
                throw new IllegalArgumentException("Quote group differs from confirmed scope");
            indices.put(item.itemId(),i);
        }
    }
    /** Backend-only classification. Call with cost configuration already resolved for this authorized target;
     * policy is the approved immutable definition for this operation, not a browser-supplied version. */
    static Basis classify(QuoteFeaturePreparation.LocalFacts facts,GroupMeanPricing.Policy policy,GroupMeanPricing.Configuration config,
                          TaskBillingService.MeasuredGroup execution,String initialIntentSha){
        var op=facts.operation();
        if(op==null)return new Missing(TaskQuoteBatches.Reason.OUT_OF_DISTRIBUTION);
        if(config==null || execution==null || !config.supportsImage(op))return new Missing(TaskQuoteBatches.Reason.BILLING_ROUTE_MISSING);
        if(policy==null)return new Missing(TaskQuoteBatches.Reason.OUT_OF_DISTRIBUTION);
        var dimensions=new EnumMap<GroupMeanPricing.Dimension,Long>(GroupMeanPricing.Dimension.class);
        dimensions.put(GroupMeanPricing.Dimension.INPUT_FILE_COUNT,(long)facts.files().size());
        long bytes=0;for(var f:facts.files()){if(f.byteLength()<0)throw new IllegalStateException("Invalid input metadata");bytes=Math.addExact(bytes,f.byteLength());}
        dimensions.put(GroupMeanPricing.Dimension.TOTAL_INPUT_BYTES,bytes);
        if(facts.requestedDeliverables()!=null)dimensions.put(GroupMeanPricing.Dimension.REQUESTED_DELIVERABLES,facts.requestedDeliverables().longValue());
        return policy.classify(op,dimensions,config).<Basis>map(g->new ComparableGroup(g,execution,initialIntentSha)).orElseGet(()->new Missing(TaskQuoteBatches.Reason.OUT_OF_DISTRIBUTION));
    }
    private record Offer(String agent,Basis basis,GroupBudgetQuotes.Decision price) {}
    static final class Prepared {
        private final TaskQuoteIntentService.Fresh fresh; private final List<Offer> offers;
        private Prepared(TaskQuoteIntentService.Fresh fresh,List<Offer> offers){this.fresh=fresh;this.offers=List.copyOf(offers);}
    }
    private final GroupBudgetQuotes estimates;private final TaskBillingService billing;
    CandidateGroupOffers(GroupBudgetQuotes estimates,TaskBillingService billing){this.estimates=estimates;this.billing=billing;}
    Prepared prepare(TaskQuoteIntentService.Fresh fresh,Map<String,Basis> bases){
        var agents=fresh.intent().agentIds();
        if(!agents.equals(fresh.candidates().stream().map(t->t.agent()).toList()) || !bases.keySet().equals(Set.copyOf(agents)))
            throw new IllegalArgumentException("Cost inputs do not match authorized candidates");
        var keys=new LinkedHashMap<String,GroupBudgetQuotes.Key>();
        for(var agent:agents){
            var basis=Objects.requireNonNull(bases.get(agent));
            if(basis instanceof ScopedGroup c)requireScopeShape(fresh,c.group());
            if(basis instanceof ComparableGroup c){
                if(fresh.confirmedScope()!=null)throw new IllegalArgumentException("Confirmed scope cannot use legacy pricing");
                if(!c.group().operation().equals(fresh.facts().operation()))throw new IllegalArgumentException("Operation does not match quote facts");
            }
            var key=key(basis);if(key!=null)keys.put(agent,key);
        }
        var distinct=Set.copyOf(keys.values());var prices=distinct.isEmpty()?Map.<GroupBudgetQuotes.Key,GroupBudgetQuotes.Decision>of():estimates.prepare(fresh.key().task().scope(),distinct);
        if(!prices.keySet().equals(distinct))throw new IllegalStateException("Incomplete cost decisions");
        var offers=new ArrayList<Offer>();
        for(var agent:agents){
            var basis=bases.get(agent);
            if(basis instanceof Unavailable){offers.add(new Offer(agent,basis,null));continue;}
            if(basis instanceof Missing m){offers.add(new Offer(agent,basis,new GroupBudgetQuotes.Fixed(m.reason())));continue;}
            var key=keys.get(agent);var price=Objects.requireNonNull(prices.get(key));
            if(price instanceof GroupBudgetQuotes.Mean m && (!key.equals(m.pin().key())||!policy(basis).equals(m.pin().policySha())))throw new IllegalStateException("Published group does not match cost policy");
            offers.add(new Offer(agent,basis,price));
        }
        return new Prepared(fresh,offers);
    }
    /** Called only by the winning intent writer after rechecking scope, inputs and any configuration used. */
    List<TaskQuoteBatches.Item> issue(Prepared prepared,String batch){
        var fresh=prepared.fresh;var facts=fresh.facts();var task=fresh.key().task();var writes=new ArrayList<TaskBillingService.OfferWrite>();
        var pins=new LinkedHashSet<GroupBudgetQuotes.Pin>();var summaries=new HashMap<String,TaskQuoteBatches.Summary>();
        var scope=fresh.confirmedScope();
        for(var o:prepared.offers){
            if(o.basis() instanceof Unavailable)continue;
            var bind=new TaskBillingService.Binding(facts.requirementRevision(),facts.requirementSha256(),facts.inputSnapshotSha256(),o.agent(),batch);
            if(scope!=null){
                var scopePin=new ScopedTaskQuoteSnapshot.ScopePin(scope.requirementRevision(),scope.requirementSha256(),scope.scopeSha256(),scope.inputSnapshotDigest());
                ScopedTaskQuoteSnapshot.Basis basis;
                var profile=o.basis() instanceof ScopedGroup c?c.profile():null;
                if(o.price() instanceof GroupBudgetQuotes.Mean m){
                    var group=((ScopedGroup)o.basis()).group();
                    basis=new ScopedTaskQuoteSnapshot.Basis("GROUP_MEAN",ScopedTaskQuoteSnapshot.MeanEvidence.from(group,m),null);
                }else basis=new ScopedTaskQuoteSnapshot.Basis("FIXED_FALLBACK",null,((GroupBudgetQuotes.Fixed)o.price()).reason());
                // Pricing evidence never manufactures an initial execution commitment.
                var snapshot=new ScopedTaskQuoteSnapshot(ScopedTaskQuoteSnapshot.SCHEMA,bind,scopePin,basis,profile,null,null,basis.amountWen());
                writes.add(new TaskBillingService.ScopedWrite(snapshot));
            }else if(o.price() instanceof GroupBudgetQuotes.Mean m){
                var c=(ComparableGroup)o.basis();var g=c.group();
                writes.add(new TaskBillingService.MeanWrite(bind,m,new TaskBillingService.LocalQuoteFacts(g.featureVersion(),g.operation(),g.bucketId()),c.execution(),c.initialIntentSha()));
            }else writes.add(new TaskBillingService.FixedWrite(bind,(GroupBudgetQuotes.Fixed)o.price()));
            if(o.price() instanceof GroupBudgetQuotes.Mean m){
                pins.add(m.pin());summaries.put(o.agent(),new TaskQuoteBatches.Summary("GROUP_MEAN","1",m.pin().n(),"PUBLISHED"));
            }else summaries.put(o.agent(),new TaskQuoteBatches.Summary("FIXED_FALLBACK","1",0,"FIXED_PRICE"));
        }
        if(!pins.isEmpty())estimates.lockSelected(task.scope(),pins);
        var stored=writes.isEmpty()?List.<TaskBillingService.StoredQuote>of():billing.storeOffersBatch(task,fresh.key().payer(),writes);
        if(stored.size()!=writes.size())throw new IllegalStateException("Incomplete quote batch");
        var items=new ArrayList<TaskQuoteBatches.Item>();int storedIndex=0;
        for(var offer:prepared.offers){
            if(offer.basis() instanceof Unavailable){
                items.add(new TaskQuoteBatches.Item(offer.agent(),TaskQuoteBatches.State.UNAVAILABLE,null,batch,null,TaskQuoteBatches.Reason.CAPABILITY_MISMATCH,null));
            }else{
                var row=stored.get(storedIndex++);
                items.add(new TaskQuoteBatches.Item(offer.agent(),TaskQuoteBatches.State.AVAILABLE,row.quoteId(),batch,Money.wen(row.amountWen()),null,summaries.get(offer.agent())));
            }
        }
        return List.copyOf(items);
    }
}
