package cn.jia.agent.service.recommendation;

import cn.jia.agent.hosting.HostingRentHttp.Actor;
import cn.jia.economy.task.GroupBudgetQuotes;
import cn.jia.economy.common.Money;
import cn.jia.economy.task.TaskBillingService;
import cn.jia.economy.task.TaskBudgetEstimator;
import cn.jia.economy.task.TaskQuoteBatches;
import java.util.*;
import org.springframework.stereotype.Service;

/** Independent quote intent. No recommendation, wallet operation, raw runtime plan or client-supplied price. */
@Service
public final class IndependentTaskQuotes {
    private final TaskQuoteIntentService intents;
    private final TaskBillingService billing;
    public IndependentTaskQuotes(TaskQuoteIntentService intents,TaskBillingService billing){this.intents=intents;this.billing=billing;}
    public static final class Changed extends RuntimeException{}
    public static final class NotFound extends RuntimeException{}
    public TaskQuoteBatches.Result quote(Actor actor,String id,String key,TaskQuoteBatches.Intent intent){
        try{return execute(actor,id,key,intent);}
        catch(TaskQuoteIntentService.Stale|TaskBudgetEstimator.Stale stale){throw new Changed();}
        catch(TaskQuoteIntentService.CandidateNotAuthorized denied){throw new NotFound();}
    }
    private TaskQuoteBatches.Result execute(Actor actor,String id,String key,TaskQuoteBatches.Intent intent){
        var start=intents.begin(actor,id,key,intent);
        if(start instanceof TaskQuoteIntentService.Replay replay)return replay.stored();
        var fresh=(TaskQuoteIntentService.Fresh)start;var facts=fresh.facts();var task=fresh.key().task();
        // The ordinary creation contract currently supplies no attested operation. Never convert a
        // future structured input to an ordinary fixed offer without evaluating its cost group.
        if(facts.operation()!=null)throw new IllegalStateException("Structured quote requires an evaluated cost group");
        var fixed=new GroupBudgetQuotes.Fixed(TaskQuoteBatches.Reason.OUT_OF_DISTRIBUTION);
        return intents.finish(actor,fresh,batch->{
            List<TaskBillingService.OfferWrite> writes=new ArrayList<>(intent.agentIds().size());
            for(var agent:intent.agentIds()){
                var binding=new TaskBillingService.Binding(facts.requirementRevision(),facts.requirementSha256(),facts.inputSnapshotSha256(),agent,batch);
                writes.add(new TaskBillingService.FixedWrite(binding,fixed));
            }
            var stored=billing.storeOffersBatch(task,actor.actorId(),writes);
            if(stored.size()!=writes.size())throw new IllegalStateException("Incomplete quote batch");
            var items=new ArrayList<TaskQuoteBatches.Item>(stored.size());
            var summary=new TaskQuoteBatches.Summary("FIXED_FALLBACK","1",0,"FIXED_PRICE");
            for(int i=0;i<stored.size();i++){
                var row=stored.get(i);items.add(new TaskQuoteBatches.Item(writes.get(i).binding().agentId(),TaskQuoteBatches.State.AVAILABLE,row.quoteId(),batch,Money.wen(row.amountWen()),null,summary));
            }
            return items;
        });
    }
}
