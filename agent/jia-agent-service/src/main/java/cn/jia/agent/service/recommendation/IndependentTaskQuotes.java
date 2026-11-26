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
    private final CandidateGroupOffers offers;
    public IndependentTaskQuotes(TaskQuoteIntentService intents,CandidateGroupOffers offers){this.intents=intents;this.offers=offers;}
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
        var fresh=(TaskQuoteIntentService.Fresh)start;var facts=fresh.facts();
        // The ordinary creation contract currently supplies no attested operation. Never convert a
        // future structured input to an ordinary fixed offer without evaluating its cost group.
        if(facts.operation()!=null)throw new IllegalStateException("Structured quote requires an evaluated cost group");
        var bases=new HashMap<String,CandidateGroupOffers.Basis>();
        for(var candidate:fresh.candidates())bases.put(candidate.agent(),CandidateGroupOffers.classify(facts,null,null,null,null));
        var prepared=offers.prepare(fresh,bases);
        return intents.finish(actor,fresh,batch->offers.issue(prepared,batch));
    }
}
