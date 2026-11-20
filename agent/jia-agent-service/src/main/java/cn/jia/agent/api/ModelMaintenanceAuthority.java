package cn.jia.agent.api;

import cn.jia.agent.mapper.skill.SkillManagementMapper;
import cn.jia.agent.position.PositionSkillService;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.agent.service.PositionAuthority;
import org.springframework.stereotype.Service;
import java.util.*;
import static cn.jia.agent.position.PositionSkillException.require;

/** Domain permission shared by HTTP resources and existing frozen calls. */
@Service
final class ModelMaintenanceAuthority {
    static final PositionSkillService.RequiredPositionSkill REQUIRED=new PositionSkillService.RequiredPositionSkill(
            cn.jia.agent.position.PositionSkillPolicy.PLATFORM_MODELS,"model-maintenance","MODEL_POSITION_REQUIRED","MODEL_SKILL_REQUIRED");
    private final PositionSkillService positions;
    private final AgentIdentityService identities;
    private final SkillManagementMapper managed;
    ModelMaintenanceAuthority(PositionSkillService positions,AgentIdentityService identities,SkillManagementMapper managed) {
        this.positions=positions;this.identities=identities;this.managed=managed;
    }
    void requireInstalled(PositionAuthority.Scope scope,String agent,String installation,String host,boolean price,List<Map<String,Object>> selections) {
        var ids=admit(scope,agent,installation,host,price);
        var state=managed.lockState(scope.tenantId(),scope.clientId(),agent,REQUIRED.skillKey());
        require(state!=null && Boolean.TRUE.equals(state.getDesiredEnabled()) && state.getPendingChangeId()==null
                && ids.contains(state.getActiveInstallationId()) && selections.stream().map(s->(Map<?,?>)s.get("skill"))
                .anyMatch(s->REQUIRED.skillKey().equals(s.get("key")) && state.getActiveInstallationId().equals(s.get("installationId"))),403,"MODEL_SKILL_REQUIRED");
    }
    Set<String> admit(PositionAuthority.Scope scope,String agent,String installation,String host,boolean price) {
        var identity=identities.requireRegistrationIdentityInScope(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),agent);
        require(agent.equals(identity.getCanonicalAgentId()) && identity.getBindingId()!=null,403,"MODEL_POSITION_REQUIRED");
        return positions.positionSkillInstallations(scope,agent,identity.getBindingId(),installation,host,REQUIRED,price);
    }
}
