package cn.jia.chat.service;

import java.util.List;

/**
 * Chat-owned boundary exposing authoritative Juyiting conversation scope and lifecycle facts.
 * This service does not expose messages and does not grant runtime file access.
 */
public interface WorkspaceConversationAccessService {
    ConversationView requireAccessible(Scope scope, String conversationId);
    /** Holds the conversation lifecycle root in the caller transaction, before execution rows. */
    <T> T withLockedAccessible(Scope scope, String conversationId,
            java.util.function.Function<ConversationView,T> action);

    record Scope(String tenantId, String clientId, String ownerJiacn) { }

    record ConversationView(
            String conversationId,
            String scopeType,
            String scopeKey,
            String taskId,
            List<String> targetAgentIds,
            long lifecycleGeneration,
            long contextRevision) {
        public ConversationView {
            targetAgentIds = List.copyOf(targetAgentIds);
        }
    }
}
