package cn.jia.agent.service.impl;

import cn.jia.agent.service.AgentCollaborationRequestService;
import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import cn.jia.chat.service.WorkspaceConversationAccessService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/** Read-only reconciliation of historical collaboration facts, preserving conversation ACL. */
@Service
public class AgentCollaborationRequestServiceImpl implements AgentCollaborationRequestService {
    static final String DISPATCH_MODE = "RUNTIME_COMMAND_DISPATCH";
    private final WorkspaceConversationAccessService conversations;
    private final PersonalWorkspaceExecutionService executions;

    public AgentCollaborationRequestServiceImpl(WorkspaceConversationAccessService conversations,
            PersonalWorkspaceExecutionService executions) {
        this.conversations = Objects.requireNonNull(conversations, "conversations");
        this.executions = Objects.requireNonNull(executions, "executions");
    }

    @Override
    public RequestView get(OwnerScope scope, String requestId) {
        validateScope(scope);
        exact(requestId, "requestId", 100);
        try {
            return viewForExecution(scope, executions.get(
                    new PersonalWorkspaceExecutionService.OwnerScope(
                            scope.tenantId(), scope.clientId(), scope.ownerJiacn()), requestId));
        } catch (PersonalWorkspaceExecutionService.Failure failure) {
            throw map(failure);
        }
    }

    @Override
    public RequestView getByIdempotencyKey(OwnerScope scope, String idempotencyKey) {
        validateScope(scope);
        validateKey(idempotencyKey);
        try {
            return viewForExecution(scope, executions.getByIdempotencyKey(
                    new PersonalWorkspaceExecutionService.OwnerScope(
                            scope.tenantId(), scope.clientId(), scope.ownerJiacn()), idempotencyKey));
        } catch (PersonalWorkspaceExecutionService.Failure failure) {
            throw map(failure);
        }
    }

    private RequestView viewForExecution(OwnerScope scope,
            PersonalWorkspaceExecutionService.ExecutionView execution) {
        if (execution == null) throw new Failure(Reason.STORAGE_UNAVAILABLE);
        String conversationId = execution.conversationId();
        String targetAgentId = execution.targetAgentId();
        exact(conversationId, "conversationId", 100);
        exact(targetAgentId, "targetAgentId", 100);
        WorkspaceConversationAccessService.ConversationView conversation = conversation(scope, conversationId);
        requireTarget(conversation, targetAgentId);
        return view(scope, conversation, execution);
    }

    private RequestView view(OwnerScope scope, WorkspaceConversationAccessService.ConversationView conversation,
            PersonalWorkspaceExecutionService.ExecutionView execution) {
        if (execution == null
                || !conversation.conversationId().equals(execution.conversationId())
                || !conversation.targetAgentIds().contains(execution.targetAgentId())
                || !isExact(execution.executionId(), 100)
                || !isExact(execution.state(), 40)) {
            throw new Failure(Reason.STORAGE_UNAVAILABLE);
        }
        String expectedTaskId = conversation.taskId();
        if (!Objects.equals(expectedTaskId, execution.businessTaskId())) {
            throw new Failure(Reason.NOT_FOUND);
        }
        String mode = execution.executionMode();
        if ((expectedTaskId == null && !"PRIVATE".equals(mode))
                || (expectedTaskId != null && !"TASK".equals(mode))) {
            throw new Failure(Reason.NOT_FOUND);
        }
        return new RequestView(execution.executionId(), conversation.conversationId(),
                execution.targetAgentId(), conversation.scopeType(), conversation.scopeKey(),
                execution.state(), DISPATCH_MODE, mode, expectedTaskId);
    }

    private WorkspaceConversationAccessService.ConversationView conversation(OwnerScope scope,
            String conversationId) {
        try {
            WorkspaceConversationAccessService.ConversationView view = conversations.requireAccessible(
                    new WorkspaceConversationAccessService.Scope(scope.tenantId(), scope.clientId(),
                            scope.ownerJiacn()), conversationId);
            if (view == null || !conversationId.equals(view.conversationId())) {
                throw new Failure(Reason.NOT_FOUND);
            }
            return view;
        } catch (Failure failure) {
            throw failure;
        } catch (RuntimeException denied) {
            throw new Failure(Reason.NOT_FOUND, denied);
        }
    }

    private void requireTarget(WorkspaceConversationAccessService.ConversationView conversation,
            String targetAgentId) {
        if (conversation.targetAgentIds() == null || conversation.targetAgentIds().isEmpty()
                || !conversation.targetAgentIds().contains(targetAgentId)) {
            throw new Failure(Reason.NOT_FOUND);
        }
        if ("private".equals(conversation.scopeType())
                && (conversation.targetAgentIds().size() != 1
                || !targetAgentId.equals(conversation.targetAgentIds().getFirst()))) {
            throw new Failure(Reason.NOT_FOUND);
        }
    }

    private Failure map(PersonalWorkspaceExecutionService.Failure failure) {
        return switch (failure.getReason()) {
            case BAD_REQUEST -> new Failure(Reason.BAD_REQUEST, failure);
            case NOT_FOUND, GRANT_CHANGED, GRANT_REVOKED -> new Failure(Reason.NOT_FOUND, failure);
            case IDEMPOTENCY_CONFLICT -> new Failure(Reason.IDEMPOTENCY_CONFLICT, failure);
            case CAPABILITY_UNAVAILABLE -> new Failure(Reason.CAPABILITY_UNAVAILABLE, failure);
            case TASK_CONFLICT, OUTPUT_CONFLICT, OUTPUT_MISSING -> new Failure(Reason.STATE_CONFLICT, failure);
            case STORAGE_UNAVAILABLE -> new Failure(Reason.STORAGE_UNAVAILABLE, failure);
        };
    }

    private void validateScope(OwnerScope scope) {
        if (scope == null || !"0".equals(scope.tenantId()) || !isExact(scope.clientId(), 50)
                || !isExact(scope.ownerJiacn(), 50) || "0".equals(scope.ownerJiacn())) {
            throw new Failure(Reason.BAD_REQUEST);
        }
    }

    private void validateKey(String key) { exact(key, "Idempotency-Key", 100); }
    private void exact(String value, String name, int max) {
        if (!isExact(value, max)) throw new Failure(Reason.BAD_REQUEST);
    }
    private boolean isExact(String value, int max) {
        return value != null && !value.isBlank() && value.equals(value.strip())
                && value.codePointCount(0, value.length()) <= max
                && value.codePoints().noneMatch(Character::isISOControl);
    }
}
