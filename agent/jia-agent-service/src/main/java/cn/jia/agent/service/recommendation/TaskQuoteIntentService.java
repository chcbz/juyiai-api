package cn.jia.agent.service.recommendation;

import cn.jia.agent.service.AgentExternalServicePolicyService.QuoteTarget;
import cn.jia.agent.hosting.HostingRentHttp.Actor;
import cn.jia.agent.service.AgentExternalServicePolicyService;
import cn.jia.agent.service.AgentTaskExecutionGrantService;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.agent.service.AgentTaskRequirementSnapshotService;
import cn.jia.economy.task.TaskBillingService;
import cn.jia.economy.task.TaskQuoteBatches;
import org.springframework.stereotype.Service;
import java.util.*;

/** First half of independent quoting. Access is checked even for immutable replays. No wallet, assignment or recommendation call. */
@Service
final class TaskQuoteIntentService {

    static final class Stale extends RuntimeException {}
    static final class CandidateNotAuthorized extends RuntimeException {}
    sealed interface Start permits Replay,Fresh {}
    record Replay(TaskQuoteBatches.Result stored) implements Start {}
    record Fresh(TaskQuoteBatches.Key key,TaskQuoteBatches.Intent intent,QuoteFeaturePreparation.LocalFacts facts,List<QuoteTarget> candidates,List<RecommendationMaterials.Observation> metadata,String experimentSha) implements Start {
        Fresh(TaskQuoteBatches.Key key,TaskQuoteBatches.Intent intent,QuoteFeaturePreparation.LocalFacts facts,List<QuoteTarget> candidates,List<RecommendationMaterials.Observation> metadata){this(key,intent,facts,candidates,metadata,null);}
        Fresh withFacts(QuoteFeaturePreparation.LocalFacts classified){return new Fresh(key,intent,classified,candidates,metadata,experimentSha);}
    }
    private record Read(TaskQuoteBatches.Result replay,AgentTaskRequirementSnapshotService.CurrentSnapshot requirement,
                        AgentTaskExecutionGrantService.InputCatalogue catalogue,String experimentSha){}
    private final AgentTaskMutationTransaction roots;private final TaskQuoteBatches batches;private final AgentTaskRequirementSnapshotService requirements;
    private final AgentTaskExecutionGrantService grants;private final AgentExternalServicePolicyService agents;private final RecommendationMaterials materials;
    TaskQuoteIntentService(AgentTaskMutationTransaction roots,TaskQuoteBatches batches,AgentTaskRequirementSnapshotService requirements,AgentTaskExecutionGrantService grants,
                           AgentExternalServicePolicyService agents,RecommendationMaterials materials){this.roots=roots;this.batches=batches;this.requirements=requirements;this.grants=grants;this.agents=agents;this.materials=materials;}
    TaskQuoteBatches.Result replay(Actor actor,String task,String idempotencyKey,TaskQuoteBatches.Intent intent){
        var key=new TaskQuoteBatches.Key(new TaskBillingService.Task(actor.scope(),actor.ownerJiacn(),task),actor.actorId(),idempotencyKey);
        return roots.executeWithLockedTaskRootInOwnerScope(actor.tenantId(),actor.clientId(),actor.ownerJiacn(),task,root->batches.replay(key,intent));
    }
    Start begin(Actor actor,String task,String idempotencyKey,TaskQuoteBatches.Intent intent){
        var key=new TaskQuoteBatches.Key(new TaskBillingService.Task(actor.scope(),actor.ownerJiacn(),task),actor.actorId(),idempotencyKey);
        var scope=new AgentTaskExecutionGrantService.Scope(actor.tenantId(),actor.clientId(),actor.ownerJiacn());
        var read=roots.executeWithLockedTaskRootInOwnerScope(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),task,root->{
            var stored=batches.replay(key,intent);if(stored!=null)return new Read(stored,null,null,null);
            if(!"open".equals(root.getRewardStatus())||!intent.expectedTaskVersion().equals(String.valueOf(root.getTaskVersion())))throw new Stale();
            return new Read(null,requirements.readCurrentWithinLockedTask(scope,task,root),grants.snapshotTaskInputsWithinLockedTask(scope,task),root.getPricingExperimentSha());
        });
        if(read.replay()!=null)return new Replay(read.replay());
        var selected=authorized(scope,task,intent.agentIds(),false);var meta=materials.observe(actor,task);var facts=QuoteFeaturePreparation.localFacts(read.requirement(),read.catalogue(),meta);
        return new Fresh(key,intent,facts,List.copyOf(selected),List.copyOf(meta),read.experimentSha());
    }
    private List<QuoteTarget> authorized(AgentTaskExecutionGrantService.Scope scope,String task,List<String> ids,boolean locking){
        var targets=agents.quoteTargets(scope,task,ids,locking);if(!ids.equals(targets.stream().map(QuoteTarget::agent).toList()))throw new CandidateNotAuthorized();return targets;
    }
    TaskQuoteBatches.Result finish(Actor actor,Fresh start,java.util.function.Function<String,List<TaskQuoteBatches.Item>> write){
        var key=start.key();var id=key.task().taskId();var scope=new AgentTaskExecutionGrantService.Scope(actor.tenantId(),actor.clientId(),actor.ownerJiacn());
        return roots.executeWithLockedTaskRootInOwnerScope(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),id,root->batches.commit(key,start.intent(),batch->{
            if(!"open".equals(root.getRewardStatus())||!start.intent().expectedTaskVersion().equals(String.valueOf(root.getTaskVersion())))throw new Stale();
            var current=requirements.readCurrentWithinLockedTask(scope,id,root);var catalogue=grants.snapshotTaskInputsWithinLockedTask(scope,id);
            var meta=materials.observe(actor,id);
            if(!Objects.equals(root.getPricingExperimentSha(),start.experimentSha())||!start.metadata().equals(meta)||!start.facts().equals(QuoteFeaturePreparation.localFacts(current,catalogue,meta))
                ||!start.candidates().equals(authorized(scope,id,start.intent().agentIds(),true)))throw new Stale();
            return write.apply(batch);
        }));
    }
}
