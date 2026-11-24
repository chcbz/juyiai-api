package cn.jia.agent.skill;

import cn.jia.agent.security.AgentRuntimeAuthenticationService.RecommendationChannel;
import cn.jia.agent.service.AgentManagedSessionLookup.SessionFence;
import cn.jia.agent.service.SkillCapabilityLookup;

/** Read-only recommendation port. The ordinary capability lookup remains independent. */
public interface RecommendationCatalogLookup extends SkillCapabilityLookup {
    View forRecommendation(Scope scope, SessionFence fence, RecommendationChannel channel);
}
