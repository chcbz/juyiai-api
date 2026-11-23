package cn.jia.agent.service.recommendation;

import cn.jia.agent.common.AgentTaskStoredLists;
import cn.jia.agent.entity.AgentTaskAssigneeCandidateDTO;
import cn.jia.agent.hosting.HostingRentHttp.Actor;
import cn.jia.agent.service.AgentService;
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
                 TaskEstimateFeatures features,List<AgentTaskAssigneeCandidateDTO> candidates) implements Start {}
    private record Read(TaskQuoteBatches.Result replay,AgentTaskRequirementSnapshotService.CurrentSnapshot requirement,
                        List<String> abilities,AgentTaskExecutionGrantService.InputCatalogue catalogue){}
    private final AgentTaskMutationTransaction roots;private final TaskQuoteBatches batches;private final AgentTaskRequirementSnapshotService requirements;
    private final AgentTaskExecutionGrantService grants;private final AgentService agents;private final RecommendationMaterials materials;private final QuoteFeaturePreparation features;
    TaskQuoteIntentService(AgentTaskMutationTransaction roots,TaskQuoteBatches batches,AgentTaskRequirementSnapshotService requirements,AgentTaskExecutionGrantService grants,
                           AgentService agents,RecommendationMaterials materials,QuoteFeaturePreparation features){this.roots=roots;this.batches=batches;this.requirements=requirements;this.grants=grants;this.agents=agents;this.materials=materials;this.features=features;}
    Start begin(Actor actor,String task,String idempotencyKey,TaskQuoteBatches.Intent intent){
        var key=new TaskQuoteBatches.Key(new TaskBillingService.Task(actor.scope(),actor.ownerJiacn(),task),actor.actorId(),idempotencyKey);
        var scope=new AgentTaskExecutionGrantService.Scope(actor.tenantId(),actor.clientId(),actor.ownerJiacn());
        var read=roots.executeWithLockedTaskRootInOwnerScope(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),task,root->{
            var stored=batches.replay(key,intent);if(stored!=null)return new Read(stored,null,null,null);
            if(!"open".equals(root.getRewardStatus())||!intent.expectedTaskVersion().equals(String.valueOf(root.getTaskVersion())))throw new Stale();
            return new Read(null,requirements.readCurrent(scope,task),AgentTaskStoredLists.read(root.getRequiredAbilities()),grants.snapshotTaskInputsWithinLockedTask(scope,task));
        });
        if(read.replay()!=null)return new Replay(read.replay());
        var selected=authorized(task,intent.agentIds());var input=features.observe(read.requirement(),read.abilities(),read.catalogue(),materials.observe(actor,task));
        return new Fresh(key,intent,input.requirementRevision(),features.prepare(actor,input),selected);
    }
    List<AgentTaskAssigneeCandidateDTO> authorized(String task,List<String> ids){
        var rows=new HashMap<String,AgentTaskAssigneeCandidateDTO>();for(var c:agents.listTaskAssigneeCandidates(task))rows.put(c.target().agentId(),c);
        return ids.stream().map(id->{var row=rows.get(id);if(row==null)throw new CandidateNotAuthorized();return row;}).toList();
    }
}
