package cn.jia.chat.service;

import cn.jia.agent.service.NativeSkillScriptSessionLookup;
import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import cn.jia.agent.service.SkillCapabilityLookup;
import cn.jia.agent.service.AgentManagedSessionLookup;
import cn.jia.agent.security.AgentRuntimeAuthenticationService;
import cn.jia.chat.handler.TypedDeliberationSessionRegistry;
import org.springframework.stereotype.Service;
import java.util.Objects;

/** Runtime capability evidence only. Business scope/grant checks belong to execution admission. */
@Service
public final class NativeSkillScriptSessionLookupImpl implements NativeSkillScriptSessionLookup {
    private final AgentRuntimeAuthenticationService authentication;
    private final SkillCapabilityLookup runtimes;
    private final TypedDeliberationSessionRegistry sessions;
    public NativeSkillScriptSessionLookupImpl(AgentRuntimeAuthenticationService authentication,SkillCapabilityLookup runtimes,TypedDeliberationSessionRegistry sessions) {
        this.authentication=authentication;this.runtimes=runtimes;this.sessions=sessions;
    }
    @Override public boolean supports(PersonalWorkspaceExecutionService.RuntimeScope scope) {
        try {
            var proof=authentication.currentRegisteredProof(scope.tenantId(),scope.clientId(),scope.agentId());
            if(proof==null||!Objects.equals(proof.scope().tenantId(),scope.tenantId())
                    ||!Objects.equals(proof.scope().clientId(),scope.clientId())
                    ||!Objects.equals(proof.scope().agentId(),scope.agentId())
                    ||!Objects.equals(proof.scope().ownerJiacn(),scope.executorOwnerJiacn())
                    ||!Objects.equals(proof.scope().runtimeInstanceId(),scope.runtimeInstanceId()))return false;
            var key=new SkillCapabilityLookup.Scope(scope.tenantId(),scope.clientId(),scope.executorOwnerJiacn(),scope.agentId());
            var fence=new AgentManagedSessionLookup.SessionFence(proof.installationId(),proof.hostId(),scope.runtimeInstanceId(),proof.sessionGeneration());
            var live=runtimes.lookup(key,fence);
            if(live==null||live.state()!=SkillCapabilityLookup.State.AVAILABLE)return false;
            if(!sessions.nativeScriptDeclared(new TypedDeliberationSessionRegistry.Scope(scope.tenantId(),scope.executorOwnerJiacn(),scope.clientId()),
                    scope.agentId(),live.sessionId()))return false;
            return proof.equals(authentication.currentRegisteredProof(scope.tenantId(),scope.clientId(),scope.agentId()))
                    &&live.sameExecutionFacts(runtimes.lookup(key,fence));
        }catch(IllegalStateException|IllegalArgumentException unavailable){return false;}
    }
}
