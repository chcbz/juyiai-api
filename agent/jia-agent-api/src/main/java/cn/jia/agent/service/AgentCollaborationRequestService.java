package cn.jia.agent.service;

import java.util.List;

/** Read-only reconciliation of historical collaboration facts, preserving conversation ACL. */
public interface AgentCollaborationRequestService {
    RequestView get(OwnerScope scope, String requestId);
    RequestView getByIdempotencyKey(OwnerScope scope, String idempotencyKey);

    record OwnerScope(String tenantId, String clientId, String ownerJiacn) { }
    record RequestView(String requestId, String conversationId, String targetAgentId,
            String conversationScopeType, String conversationScopeKey,
            String state, String dispatchMode, String executionMode, String businessTaskId) { }

    final class Failure extends RuntimeException {
        private final Reason reason;
        public Failure(Reason reason) { super(reason.name()); this.reason = reason; }
        public Failure(Reason reason, Throwable cause) { super(reason.name(), cause); this.reason = reason; }
        public Reason reason() { return reason; }
    }
    enum Reason {
        BAD_REQUEST,
        NOT_FOUND,
        IDEMPOTENCY_CONFLICT,
        CAPABILITY_UNAVAILABLE,
        STATE_CONFLICT,
        STORAGE_UNAVAILABLE
    }
}
