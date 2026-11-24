package cn.jia.agent.dao;

import cn.jia.agent.entity.PersonalWorkspaceExecutionEntity;
import cn.jia.agent.entity.PersonalWorkspaceExecutionInputEntity;
import cn.jia.agent.entity.PersonalWorkspaceExecutionOutputEntity;

import java.util.List;

/** Exact owner-scoped persistence boundary for private execution grants and output receipts. */
public interface PersonalWorkspaceExecutionDao extends TaskExecutionClosureDao {
    /** Current reads under the task root; immutable terminal executions are not rewritten. */
    List<PersonalWorkspaceExecutionEntity> findUnfinishedTaskExecutions(
            String tenantId, String clientId, String ownerJiacn, String taskId);
    boolean cancelLockedExecution(PersonalWorkspaceExecutionEntity execution, long now);

    PersonalWorkspaceExecutionEntity findForRuntime(String tenantId,String clientId,String executorOwner,
            String agentId,String executionId,String runId);

    String admittedCaptureProfile(String tenant,String client,String owner,String task,String agent,String execution,String run);

    /** Historical native cost authority, not active execution admission. */
    PersonalWorkspaceExecutionEntity findForNativeUsageCost(String tenantId, String clientId, String executorOwner,
            String agentId, String executionId, String runId);

    PersonalWorkspaceExecutionEntity find(String tenantId, String clientId, String ownerJiacn,
            String executionId);
    PersonalWorkspaceExecutionEntity findByIdempotency(String tenantId, String clientId,
            String ownerJiacn, String idempotencyKey);
    /** Browser-safe persisted create reconciliation; excludes instruction and runtime credentials. */
    PersonalWorkspaceExecutionEntity findRequestByIdempotency(String tenantId, String clientId,
            String ownerJiacn, String idempotencyKey);
    /** Bounded keyset history query. The limit is the caller's lookahead size (maximum 101). */
    List<PersonalWorkspaceExecutionEntity> listHistory(String tenantId, String clientId,
            String ownerJiacn, Long beforeCreatedAt, String beforeExecutionId, int limit);
    PersonalWorkspaceExecutionEntity findByRevokeIdempotency(String tenantId, String clientId,
            String ownerJiacn, String idempotencyKey);
    PersonalWorkspaceExecutionEntity lock(String tenantId, String clientId, String ownerJiacn,
            String executionId);
    PersonalWorkspaceExecutionEntity findByTaskRun(String tenantId, String clientId, String ownerJiacn,
            String taskId, String runId);
    PersonalWorkspaceExecutionEntity lockByTaskRun(String tenantId, String clientId, String ownerJiacn,
            String taskId, String runId);
    List<PersonalWorkspaceExecutionEntity> listQueuedConversationsByTarget(String tenantId,
            String clientId, String ownerJiacn, String targetAgentId,
            Long afterCreatedAt, String afterExecutionId, int limit);
    /** True only when the follow-up-v3 execution protocol column is present in the current catalog. */
    boolean hasExecutionProtocolVersionColumn();
    void registerTaskMember(PersonalWorkspaceExecutionEntity execution,String captureId);
    record MemberIdentity(String tenantId,String clientId,String ownerJiacn,String taskId,
                          String executionId,String runId,String requestHash) { }
    boolean hasMemberCapture(MemberIdentity member,String captureId);
    void appendMember(MemberIdentity member,String captureId);
    void insert(PersonalWorkspaceExecutionEntity execution);
    void update(PersonalWorkspaceExecutionEntity execution);
    void insertInput(PersonalWorkspaceExecutionInputEntity input);
    List<PersonalWorkspaceExecutionInputEntity> listInputs(String tenantId, String clientId,
            String ownerJiacn, String executionId);
    PersonalWorkspaceExecutionInputEntity findInput(String tenantId, String clientId,
            String ownerJiacn, String executionId, String inputRef);
    PersonalWorkspaceExecutionInputEntity lockInput(String tenantId, String clientId,
            String ownerJiacn, String executionId, String inputRef);
    List<String> listActiveExecutionIdsByFile(String tenantId, String clientId,
            String ownerJiacn, String fileId, int limit);
    void updateInput(PersonalWorkspaceExecutionInputEntity input);
    /** Immutable output preparation without retaining a row lock across private storage I/O.
     * Mutating consumers must revalidate the complete tuple under their original root locks. */
    PersonalWorkspaceExecutionOutputEntity findOutput(String tenantId, String clientId,
            String ownerJiacn, String executionId, String outputId);
    PersonalWorkspaceExecutionOutputEntity lockOutput(String tenantId, String clientId,
            String ownerJiacn, String executionId, String outputId);
    List<PersonalWorkspaceExecutionOutputEntity> lockOutputs(String tenantId, String clientId,
            String ownerJiacn, String executionId);
    /** Exact immutable, already-published TASK output eligible as a rework source. */
    PersonalWorkspaceExecutionOutputEntity findPublishedReworkSource(String tenantId,
            String clientId, String ownerJiacn, String taskId, String formalDeliveryId,
            String outputId, String workspaceFileId, int workspaceFileVersion);
    void insertOutput(PersonalWorkspaceExecutionOutputEntity output);
    void updateOutput(PersonalWorkspaceExecutionOutputEntity output);
}
