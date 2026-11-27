package cn.jia.agent.service;

import cn.jia.agent.requirement.ConfirmedScope;

/** Owner-scoped current requirements and immutable business snapshots. Not an audit log. */
public interface AgentTaskRequirementSnapshotService {
    record Snapshot(String tenantId, String clientId, String ownerJiacn, String taskId,
            long revision, String title, String description, String sha256, String source,
            ConfirmedScope confirmedScope) {
        public Snapshot(String tenantId, String clientId, String ownerJiacn, String taskId,
                long revision, String title, String description, String sha256, String source) {
            this(tenantId,clientId,ownerJiacn,taskId,revision,title,description,sha256,source,null);
        }
    }

    /** Owner-safe current projection captured while the task root lock is held. */
    record CurrentSnapshot(String taskId, long taskVersion, long requirementRevision,
            String title, String description, String sha256, String source, ConfirmedScope confirmedScope) {
        public CurrentSnapshot(String taskId,long taskVersion,long requirementRevision,
                String title,String description,String sha256,String source) {
            this(taskId,taskVersion,requirementRevision,title,description,sha256,source,null);
        }
    }

    record ConfirmScopeCommand(String title, String description, ConfirmedScope.Payload payload) {
        @Override public String toString() { return "ConfirmScopeCommand[private]"; }
    }

    /** Internal confirmed-result producer only; not a browser confirmation flag or pricing API. */
    Snapshot confirmScope(AgentTaskExecutionGrantService.Scope scope,String taskId,
            long expectedTaskVersion,String confirmationId,ConfirmScopeCommand command);

    /** Caller owns the task-root lock; missing scope is not a zero/one-WEN quote input. */
    ConfirmedScope readConfirmedWithinLockedTask(AgentTaskExecutionGrantService.Scope scope,String taskId,
            cn.jia.agent.entity.AgentTaskMetaEntity lockedRoot);

    /** Caller holds the newly-created owner-scoped task root inside its creation transaction. */
    Snapshot captureOnCreate(AgentTaskExecutionGrantService.Scope scope, String taskId,
            String originalTitle, String originalDescription);

    /** Explicit authenticated owner re-confirmation, not inference from an old task plan. */
    Snapshot reconfirm(AgentTaskExecutionGrantService.Scope scope, String taskId,
            long expectedTaskVersion, String confirmationId, String confirmedTitle, String confirmedDescription);

    /** Exact immutable revision only; never falls back to the current task plan. */
    Snapshot read(AgentTaskExecutionGrantService.Scope scope, String taskId, long revision);

    /**
     * Locks the exact owner-scoped task root and reads its single current requirement row,
     * and returns one read-only observation. It never captures or re-confirms history.
     */
    CurrentSnapshot readCurrent(AgentTaskExecutionGrantService.Scope scope, String taskId);

    /** Internal: caller holds the exact owner-scoped task root in the current transaction.
     * Pass that callback's root, never a browser value or a detached prior observation. */
    CurrentSnapshot readCurrentWithinLockedTask(AgentTaskExecutionGrantService.Scope scope,String taskId,
            cn.jia.agent.entity.AgentTaskMetaEntity lockedRoot);

    /** Mutable current-state replacement; caller holds the task root and authorizes the edit. */
    Snapshot updateCurrent(AgentTaskExecutionGrantService.Scope scope,String taskId,long expectedRevision,
            String title,String description);

    /** Freeze an exact business input within the assignment transaction, never on an ordinary read. */
    Snapshot freezeCurrent(AgentTaskExecutionGrantService.Scope scope,String taskId,long revision);

    /** Read current state under task-root lock; rejects stale client revisions, never freezes. */
    Snapshot requireCurrent(AgentTaskExecutionGrantService.Scope scope, String taskId, long revision);
}
