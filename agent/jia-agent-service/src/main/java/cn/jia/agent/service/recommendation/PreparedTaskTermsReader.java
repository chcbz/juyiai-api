package cn.jia.agent.service.recommendation;

import cn.jia.agent.security.AgentRuntimeAuthenticationService;
import cn.jia.agent.service.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Background comparison, never a quote transaction or a grant. No evidence is made current merely by returning here. */
@Service
public class PreparedTaskTermsReader {
    private final NativeTaskPreparation decisions;private final AgentRuntimeAuthenticationService sessions;
    private final ObjectProvider<NativeTaskConfigurationProbe> transport;
    public PreparedTaskTermsReader(NativeTaskPreparation decisions,AgentRuntimeAuthenticationService sessions,ObjectProvider<NativeTaskConfigurationProbe> transport){
        this.decisions=decisions;this.sessions=sessions;this.transport=transport;
    }
    @Transactional(propagation=Propagation.NEVER)
    public void refreshDue(Set<NativeTaskPreparation.Binding> bindings){
        if(bindings.isEmpty())return;var b=bindings.iterator().next();compare(b.scope(),decisions.reserveDue(b,bindings));
    }
    private void compare(AgentTaskExecutionGrantService.Scope scope,NativeTaskPreparation.Comparison reservation){var selected=reservation.decisions();if(selected.isEmpty())return;
        var channel=transport.getIfAvailable();if(channel==null)return;
        var current=sessions.currentRegisteredProofs(scope.tenantId(),scope.clientId(),selected.keySet());
        var result=new HashMap<String,CompletionStage<NativeTaskConfigurationProbe.Measurement>>();
        selected.forEach((agent,decision)->{
            var p=current.get(agent);var target=decision.intent().binding().target();
            if(p==null||!target.provider().equals(p.scope().ownerJiacn())||!agent.equals(p.scope().agentId())||!scope.tenantId().equals(p.scope().tenantId())||!scope.clientId().equals(p.scope().clientId()))return;
            var fence=new AgentManagedSessionLookup.SessionFence(p.installationId(),p.hostId(),p.scope().runtimeInstanceId(),p.sessionGeneration());
            result.put(agent,channel.measure(decision,fence).thenApply(m->m!=null&&fence.equals(m.fence())&&decision.sha256().equals(m.intentSha256())?m:null));
        });
        // A valid configuration is an Agent fact even without an Economy reference route.
        // Persist on this background worker, never on the socket callback.
        result.forEach((agent,pending)->{
            NativeTaskConfigurationProbe.Measurement observed;
            try{observed=pending.toCompletableFuture().join();}catch(CompletionException|CancellationException unavailable){return;}
            if(observed!=null)decisions.recordMeasurement(reservation,agent,observed);
        });
    }
}
