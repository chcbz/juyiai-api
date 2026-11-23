package cn.jia.agent.service.recommendation;

import cn.jia.agent.entity.AgentTaskDTO;
import cn.jia.agent.hosting.HostingRentHttp.Actor;
import cn.jia.agent.service.AgentTaskExecutionGrantService.InputCatalogue;
import cn.jia.agent.service.AgentTaskRequirementSnapshotService.CurrentSnapshot;
import cn.jia.agent.service.TaskFeatureEncoder;
import cn.jia.economy.pricing.TaskEstimateFeatures;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;

/** One shared encode per authorized quote intent, regardless of candidate count. No dependency on recommendation results. */
@Service
final class QuoteFeaturePreparation {
    private final TaskQueryVectors vectors;
    QuoteFeaturePreparation(TaskQueryVectors vectors){this.vectors=vectors;}
    record Input(long requirementRevision,String requirementSha256,InputCatalogue catalogue,TaskRetrievalText text){@Override public String toString(){return "QuoteFeatureInput[private]";}}
    /** Caller supplies one coherent, authorized pre-quote snapshot. Use confirmed requirement, never a mutable plan fallback. */
    Input observe(CurrentSnapshot requirement,List<String> abilities,InputCatalogue catalogue,List<RecommendationMaterials.Observation> metadata){
        record Version(String file,int version){}
        var observed=new HashMap<Version,RecommendationMaterials.Observation>();
        for(var m:metadata){if(!"ACTIVE".equals(m.material().fileState()))throw new TaskQuoteIntentService.Stale();
            observed.merge(new Version(m.fileId(),m.material().version()),m,(one,two)->{
                if(one.metadataRevision()!=two.metadataRevision() || one.byteLength()!=two.byteLength()
                    || !Objects.equals(one.sha256(),two.sha256()) || !Objects.equals(one.material().contentMimeType(),two.material().contentMimeType()))
                    throw new TaskQuoteIntentService.Stale();
                return "INPUT".equals(one.material().role())?one:two;
            });}
        if(observed.size()!=catalogue.inputs().size())throw new TaskQuoteIntentService.Stale();
        for(var i:catalogue.inputs()){var m=observed.get(new Version(i.fileId(),i.version()));
            if(m==null||!i.contentHash().equals(m.sha256())||i.byteLength()!=m.byteLength()||!i.contentMimeType().equals(m.material().contentMimeType())||!i.purpose().equals(m.material().role()))throw new TaskQuoteIntentService.Stale();}
        var task=new AgentTaskDTO();task.setTitle(requirement.title());task.setDescription(requirement.description());task.setRequiredAbilities(abilities);
        return new Input(requirement.requirementRevision(),requirement.sha256(),catalogue,TaskRetrievalText.from(task,metadata));
    }
    TaskEstimateFeatures prepare(Actor actor,Input input){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Quote encoding must be outside task transaction");
        var encoded=vectors.encode(actor,input.text()).block();if(encoded==null||encoded.identity().dimensions()!=1024)throw new TaskFeatureEncoder.InvalidResult();
        var id=encoded.identity();var values=new ArrayList<Float>(id.dimensions());for(float v:encoded.values())values.add(v);
        var files=input.catalogue().inputs().stream().map(i->new TaskEstimateFeatures.File(i.purpose(),i.contentMimeType(),i.byteLength(),i.version())).toList();
        // The current task form has no structured requested-deliverables count. Do not infer one from text or material count.
        return new TaskEstimateFeatures(TaskEstimateFeatures.VERSION,TaskRetrievalText.VERSION,input.requirementSha256(),input.catalogue().digest(),input.text().sha256(),
            new TaskEstimateFeatures.Identity(id.providerConfigVersion(),id.model(),id.dimensions()),values,files,null);
    }
}
