package cn.jia.agent.api;

import cn.jia.agent.mapper.skill.SkillManagementMapper;
import cn.jia.agent.position.PositionSkillService;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.agent.service.PositionAuthority;
import org.springframework.stereotype.Service;
import java.util.*;
import static cn.jia.agent.position.PositionSkillException.require;

/** Match the frozen execution to the current managed installation and its domain permission. */
@Service
final class InstalledDomainAuthority {
    private final PositionSkillService positions;
    private final AgentIdentityService identities;
    private final SkillManagementMapper managed;
    InstalledDomainAuthority(PositionSkillService positions,AgentIdentityService identities,SkillManagementMapper managed) {
        this.positions=positions;this.identities=identities;this.managed=managed;
    }
    void requireInstalled(PositionAuthority.Scope scope,String agent,String installation,String host,boolean publish,List<Map<String,Object>> selections,PositionSkillService.RequiredPositionSkill required) {
        // The execution socket identifies one sandbox, not an individual process in it.
        // A pooled selection cannot prove which installation is exercising this permission.
        require(selections.size()==1 && selections.getFirst().get("skill") instanceof Map<?,?> selected
                && required.skillKey().equals(selected.get("key")),403,required.skillRequired());
        var identity=identities.requireRegistrationIdentityInScope(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),agent);
        require(agent.equals(identity.getCanonicalAgentId()) && identity.getBindingId()!=null,403,required.positionRequired());
        var ids=positions.positionSkillInstallations(scope,agent,identity.getBindingId(),installation,host,required,publish);
        var state=managed.lockState(scope.tenantId(),scope.clientId(),agent,required.skillKey());
        require(state!=null && Boolean.TRUE.equals(state.getDesiredEnabled()) && state.getPendingChangeId()==null
                && ids.contains(state.getActiveInstallationId()) && state.getActiveInstallationId().equals(((Map<?,?>)selections.getFirst().get("skill")).get("installationId")),403,required.skillRequired());
    }
}
