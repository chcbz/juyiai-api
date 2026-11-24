package cn.jia.agent.service.recommendation;

import cn.jia.agent.service.AgentExternalServicePolicyService.QuoteTarget;
import cn.jia.economy.task.TaskBudgetEstimator;
import cn.jia.economy.task.TaskBillingService.Task;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Internal pre-dispatch authority. Never derive terms from a heartbeat, past PLAN, handle or browser metadata. */
public interface ProspectiveTaskExecution {
    record Inputs(Task task,String taskVersion,long requirementRevision,String requirementSha,String inputSha){}
    record Evidence(String revision,TaskBudgetEstimator.Terms terms,String initialIntentSha256){
        public Evidence(String revision,TaskBudgetEstimator.Terms terms){this(revision,terms,null);}
        public Evidence{
        if(revision==null||revision.isBlank())throw new IllegalArgumentException("Missing execution configuration revision");Objects.requireNonNull(terms);
    }}
    /** Local prepared facts only. Missing entry means no authoritative configuration for this target and exact inputs. */
    Map<String,Evidence> observe(Inputs inputs,List<QuoteTarget> targets);
    /** Under scoped task lock: compare current revision(s), including continued absence. No runtime/network calls. */
    boolean current(Inputs inputs,List<QuoteTarget> targets,Map<String,Evidence> before);
}
