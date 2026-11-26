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
    private final RegisteredGroupQuoteInputs inputs;
    public IndependentTaskQuotes(TaskQuoteIntentService intents,CandidateGroupOffers offers,RegisteredGroupQuoteInputs inputs){this.intents=intents;this.offers=offers;this.inputs=inputs;}
    public static final class Changed extends RuntimeException{}
    public static final class NotFound extends RuntimeException{}
    public TaskQuoteBatches.Result quote(Actor actor,String id,String key,TaskQuoteBatches.Intent intent){
        try{return execute(actor,id,key,intent);}
        catch(cn.jia.agent.security.PreparedRuntimeEpochFence.Busy|TaskQuoteIntentService.Stale|TaskBudgetEstimator.Stale stale){throw new Changed();}
        catch(TaskQuoteIntentService.CandidateNotAuthorized denied){throw new NotFound();}
    }
    private TaskQuoteBatches.Result execute(Actor actor,String id,String key,TaskQuoteBatches.Intent intent){
        var start=intents.begin(actor,id,key,intent);
        if(start instanceof TaskQuoteIntentService.Replay replay)return replay.stored();
        var fresh=(TaskQuoteIntentService.Fresh)start;var observations=fresh.experimentSha()==null?RegisteredGroupQuoteInputs.fixed(fresh):inputs.read(fresh);
        var prepared=offers.prepare(observations.priced(),observations.bases());
        return intents.finish(actor,fresh,batch->{if(fresh.experimentSha()!=null)inputs.recheck(fresh,observations);return offers.issue(prepared,batch);});
    }
}
