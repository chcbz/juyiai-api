package cn.jia.chat.service;

import cn.jia.agent.service.NativeSkillScriptSessionLookup;
import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import cn.jia.agent.service.SkillCapabilityLookup;
import cn.jia.agent.service.AgentManagedSessionLookup;
import cn.jia.chat.handler.TypedDeliberationSessionRegistry;
import org.springframework.stereotype.Service;
import java.util.Objects;

/** Runtime capability evidence only. Business scope/grant checks belong to execution admission. */
@Service
public final class NativeSkillScriptSessionLookupImpl implements NativeSkillScriptSessionLookup {
    private final SkillCapabilityLookup runtimes;
    private final TypedDeliberationSessionRegistry sessions;
    public NativeSkillScriptSessionLookupImpl(SkillCapabilityLookup runtimes,TypedDeliberationSessionRegistry sessions) {
        this.runtimes=runtimes;this.sessions=sessions;
    }
    @Override public boolean supports(PersonalWorkspaceExecutionService.RuntimeScope scope,AgentManagedSessionLookup.SessionFence fence) {
        try {
            SkillCapabilityLookup.validateFence(fence);
            if(!Objects.equals(scope.runtimeInstanceId(),fence.runtimeInstanceId()))return false;
            var key=new SkillCapabilityLookup.Scope(scope.tenantId(),scope.clientId(),scope.executorOwnerJiacn(),scope.agentId());
            var live=runtimes.lookup(key,fence);
            if(live==null||live.state()!=SkillCapabilityLookup.State.AVAILABLE)return false;
            if(!sessions.nativeScriptDeclared(new TypedDeliberationSessionRegistry.Scope(scope.tenantId(),scope.executorOwnerJiacn(),scope.clientId()),
                    scope.agentId(),live.sessionId()))return false;
            return live.sameExecutionFacts(runtimes.lookup(key,fence));
        }catch(IllegalStateException|IllegalArgumentException unavailable){return false;}
    }
}
