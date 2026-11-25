package cn.jia.agent.service;

/** Non-executing read port. A marker is not authority: the registering transport must
 * authenticate its origin and exact local registration before any evidence is reusable. */
public interface RecommendationCatalogLookup extends SkillCapabilityLookup {
    interface Observation { }
    View forRecommendation(Scope scope, AgentManagedSessionLookup.SessionFence fence, Observation observation);
}
