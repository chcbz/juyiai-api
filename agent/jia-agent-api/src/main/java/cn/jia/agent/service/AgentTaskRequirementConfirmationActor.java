package cn.jia.agent.service;

/** Security boundary for explicit requirement confirmation; does not grant task access. */
public interface AgentTaskRequirementConfirmationActor {
    Owner authenticatedOwner();

    record Owner(String ownerJiacn, String clientId) {
        public Owner {
            if (ownerJiacn == null || ownerJiacn.isBlank() || clientId == null || clientId.isBlank())
                throw new IllegalArgumentException("Authenticated task owner required for re-confirmation");
        }
    }
}
