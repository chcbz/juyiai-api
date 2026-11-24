package cn.jia.agent.service.recommendation;

import cn.jia.agent.common.AgentTaskStoredLists;
import cn.jia.agent.service.AgentExternalServicePolicyService.QuoteTarget;
import cn.jia.agent.hosting.HostingRentHttp.Actor;
import cn.jia.agent.service.AgentExternalServicePolicyService;
import cn.jia.agent.service.AgentTaskExecutionGrantService;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.agent.service.AgentTaskRequirementSnapshotService;
import cn.jia.economy.pricing.TaskEstimateFeatures;
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
    record Fresh(TaskQuoteBatches.Key key,TaskQuoteBatches.Intent intent,long requirementRevision,
                 TaskEstimateFeatures features,List<QuoteTarget> candidates,List<RecommendationMaterials.Observation> metadata) implements Start {}
    private record Read(TaskQuoteBatches.Result replay,AgentTaskRequirementSnapshotService.CurrentSnapshot requirement,
                        List<String> abilities,AgentTaskExecutionGrantService.InputCatalogue catalogue){}
    private final AgentTaskMutationTransaction roots;private final TaskQuoteBatches batches;private final AgentTaskRequirementSnapshotService requirements;
    private final AgentTaskExecutionGrantService grants;private final AgentExternalServicePolicyService agents;private final RecommendationMaterials materials;private final QuoteFeaturePreparation features;
    TaskQuoteIntentService(AgentTaskMutationTransaction roots,TaskQuoteBatches batches,AgentTaskRequirementSnapshotService requirements,AgentTaskExecutionGrantService grants,
                           AgentExternalServicePolicyService agents,RecommendationMaterials materials,QuoteFeaturePreparation features){this.roots=roots;this.batches=batches;this.requirements=requirements;this.grants=grants;this.agents=agents;this.materials=materials;this.features=features;}
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
            return new Read(null,requirements.readCurrentWithinLockedTask(scope,task,root),AgentTaskStoredLists.read(root.getRequiredAbilities()),grants.snapshotTaskInputsWithinLockedTask(scope,task));
        });
        if(read.replay()!=null)return new Replay(read.replay());
        var selected=authorized(scope,task,intent.agentIds(),false);var meta=materials.observe(actor,task);var input=features.observe(read.requirement(),read.abilities(),read.catalogue(),meta);
        return new Fresh(key,intent,input.requirementRevision(),features.prepare(actor,input),selected,List.copyOf(meta));
    }
    private List<QuoteTarget> authorized(AgentTaskExecutionGrantService.Scope scope,String task,List<String> ids,boolean locking){
        var targets=agents.quoteTargets(scope,task,ids,locking);if(!ids.equals(targets.stream().map(QuoteTarget::agent).toList()))throw new CandidateNotAuthorized();return targets;
    }
    TaskQuoteBatches.Result finish(Actor actor,Fresh start,java.util.function.Function<String,List<TaskQuoteBatches.Item>> write){
        var key=start.key();var id=key.task().taskId();var scope=new AgentTaskExecutionGrantService.Scope(actor.tenantId(),actor.clientId(),actor.ownerJiacn());
        return roots.executeWithLockedTaskRootInOwnerScope(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),id,root->batches.commit(key,start.intent(),batch->{
            if(!"open".equals(root.getRewardStatus())||!start.intent().expectedTaskVersion().equals(String.valueOf(root.getTaskVersion())))throw new Stale();
            var current=requirements.readCurrentWithinLockedTask(scope,id,root);var catalogue=grants.snapshotTaskInputsWithinLockedTask(scope,id);
            if(current.requirementRevision()!=start.requirementRevision()||!current.sha256().equals(start.features().requirementSha256())||!catalogue.digest().equals(start.features().inputSnapshotSha256()))throw new Stale();
            var meta=materials.observe(actor,id);if(!start.metadata().equals(meta))throw new Stale();
            var observed=features.observe(current,AgentTaskStoredLists.read(root.getRequiredAbilities()),catalogue,meta);
            if(!observed.text().sha256().equals(start.features().textSha256())||!start.candidates().equals(authorized(scope,id,start.intent().agentIds(),true)))throw new Stale();
            return write.apply(batch);
        }));
    }
}
