package cn.jia.agent.service.recommendation;

import cn.jia.agent.security.PreparedRuntimeEpochFence;
import cn.jia.agent.service.AgentExternalServicePolicyService.QuoteTarget;
import cn.jia.agent.service.AgentTaskExecutionGrantService.Scope;
import cn.jia.agent.service.NativeTaskPreparation;
import cn.jia.economy.task.PreparedReferenceRoutes;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Consumes only local prepared evidence for targets already authorized by the quote intent. No probe or dispatch. */
@Service
@ConditionalOnProperty(name="jia.agent.task-quotes.prepared-observations-enabled",havingValue="true")
public final class RecordedProspectiveTaskExecution implements ProspectiveTaskExecution {
    private final NativeTaskPreparation decisions;private final PreparedRuntimeEpochFence epochs;private final PreparedReferenceRoutes routes;
    public RecordedProspectiveTaskExecution(NativeTaskPreparation decisions,PreparedRuntimeEpochFence epochs,PreparedReferenceRoutes routes){
        this.decisions=decisions;this.epochs=epochs;this.routes=routes;
    }
    public Map<String,Evidence> observe(Inputs input,List<QuoteTarget> targets){return read(input,targets,false);}
    public boolean current(Inputs input,List<QuoteTarget> targets,Map<String,Evidence> before){
        try{return before.equals(read(input,targets,true));}catch(PreparedRuntimeEpochFence.Busy changed){return false;}
    }
    private Map<String,Evidence> read(Inputs input,List<QuoteTarget> targets,boolean locking){
        var task=input.task();var scope=new Scope(task.scope().tenantId(),task.scope().clientId(),task.ownerJiacn());long version=Long.parseLong(input.taskVersion());
        var bindings=targets.stream().map(t->new NativeTaskPreparation.Binding(scope,task.taskId(),version,input.requirementRevision(),input.requirementSha(),input.inputSha(),t)).toList();
        var receipts=decisions.recorded(scope,task.taskId(),bindings,locking);var expected=new ArrayList<PreparedRuntimeEpochFence.Epoch>();
        var available=new HashMap<String,Evidence>();
        receipts.forEach((b,r)->{var m=r.measurement();var t=b.target();
            var terms=routes.resolve(task,new PreparedReferenceRoutes.Configuration(m.version(),m.executionConfigSha256(),m.routeSha256(),m.toolConfigSha256(),m.capturePolicyVersion()));
            if(terms==null)return;
            expected.add(new PreparedRuntimeEpochFence.Epoch(t.agent(),t.provider(),t.binding(),m.fence(),m.profile()));
            // The reservation permits exactly one persisted measurement per binding and ticket; a new probe always rotates it.
            available.put(t.agent(),new Evidence(r.ticket(),terms,r.decision().sha256()));
        });
        available.keySet().retainAll(epochs.matching(scope.tenantId(),scope.clientId(),expected,locking));return Map.copyOf(available);
    }
}
