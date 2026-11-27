package cn.jia.agent.service.recommendation;

import cn.jia.agent.hosting.HostingRentHttp.Actor;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final ScopedGroupQuoteInputs scoped;
    private final ObservationRegistry registry;
    IndependentTaskQuotes(TaskQuoteIntentService intents,CandidateGroupOffers offers,RegisteredGroupQuoteInputs inputs,ScopedGroupQuoteInputs scoped){this(intents,offers,inputs,scoped,Optional.empty());}
    @Autowired IndependentTaskQuotes(TaskQuoteIntentService intents,CandidateGroupOffers offers,RegisteredGroupQuoteInputs inputs,ScopedGroupQuoteInputs scoped,Optional<ObservationRegistry> registry){
        this.intents=intents;this.offers=offers;this.inputs=inputs;this.scoped=scoped;this.registry=registry.orElse(ObservationRegistry.NOOP);
    }
    // One observation per reached batch stage, never per candidate. Do not export exception
    // messages, identity, quote IDs or hashes. finish includes the winning transaction OR replay.
    private <T> T stage(String name,Supplier<T> work){
        if(registry.isNoop())return work.get();
        var span=Observation.createNotStarted("cyf.task.quote.batch.stage",registry).lowCardinalityKeyValue("stage",name).start();
        String outcome="error";
        try{var result=work.get();outcome="ok";return result;}
        finally{span.lowCardinalityKeyValue("outcome",outcome).stop();}
    }
    public static final class Changed extends RuntimeException{}
    public static final class NotFound extends RuntimeException{}
    public TaskQuoteBatches.Result quote(Actor actor,String id,String key,TaskQuoteBatches.Intent intent){
        try{return execute(actor,id,key,intent);}
        catch(cn.jia.agent.security.PreparedRuntimeEpochFence.Busy|cn.jia.economy.pricing.ScopedGroupPolicies.Busy|TaskQuoteIntentService.Stale|TaskBudgetEstimator.Stale stale){throw new Changed();}
        catch(TaskQuoteIntentService.CandidateNotAuthorized denied){throw new NotFound();}
    }
    private TaskQuoteBatches.Result execute(Actor actor,String id,String key,TaskQuoteBatches.Intent intent){
        var start=stage("intent_features",()->intents.begin(actor,id,key,intent));
        if(start instanceof TaskQuoteIntentService.Replay replay)return replay.stored();
        var fresh=(TaskQuoteIntentService.Fresh)start;
        if(fresh.confirmedScope()!=null){
            var observations=stage("configuration",()->scoped.read(fresh));
            var prepared=stage("baseline",()->offers.prepare(fresh,observations.bases()));
            return stage("finish",()->intents.finish(actor,fresh,batch->{scoped.recheck(observations);return offers.issue(prepared,batch);}));
        }
        // Unmigrated text-only entry is retained until its real scope producer is integrated;
        // it never claims to have consumed the new estimator. Do not deploy this intermediate chain.
        var observations=stage("configuration",()->fresh.experimentSha()==null?RegisteredGroupQuoteInputs.fixed(fresh):inputs.read(fresh));
        var prepared=stage("baseline",()->offers.prepare(observations.priced(),observations.bases()));
        return stage("finish",()->intents.finish(actor,fresh,batch->{if(fresh.experimentSha()!=null)inputs.recheck(fresh,observations);return offers.issue(prepared,batch);}));
    }
}
