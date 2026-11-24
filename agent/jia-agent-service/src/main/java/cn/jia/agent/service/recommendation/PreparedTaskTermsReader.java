package cn.jia.agent.service.recommendation;

import cn.jia.agent.security.AgentRuntimeAuthenticationService;
import cn.jia.agent.service.*;
import cn.jia.economy.service.EconomyScope;
import cn.jia.economy.task.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Background comparison, never a quote transaction or a grant. No evidence is made current merely by returning here. */
@Service
public class PreparedTaskTermsReader {
    private final NativeTaskPreparation decisions;private final AgentRuntimeAuthenticationService sessions;
    private final ObjectProvider<NativeTaskConfigurationProbe> transport;private final PreparedReferenceRoutes routes;
    private final cn.jia.agent.security.PreparedRuntimeEpochFence epochs;
    public PreparedTaskTermsReader(NativeTaskPreparation decisions,AgentRuntimeAuthenticationService sessions,ObjectProvider<NativeTaskConfigurationProbe> transport,PreparedReferenceRoutes routes,cn.jia.agent.security.PreparedRuntimeEpochFence epochs){this.epochs=epochs;
        this.decisions=decisions;this.sessions=sessions;this.transport=transport;this.routes=routes;
    }
    public record Observation(String ticket,NativeTaskPreparation.Frozen decision,NativeTaskConfigurationProbe.Measurement measurement,TaskBudgetEstimator.Terms reference) {}
    /** During quote finalization, after task inputs/targets were independently reauthorized. This guards the exact stored
     * measurements and server session epochs. Not sufficient by itself to attest the current in-session toolchain. */
    @Transactional(propagation=Propagation.MANDATORY)
    public boolean lockRecorded(AgentTaskExecutionGrantService.Scope scope,String task,Map<NativeTaskPreparation.Binding,Observation> selected){
        var expected=new ArrayList<cn.jia.agent.security.PreparedRuntimeEpochFence.Epoch>();
        var ordered=new TreeMap<String,Observation>();var subject=new TaskBillingService.Task(new EconomyScope(scope.tenantId(),scope.clientId()),scope.ownerJiacn(),task);
        for(var e:selected.entrySet()){
            var b=e.getKey();var o=e.getValue();var m=o.measurement();var t=b.target();
            if(!scope.equals(b.scope())||!task.equals(b.task())||!b.equals(o.decision().intent().binding())||!o.decision().sha256().equals(m.intentSha256())||ordered.putIfAbsent(t.agent(),o)!=null)return false;
            var now=routes.resolve(subject,new PreparedReferenceRoutes.Configuration(m.version(),m.executionConfigSha256(),m.routeSha256(),m.toolConfigSha256(),m.capturePolicyVersion()));
            if(now==null||!now.equals(o.reference()))return false;
            expected.add(new cn.jia.agent.security.PreparedRuntimeEpochFence.Epoch(t.agent(),t.provider(),t.binding(),m.fence(),m.profile()));
        }
        if(!epochs.lock(scope.tenantId(),scope.clientId(),expected))return false;
        for(var o:ordered.values())if(!decisions.lockMeasurement(o.ticket(),o.decision(),o.measurement()))return false;
        return true;
    }
    /** Only explicitly chosen candidates. No socket send under the task root; authentication proof never leaves Agent. */
    @Transactional(propagation=Propagation.NEVER)
    public Map<String,Observation> refresh(AgentTaskExecutionGrantService.Scope scope,String task,long version,List<String> candidates){
        var reservation=decisions.reserveComparisons(scope,task,version,candidates);var selected=reservation.decisions();if(selected.isEmpty())return Map.of();
        var channel=transport.getIfAvailable();if(channel==null)return Map.of();
        var current=sessions.currentRegisteredProofs(scope.tenantId(),scope.clientId(),selected.keySet());
        var result=new HashMap<String,CompletionStage<Observation>>();
        var subject=new TaskBillingService.Task(new EconomyScope(scope.tenantId(),scope.clientId()),scope.ownerJiacn(),task);
        selected.forEach((agent,decision)->{
            var p=current.get(agent);var target=decision.intent().binding().target();
            if(p==null||!target.provider().equals(p.scope().ownerJiacn())||!agent.equals(p.scope().agentId())||!scope.tenantId().equals(p.scope().tenantId())||!scope.clientId().equals(p.scope().clientId()))return;
            var fence=new AgentManagedSessionLookup.SessionFence(p.installationId(),p.hostId(),p.scope().runtimeInstanceId(),p.sessionGeneration());
            result.put(agent,channel.measure(decision,fence).thenApply(m->{
                if(m==null||!fence.equals(m.fence())||!decision.sha256().equals(m.intentSha256()))return null;
                var terms=routes.resolve(subject,new PreparedReferenceRoutes.Configuration(m.version(),m.executionConfigSha256(),m.routeSha256(),m.toolConfigSha256(),m.capturePolicyVersion()));
                return terms==null?null:new Observation(reservation.ticket(),decision,m,terms);
            }));
        });
        // Join on the invoking background worker. No database write or block on a socket callback thread.
        var published=new HashMap<String,Observation>();
        result.forEach((agent,pending)->{var observed=pending.toCompletableFuture().join();
            if(observed!=null&&decisions.recordMeasurement(reservation,agent,observed.measurement()))published.put(agent,observed);
        });return Map.copyOf(published);
    }
}
