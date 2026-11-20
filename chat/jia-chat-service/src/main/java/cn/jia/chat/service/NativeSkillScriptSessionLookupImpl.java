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
        return declared(scope,fence,"NATIVE_SKILL_SCRIPT_V1");
    }
    @Override public boolean supportsPrivateTools(PersonalWorkspaceExecutionService.RuntimeScope scope,AgentManagedSessionLookup.SessionFence fence) {
        return declared(scope,fence,null);
    }
    @Override public boolean supportsDomain(PersonalWorkspaceExecutionService.RuntimeScope scope,AgentManagedSessionLookup.SessionFence fence){return declared(scope,fence,"NATIVE_DOMAIN_API_V1");}
    private boolean declared(PersonalWorkspaceExecutionService.RuntimeScope scope,AgentManagedSessionLookup.SessionFence fence,String policy) {
        try {
            SkillCapabilityLookup.validateFence(fence);
            if(!Objects.equals(scope.runtimeInstanceId(),fence.runtimeInstanceId()))return false;
            var key=new SkillCapabilityLookup.Scope(scope.tenantId(),scope.clientId(),scope.executorOwnerJiacn(),scope.agentId());
            var live=runtimes.lookup(key,fence);
            if(live==null||live.state()!=SkillCapabilityLookup.State.AVAILABLE)return false;
            var socketScope=new TypedDeliberationSessionRegistry.Scope(scope.tenantId(),scope.executorOwnerJiacn(),scope.clientId());
            boolean supported=policy==null ? sessions.privateBusinessToolsDeclared(socketScope,scope.agentId(),live.sessionId())
                    : "NATIVE_SKILL_SCRIPT_V1".equals(policy)?sessions.nativeScriptDeclared(socketScope,scope.agentId(),live.sessionId()):sessions.nativePolicyDeclared(socketScope,scope.agentId(),live.sessionId(),policy);
            if (!supported) return false;
            return live.sameExecutionFacts(runtimes.lookup(key,fence));
        }catch(IllegalStateException|IllegalArgumentException unavailable){return false;}
    }
}
