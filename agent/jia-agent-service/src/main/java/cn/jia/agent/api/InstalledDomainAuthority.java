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
        var identity=identities.requireRegistrationIdentityInScope(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),agent);
        require(agent.equals(identity.getCanonicalAgentId()) && identity.getBindingId()!=null,403,required.positionRequired());
        var ids=positions.positionSkillInstallations(scope,agent,identity.getBindingId(),installation,host,required,publish);
        var state=managed.lockState(scope.tenantId(),scope.clientId(),agent,required.skillKey());
        require(state!=null && Boolean.TRUE.equals(state.getDesiredEnabled()) && state.getPendingChangeId()==null
                && ids.contains(state.getActiveInstallationId()) && selections.stream().map(s->(Map<?,?>)s.get("skill"))
                .anyMatch(s->required.skillKey().equals(s.get("key")) && state.getActiveInstallationId().equals(s.get("installationId"))),403,required.skillRequired());
    }
}
