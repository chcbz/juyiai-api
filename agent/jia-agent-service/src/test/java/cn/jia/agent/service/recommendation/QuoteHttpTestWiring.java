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
    public static IndependentTaskQuoteController structured(AgentTaskMutationTransaction roots,TaskQuoteBatches batches,
            AgentTaskRequirementSnapshotService requirements,AgentTaskExecutionGrantService grants,AgentExternalServicePolicyService agents,
            RecommendationMaterials materials,TaskBillingService billing,cn.jia.economy.mapper.GroupBaselineMapper db,cn.jia.agent.security.RegisteredPricingProfiles profiles,cn.jia.economy.task.PreparedReferenceRoutes routes,io.micrometer.observation.ObservationRegistry observations){
        var json=new tools.jackson.databind.ObjectMapper();return new IndependentTaskQuoteController(new IndependentTaskQuotes(new TaskQuoteIntentService(roots,batches,requirements,grants,agents,materials),new CandidateGroupOffers(new cn.jia.economy.service.impl.GroupBudgetQuotesService(db),billing),new RegisteredGroupQuoteInputs(new cn.jia.economy.service.impl.TaskQuoteExperimentReader(db,json),profiles,routes,json),org.mockito.Mockito.mock(ScopedGroupQuoteInputs.class),Optional.of(observations)));
    }
    /** Actual scoped application chain; only authoritative upstream observations are fixture ports. */
    public static IndependentTaskQuoteController scoped(AgentTaskMutationTransaction roots,TaskQuoteBatches batches,
            AgentTaskRequirementSnapshotService requirements,AgentTaskExecutionGrantService grants,AgentExternalServicePolicyService agents,
            RecommendationMaterials materials,TaskBillingService billing,cn.jia.agent.security.RegisteredPricingProfiles profiles,
            cn.jia.agent.skill.PublishedCapabilityQuoteSpecs specs,cn.jia.economy.pricing.CostRouteBindings routes,
            cn.jia.economy.pricing.ScopedGroupPolicies policies,cn.jia.economy.task.GroupBudgetQuotes baseline){
        return new IndependentTaskQuoteController(new IndependentTaskQuotes(new TaskQuoteIntentService(roots,batches,requirements,grants,agents,materials),
                new CandidateGroupOffers(baseline,billing),org.mockito.Mockito.mock(RegisteredGroupQuoteInputs.class),
                new ScopedGroupQuoteInputs(profiles,specs,new ResolvedCostProfiles(routes),policies)));
    }
    public static IndependentTaskQuoteController controller(AgentTaskMutationTransaction roots,TaskQuoteBatches batches,
            AgentTaskRequirementSnapshotService requirements,AgentTaskExecutionGrantService grants,AgentExternalServicePolicyService agents,
            RecommendationMaterials materials,TaskBillingService billing){
        var intents=new TaskQuoteIntentService(roots,batches,requirements,grants,agents,materials);
        return new IndependentTaskQuoteController(new IndependentTaskQuotes(intents,new CandidateGroupOffers(org.mockito.Mockito.mock(cn.jia.economy.task.GroupBudgetQuotes.class),billing),org.mockito.Mockito.mock(RegisteredGroupQuoteInputs.class),org.mockito.Mockito.mock(ScopedGroupQuoteInputs.class)));
    }
}
