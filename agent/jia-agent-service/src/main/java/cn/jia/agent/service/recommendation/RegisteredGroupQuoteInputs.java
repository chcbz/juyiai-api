package cn.jia.agent.service.recommendation;

import cn.jia.agent.security.RegisteredPricingProfiles;
import cn.jia.economy.pricing.*;
import cn.jia.economy.task.*;
import java.util.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.*;

/** Only scoped, pre-approved experiments read these inputs. No Runtime request or dispatch. */
@Service
final class RegisteredGroupQuoteInputs {
    private final TaskQuoteExperiment experiments;private final RegisteredPricingProfiles profiles;private final PreparedReferenceRoutes routes;private final ObjectMapper json;
    RegisteredGroupQuoteInputs(TaskQuoteExperiment experiments,RegisteredPricingProfiles profiles,PreparedReferenceRoutes routes,ObjectMapper json){this.experiments=experiments;this.profiles=profiles;this.routes=routes;this.json=json;}
    record Read(TaskQuoteIntentService.Fresh priced,Map<String,CandidateGroupOffers.Basis> bases,Map<String,RegisteredPricingProfiles.Observed> configs,boolean approved){}
    private static TaskQuoteExperiment.Subject subject(TaskQuoteIntentService.Fresh f){var x=f.facts();return new TaskQuoteExperiment.Subject(f.key().task(),f.intent().expectedTaskVersion(),x.requirementRevision(),x.requirementSha256(),x.inputSnapshotSha256());}
    Read read(TaskQuoteIntentService.Fresh f){var a=experiments.read(subject(f),f.experimentSha());if(a==null)return fixed(f);
        var x=f.facts();var facts=new QuoteFeaturePreparation.LocalFacts(x.requirementRevision(),x.requirementSha256(),x.inputSnapshotSha256(),x.files(),a.deliverables(),a.operation());
        var d=f.key().task().scope();var configs=profiles.read(d.tenantId(),d.clientId(),f.candidates(),false);var bases=new HashMap<String,CandidateGroupOffers.Basis>();
        for(var t:f.candidates())bases.put(t.agent(),classify(f.key().task(),facts,a,configs.get(t.agent())));
        return new Read(f.withFacts(facts),Map.copyOf(bases),configs,true);
    }
    static Read fixed(TaskQuoteIntentService.Fresh f){var bases=new HashMap<String,CandidateGroupOffers.Basis>();for(var t:f.candidates())bases.put(t.agent(),new CandidateGroupOffers.Missing(TaskQuoteBatches.Reason.OUT_OF_DISTRIBUTION));return new Read(f,Map.copyOf(bases),Map.of(),false);}
    private CandidateGroupOffers.Basis classify(TaskBillingService.Task task,QuoteFeaturePreparation.LocalFacts facts,TaskQuoteExperiment.Approved a,RegisteredPricingProfiles.Observed row){
        if(row!=null)for(var p:json.readTree(row.canonical())){if(!a.operation().equals(p.path("operation").asText()))continue;
            int i=a.outputContract();var c=new PreparedReferenceRoutes.Configuration("NATIVE_DISPATCH_GROUP_V1",p.path("execution"+i).asText(),p.path("referenceRouteSha256").asText(),p.path("tools"+i).asText(),p.path("capturePolicyVersion").asText());
            var terms=routes.resolve(task,c);if(terms==null)break;
            var src=terms.routes();if(!src.keySet().equals(Set.of("MODEL_RESPONSE","NATIVE_PROVIDER_CALL"))||!src.get("MODEL_RESPONSE").keySet().equals(Set.of("INFERENCE"))||!src.get("NATIVE_PROVIDER_CALL").keySet().equals(Set.of(a.operation())))break;
            if(!(src.get("MODEL_RESPONSE").get("INFERENCE") instanceof TaskReferencePricing.TokenRoute parent)||!(src.get("NATIVE_PROVIDER_CALL").get(a.operation()) instanceof TaskReferencePricing.TokenRoute child))break;
            if(!parent.model().equals(p.path("parentModel").asText())||!parent.normalizationBasis().equals(p.path("parentBasis").asText())||!child.model().equals(p.path("childModel").asText())||!child.channel().equals(p.path("childChannel").asText())||!child.tier().equals(p.path("childTier").asText())||!child.normalizationBasis().equals(p.path("childBasis").asText()))break;
            var config=new GroupMeanPricing.Configuration(p.path("contextPolicyId").asText(),p.path("contextPolicyVersion").asText(),p.path("toolPolicyVersion").asText(),c.capturePolicy(),List.of(route("MODEL_RESPONSE","INFERENCE",parent),route("NATIVE_PROVIDER_CALL",a.operation(),child)));
            return CandidateGroupOffers.classify(facts,a.policy(),config,terms.execution(),null);
        }
        return new CandidateGroupOffers.Missing(TaskQuoteBatches.Reason.BILLING_ROUTE_MISSING);
    }
    private static GroupMeanPricing.Route route(String k,String component,TaskReferencePricing.TokenRoute r){return new GroupMeanPricing.Route(k,component,r.channel(),r.model(),r.tier(),r.normalizationBasis());}
    void recheck(TaskQuoteIntentService.Fresh f,Read r){if(!r.approved())return;var d=f.key().task().scope();
        if(!experiments.current(subject(f),f.experimentSha())||!r.configs().equals(profiles.read(d.tenantId(),d.clientId(),f.candidates(),true)))throw new TaskQuoteIntentService.Stale();
    }
}
