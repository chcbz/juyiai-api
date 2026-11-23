package cn.jia.agent.service.recommendation;

import cn.jia.agent.hosting.HostingRentHttp.Actor;
import cn.jia.agent.service.TaskFeatureEncoder;
import cn.jia.economy.common.Money;
import cn.jia.economy.task.TaskBillingService;
import cn.jia.economy.task.TaskBudgetEstimator;
import cn.jia.economy.task.TaskQuoteBatches;
import java.util.*;
import org.springframework.stereotype.Service;

/** Independent quote intent. No recommendation, wallet operation, raw runtime plan or client-supplied price. */
@Service
public final class IndependentTaskQuotes {
    private final TaskQuoteIntentService intents;private final Optional<ProspectiveTaskExecution> execution;
    private final TaskBudgetEstimator estimates;private final TaskBillingService billing;
    public IndependentTaskQuotes(TaskQuoteIntentService intents,Optional<ProspectiveTaskExecution> execution,TaskBudgetEstimator estimates,TaskBillingService billing){
        this.intents=intents;this.execution=execution;this.estimates=estimates;this.billing=billing;}
    public static final class Changed extends RuntimeException{}
    public static final class NotFound extends RuntimeException{}
    public TaskQuoteBatches.Result quote(Actor actor,String id,String key,TaskQuoteBatches.Intent intent){
        try{return execute(actor,id,key,intent);}
        catch(TaskQuoteIntentService.Stale|TaskBudgetEstimator.Stale stale){throw new Changed();}
        catch(TaskQuoteIntentService.CandidateNotAuthorized denied){throw new NotFound();}
    }
    private TaskQuoteBatches.Result execute(Actor actor,String id,String key,TaskQuoteBatches.Intent intent){
        TaskQuoteIntentService.Start start;
        try{start=intents.begin(actor,id,key,intent);}
        catch(TaskFeatureEncoder.InvalidResult|TaskFeatureEncoder.Unavailable error){
            var winner=intents.replay(actor,id,key,intent);if(winner!=null)return winner;throw error;
        }
        if(start instanceof TaskQuoteIntentService.Replay replay)return replay.stored();
        var fresh=(TaskQuoteIntentService.Fresh)start;var f=fresh.features();var task=fresh.key().task();
        var inputs=new ProspectiveTaskExecution.Inputs(task,intent.expectedTaskVersion(),fresh.requirementRevision(),f.requirementSha256(),f.inputSnapshotSha256());
        var terms=execution.map(x->Map.copyOf(x.observe(inputs,fresh.candidates()))).orElse(Map.of());
        if(!intent.agentIds().containsAll(terms.keySet()))throw new IllegalStateException("Unrequested pricing configuration");
        var prepared=new LinkedHashMap<String,TaskBudgetEstimator.Outcome>();var available=new ArrayList<TaskBudgetEstimator.Available>();
        // Only within this authorized intent: task/features are fixed. Equality includes the entire
        // execution group AND all pricing routes. Never share persisted quotes or candidate bindings.
        var byTerms=new HashMap<TaskBudgetEstimator.Terms,TaskBudgetEstimator.Outcome>();
        for(var agent:intent.agentIds()){
            var proof=terms.get(agent);
            var offer=byTerms.computeIfAbsent(proof==null?null:proof.terms(),t->Objects.requireNonNull(estimates.prepare(task,f,t)));
            prepared.put(agent,offer);
        }
        for(var offer:byTerms.values())if(offer instanceof TaskBudgetEstimator.Available ready)available.add(ready);
        return intents.finish(actor,fresh,batch->{
            if(execution.isPresent()&&!execution.get().current(inputs,fresh.candidates(),terms))throw new Changed();
            estimates.lockSelected(task,available);var result=new ArrayList<TaskQuoteBatches.Item>();
            prepared.forEach((agent,outcome)->{if(outcome instanceof TaskBudgetEstimator.Unavailable unavailable){
                result.add(new TaskQuoteBatches.Item(agent,TaskQuoteBatches.State.UNAVAILABLE,null,batch,null,unavailable.reason(),null));return;}
                var p=((TaskBudgetEstimator.Available)outcome).preparation();var binding=new TaskBillingService.Binding(fresh.requirementRevision(),f.requirementSha256(),f.inputSnapshotSha256(),agent,batch);
                var quote=billing.storeMeasured(task,binding,actor.actorId(),p);var summary=new TaskQuoteBatches.Summary("MEASURED_NEIGHBORS",p.estimatorVersion(),p.estimate().contributions().size(),p.qualityStatus());
                result.add(new TaskQuoteBatches.Item(agent,TaskQuoteBatches.State.AVAILABLE,quote.quoteId(),batch,Money.wen(quote.amountWen()),null,summary));});return result;
        });
    }
}
