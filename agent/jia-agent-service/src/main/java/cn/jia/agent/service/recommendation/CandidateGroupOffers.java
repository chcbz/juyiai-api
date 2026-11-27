package cn.jia.agent.service.recommendation;

import cn.jia.economy.common.Money;
import cn.jia.economy.pricing.GroupMeanPricing;
import cn.jia.economy.task.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** Prepare once per cost group; issue once per authorized candidate. Internal, never deserialized from HTTP. */
@Service
final class CandidateGroupOffers {
    sealed interface Basis permits Missing,ComparableGroup {}
    record Missing(TaskQuoteBatches.Reason reason) implements Basis { Missing { Objects.requireNonNull(reason); } }
    record ComparableGroup(GroupMeanPricing.Group group,TaskBillingService.MeasuredGroup execution,String initialIntentSha) implements Basis {
        ComparableGroup { Objects.requireNonNull(group);Objects.requireNonNull(execution); }
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
    private record Offer(String agent,ComparableGroup comparable,GroupBudgetQuotes.Decision price) {}
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
        var keys=new LinkedHashMap<String,GroupBudgetQuotes.Key>();var byGroup=new HashMap<GroupMeanPricing.Group,GroupBudgetQuotes.Key>();
        for(var agent:agents)if(bases.get(agent) instanceof ComparableGroup c){
            var group=c.group();
            if(!group.operation().equals(fresh.facts().operation()))throw new IllegalArgumentException("Operation does not match quote facts");
            keys.put(agent,byGroup.computeIfAbsent(group,g->new GroupBudgetQuotes.Key(GroupMeanPricing.key(g))));
        }
        var distinct=Set.copyOf(keys.values());var prices=distinct.isEmpty()?Map.<GroupBudgetQuotes.Key,GroupBudgetQuotes.Decision>of():estimates.prepare(fresh.key().task().scope(),distinct);
        if(!prices.keySet().equals(distinct))throw new IllegalStateException("Incomplete cost decisions");
        var offers=new ArrayList<Offer>();
        for(var agent:agents){
            if(bases.get(agent) instanceof Missing m){offers.add(new Offer(agent,null,new GroupBudgetQuotes.Fixed(m.reason())));continue;}
            var c=(ComparableGroup)Objects.requireNonNull(bases.get(agent));var key=keys.get(agent);var price=Objects.requireNonNull(prices.get(key));
            if(price instanceof GroupBudgetQuotes.Mean m && (!key.equals(m.pin().key())||!c.group().policySha().equals(m.pin().policySha())))throw new IllegalStateException("Published group does not match cost policy");
            offers.add(new Offer(agent,c,price));
        }
        return new Prepared(fresh,offers);
    }
    /** Called only by the winning intent writer after rechecking scope, inputs and any configuration used. */
    List<TaskQuoteBatches.Item> issue(Prepared prepared,String batch){
        var fresh=prepared.fresh;var facts=fresh.facts();var task=fresh.key().task();var writes=new ArrayList<TaskBillingService.OfferWrite>();
        var pins=new LinkedHashSet<GroupBudgetQuotes.Pin>();var summaries=new ArrayList<TaskQuoteBatches.Summary>();
        for(var o:prepared.offers){
            var bind=new TaskBillingService.Binding(facts.requirementRevision(),facts.requirementSha256(),facts.inputSnapshotSha256(),o.agent(),batch);
            if(o.price() instanceof GroupBudgetQuotes.Mean m){
                var c=o.comparable();var g=c.group();pins.add(m.pin());
                writes.add(new TaskBillingService.MeanWrite(bind,m,new TaskBillingService.LocalQuoteFacts(g.featureVersion(),g.operation(),g.bucketId()),c.execution(),c.initialIntentSha()));
                summaries.add(new TaskQuoteBatches.Summary("GROUP_MEAN","1",m.pin().n(),"PUBLISHED"));
            }else{
                writes.add(new TaskBillingService.FixedWrite(bind,(GroupBudgetQuotes.Fixed)o.price()));
                summaries.add(new TaskQuoteBatches.Summary("FIXED_FALLBACK","1",0,"FIXED_PRICE"));
            }
        }
        if(!pins.isEmpty())estimates.lockSelected(task.scope(),pins);
        var stored=billing.storeOffersBatch(task,fresh.key().payer(),writes);
        if(stored.size()!=writes.size())throw new IllegalStateException("Incomplete quote batch");
        var items=new ArrayList<TaskQuoteBatches.Item>();
        for(int i=0;i<stored.size();i++){var row=stored.get(i);items.add(new TaskQuoteBatches.Item(writes.get(i).binding().agentId(),TaskQuoteBatches.State.AVAILABLE,row.quoteId(),batch,Money.wen(row.amountWen()),null,summaries.get(i)));}
        return List.copyOf(items);
    }
}
