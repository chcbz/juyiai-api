package cn.jia.agent.dao.impl;

import cn.jia.agent.dao.PersonalWorkspaceExecutionDao;
import cn.jia.agent.entity.PersonalWorkspaceExecutionEntity;
import cn.jia.agent.entity.PersonalWorkspaceExecutionInputEntity;
import cn.jia.agent.entity.PersonalWorkspaceExecutionOutputEntity;
import cn.jia.agent.mapper.PersonalWorkspaceExecutionInputMapper;
import cn.jia.agent.mapper.PersonalWorkspaceExecutionMapper;
import cn.jia.agent.mapper.PersonalWorkspaceExecutionOutputMapper;
import cn.jia.core.util.DateUtil;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.util.List;
import java.util.Objects;

@Named
public class PersonalWorkspaceExecutionDaoImpl implements PersonalWorkspaceExecutionDao {
    private final PersonalWorkspaceExecutionMapper executions;
    private final PersonalWorkspaceExecutionInputMapper inputs;
    private final PersonalWorkspaceExecutionOutputMapper outputs;

    @Inject
    public PersonalWorkspaceExecutionDaoImpl(PersonalWorkspaceExecutionMapper executions,
            PersonalWorkspaceExecutionInputMapper inputs,
            PersonalWorkspaceExecutionOutputMapper outputs) {
        this.executions = Objects.requireNonNull(executions, "executions");
        this.inputs = Objects.requireNonNull(inputs, "inputs");
        this.outputs = Objects.requireNonNull(outputs, "outputs");
    }
    @Override public List<PersonalWorkspaceExecutionEntity> findUnfinishedTaskExecutions(
            String tenant, String client, String owner, String task) {
        scope(tenant, client, owner); id(task, "taskId");
        return executions.findUnfinishedTaskExecutions(tenant, client, owner, task);
    }
    @Override public boolean cancelLockedExecution(PersonalWorkspaceExecutionEntity execution, long now) {
        return executions.cancelLockedExecution(execution.getTenantId(), execution.getClientId(),
                execution.getOwnerJiacn(), execution.getExecutionId(),
                execution.getExecutionState(), now) == 1;
    }
    @Override public PersonalWorkspaceExecutionEntity find(String tenantId, String clientId,
            String ownerJiacn, String executionId) {
        scope(tenantId, clientId, ownerJiacn); id(executionId, "executionId");
        return executions.findExact(tenantId, clientId, ownerJiacn, executionId);
    }
    @Override public PersonalWorkspaceExecutionEntity findByIdempotency(String tenantId,
            String clientId, String ownerJiacn, String idempotencyKey) {
        scope(tenantId, clientId, ownerJiacn); id(idempotencyKey, "idempotencyKey");
        return executions.findByIdempotency(tenantId, clientId, ownerJiacn, idempotencyKey);
    }
    @Override public PersonalWorkspaceExecutionEntity findRequestByIdempotency(String tenantId,
            String clientId, String ownerJiacn, String idempotencyKey) {
        scope(tenantId, clientId, ownerJiacn); id(idempotencyKey, "idempotencyKey");
        return executions.findRequestByIdempotency(tenantId, clientId, ownerJiacn, idempotencyKey);
    }
    @Override public List<PersonalWorkspaceExecutionEntity> listHistory(String tenantId,
            String clientId, String ownerJiacn, Long beforeCreatedAt,
            String beforeExecutionId, int limit) {
        scope(tenantId, clientId, ownerJiacn);
        if ((beforeCreatedAt == null) != (beforeExecutionId == null)
                || (beforeCreatedAt != null && beforeCreatedAt < 0)
                || limit < 1 || limit > 101) {
            throw new IllegalArgumentException("history");
        }
        if (beforeExecutionId != null) id(beforeExecutionId, "beforeExecutionId");
        return executions.listHistory(tenantId, clientId, ownerJiacn,
                beforeCreatedAt, beforeExecutionId, limit);
    }
    @Override public PersonalWorkspaceExecutionEntity findByRevokeIdempotency(String tenantId,
            String clientId, String ownerJiacn, String idempotencyKey) {
        scope(tenantId, clientId, ownerJiacn); id(idempotencyKey, "idempotencyKey");
        return executions.findByRevokeIdempotency(tenantId, clientId, ownerJiacn, idempotencyKey);
    }
    @Override public String admittedCaptureProfile(String tenant,String client,String owner,String task,String agent,String execution,String run){
        return executions.admittedCaptureProfile(tenant,client,owner,task,agent,execution,run);
    }
    @Override public PersonalWorkspaceExecutionEntity findForNativeUsageCost(String tenantId, String clientId,
            String executorOwner, String agentId, String executionId, String runId) {
        return executions.findForNativeUsageCost(tenantId, clientId, executorOwner, agentId, executionId, runId);
    }
    @Override public PersonalWorkspaceExecutionEntity findForRuntime(String tenantId,String clientId,
            String executorOwner,String agentId,String executionId,String runId) {
        return executions.findForRuntime(tenantId,clientId,executorOwner,agentId,executionId,runId);
    }
    @Override public PersonalWorkspaceExecutionEntity findByTaskRun(String tenantId, String clientId,
            String ownerJiacn, String taskId, String runId) {
        scope(tenantId, clientId, ownerJiacn); id(taskId, "taskId"); id(runId, "runId");
        return executions.findByTaskRun(tenantId, clientId, ownerJiacn, taskId, runId);
    }
    @Override public PersonalWorkspaceExecutionEntity lock(String tenantId, String clientId,
            String ownerJiacn, String executionId) {
        scope(tenantId, clientId, ownerJiacn); id(executionId, "executionId");
        return executions.lockExact(tenantId, clientId, ownerJiacn, executionId);
    }
    @Override public PersonalWorkspaceExecutionEntity lockByTaskRun(String tenantId, String clientId,
            String ownerJiacn, String taskId, String runId) {
        scope(tenantId, clientId, ownerJiacn); id(taskId, "taskId"); id(runId, "runId");
        return executions.lockByTaskRun(tenantId, clientId, ownerJiacn, taskId, runId);
    }
    @Override public List<PersonalWorkspaceExecutionEntity> listQueuedConversationsByTarget(
            String tenantId, String clientId, String ownerJiacn, String targetAgentId,
            Long afterCreatedAt, String afterExecutionId, int limit) {
        scope(tenantId, clientId, ownerJiacn); id(targetAgentId, "targetAgentId");
        if ((afterCreatedAt == null) != (afterExecutionId == null)
                || (afterCreatedAt != null && (afterCreatedAt < 0 || afterExecutionId.isBlank())))
            throw new IllegalArgumentException("invalid conversation inbox cursor");
        return executions.listQueuedConversationsByTarget(tenantId, clientId, ownerJiacn, targetAgentId,
                afterCreatedAt, afterExecutionId, Math.max(1, Math.min(limit, 16)));
    }
    @Override public boolean hasExecutionProtocolVersionColumn() {
        int count=executions.countExecutionProtocolVersionColumn();
        if(count<0||count>1)throw new IllegalStateException("execution protocol catalog ambiguity");
        return count==1;
    }
    @Override public void registerTaskMember(PersonalWorkspaceExecutionEntity execution,String captureId) {
        appendMember(new MemberIdentity(execution.getTenantId(),execution.getClientId(),execution.getOwnerJiacn(),execution.getTaskId(),
                execution.getExecutionId(),execution.getRunId(),execution.getRequestHash()),captureId);
    }
    @Override public void appendMember(MemberIdentity execution,String captureId) {
        String t=execution.tenantId(),c=execution.clientId(),o=execution.ownerJiacn(),id=execution.taskId();
        String leaf=cn.jia.economy.usage.TaskMembershipDigest.member(t,c,o,id,execution.executionId(),execution.runId(),captureId,execution.requestHash());
        executions.ensureMemberSet(t,c,o,id);executions.registerTaskMember(execution,captureId);
        if(executions.advanceMemberSet(execution,leaf)!=1) throw new IllegalStateException("Task execution set closed or stale");
    }
    @Override public cn.jia.economy.usage.TaskMembership.Terminal pending(String t,String c,String fact){return executions.pendingTerminal(t,c,fact);}
    @Override public java.util.List<cn.jia.economy.usage.TaskMembership.Member> members(cn.jia.economy.usage.TaskMembership.Terminal t,long after){return executions.membersPage(t,after,cn.jia.economy.usage.TaskMembership.PAGE);}
    @Override public void delivered(cn.jia.economy.usage.TaskMembership.Terminal t){executions.markMembershipDelivered(t);}
    @Override public java.util.List<cn.jia.economy.usage.TaskMembership.Pending> pendingPage(String tenant,String client,String after){return executions.pendingMembershipPage(tenant,client,after,cn.jia.economy.usage.TaskMembership.PAGE);}
    @Override public void closeTaskMembers(String t,String c,String o,String id,Outcome outcome,String ref,long revision,long time) {
        String fact=cn.jia.economy.usage.TaskMembershipDigest.tuple("TASK_TERMINAL_V1",t,c,o,id,outcome.name(),ref,Long.toString(revision));
        executions.ensureMemberSet(t,c,o,id);
        if(executions.freezeMemberSet(t,c,o,id,outcome.name(),fact,revision,time)!=1) throw new IllegalStateException("Task execution set already closed");
    }
    @Override public void insert(PersonalWorkspaceExecutionEntity execution) { init(execution); executions.insert(execution); }
    @Override public void update(PersonalWorkspaceExecutionEntity execution) { execution.setUpdateTime(DateUtil.nowTime()); executions.updateById(execution); }
    @Override public void insertInput(PersonalWorkspaceExecutionInputEntity input) { init(input); inputs.insert(input); }
    @Override public List<PersonalWorkspaceExecutionInputEntity> listInputs(String tenantId,
            String clientId, String ownerJiacn, String executionId) {
        scope(tenantId, clientId, ownerJiacn); id(executionId, "executionId");
        return inputs.listByExecution(tenantId, clientId, ownerJiacn, executionId);
    }
    @Override public PersonalWorkspaceExecutionInputEntity findInput(String tenantId, String clientId,
            String ownerJiacn, String executionId, String inputRef) {
        scope(tenantId, clientId, ownerJiacn); id(executionId, "executionId"); id(inputRef, "inputRef");
        return inputs.findByReference(tenantId, clientId, ownerJiacn, executionId, inputRef);
    }
    @Override public PersonalWorkspaceExecutionInputEntity lockInput(String tenantId, String clientId,
            String ownerJiacn, String executionId, String inputRef) {
        scope(tenantId, clientId, ownerJiacn); id(executionId, "executionId"); id(inputRef, "inputRef");
        return inputs.lockByReference(tenantId, clientId, ownerJiacn, executionId, inputRef);
    }
    @Override public List<String> listActiveExecutionIdsByFile(String tenantId, String clientId,
            String ownerJiacn, String fileId, int limit) {
        scope(tenantId, clientId, ownerJiacn); id(fileId, "fileId");
        return inputs.listActiveExecutionIdsByFile(tenantId, clientId, ownerJiacn, fileId,
                Math.max(1, Math.min(limit, 100)));
    }
    @Override public void updateInput(PersonalWorkspaceExecutionInputEntity input) { input.setUpdateTime(DateUtil.nowTime()); inputs.updateById(input); }
    @Override public PersonalWorkspaceExecutionOutputEntity findOutput(String tenantId,
            String clientId, String ownerJiacn, String executionId, String outputId) {
        scope(tenantId, clientId, ownerJiacn); id(executionId, "executionId"); id(outputId, "outputId");
        return outputs.findByOutputId(tenantId, clientId, ownerJiacn, executionId, outputId);
    }
    @Override public PersonalWorkspaceExecutionOutputEntity lockOutput(String tenantId,
            String clientId, String ownerJiacn, String executionId, String outputId) {
        scope(tenantId, clientId, ownerJiacn); id(executionId, "executionId"); id(outputId, "outputId");
        return outputs.lockByOutputId(tenantId, clientId, ownerJiacn, executionId, outputId);
    }
    @Override public List<PersonalWorkspaceExecutionOutputEntity> lockOutputs(String tenantId,
            String clientId, String ownerJiacn, String executionId) {
        scope(tenantId, clientId, ownerJiacn); id(executionId, "executionId");
        return outputs.lockByExecution(tenantId, clientId, ownerJiacn, executionId);
    }
    @Override public PersonalWorkspaceExecutionOutputEntity findPublishedReworkSource(String tenantId,
            String clientId, String ownerJiacn, String taskId, String formalDeliveryId,
            String outputId, String workspaceFileId, int workspaceFileVersion) {
        scope(tenantId, clientId, ownerJiacn); id(taskId, "taskId");
        id(formalDeliveryId, "formalDeliveryId"); id(outputId, "outputId");
        id(workspaceFileId, "workspaceFileId");
        if (workspaceFileVersion < 1) throw new IllegalArgumentException("workspaceFileVersion");
        return outputs.findPublishedReworkSource(tenantId, clientId, ownerJiacn, taskId,
                formalDeliveryId, outputId, workspaceFileId, workspaceFileVersion);
    }
    @Override public void insertOutput(PersonalWorkspaceExecutionOutputEntity output) { init(output); outputs.insert(output); }
    @Override public void updateOutput(PersonalWorkspaceExecutionOutputEntity output) { output.setUpdateTime(DateUtil.nowTime()); outputs.updateById(output); }

    private static void init(cn.jia.core.entity.BaseEntity entity) { entity.init4Creation(); }
    private static void scope(String tenantId, String clientId, String ownerJiacn) {
        TaskCollaborationDaoSupport.requireStrictOwnerScope(tenantId, clientId, ownerJiacn);
        id(clientId, "clientId"); id(ownerJiacn, "ownerJiacn"); if ("0".equals(ownerJiacn)) throw new IllegalArgumentException("owner");
    }
    private static void id(String value, String name) {
        if (value == null || value.isBlank() || !value.equals(value.strip()) || value.codePointCount(0, value.length()) > 100 || value.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException(name);
    }
}
