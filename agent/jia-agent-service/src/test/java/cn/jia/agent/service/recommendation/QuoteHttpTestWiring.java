package cn.jia.agent.service.recommendation;

import cn.jia.agent.api.IndependentTaskQuoteController;
import cn.jia.agent.service.AgentExternalServicePolicyService;
import cn.jia.agent.service.AgentTaskExecutionGrantService;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.agent.service.AgentTaskRequirementSnapshotService;
import cn.jia.agent.service.TaskFeatureEncoder;
import cn.jia.economy.task.TaskBillingService;
import cn.jia.economy.task.TaskBudgetEstimator;
import cn.jia.economy.task.TaskQuoteBatches;
import java.time.Duration;
import java.util.Optional;

/** Test-only wiring, so HTTP integration need not open production package visibility. */
public final class QuoteHttpTestWiring {
    private QuoteHttpTestWiring(){}
    public static String template(){return TaskRetrievalText.VERSION;}
    public static IndependentTaskQuoteController controller(AgentTaskMutationTransaction roots,TaskQuoteBatches batches,
            AgentTaskRequirementSnapshotService requirements,AgentTaskExecutionGrantService grants,AgentExternalServicePolicyService agents,
            RecommendationMaterials materials,TaskFeatureEncoder encoder,ProspectiveTaskExecution source,
            TaskBudgetEstimator estimator,TaskBillingService billing){
        var vectors=new TaskQueryVectors(encoder,256,8,Duration.ofMinutes(10));
        var intents=new TaskQuoteIntentService(roots,batches,requirements,grants,agents,materials,new QuoteFeaturePreparation(vectors));
        return new IndependentTaskQuoteController(new IndependentTaskQuotes(intents,Optional.ofNullable(source),estimator,billing));
    }
}
