package cn.jia.agent.mapper;

import cn.jia.agent.entity.PersonalWorkspaceExecutionEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface PersonalWorkspaceExecutionMapper extends BaseMapper<PersonalWorkspaceExecutionEntity> {
    @org.apache.ibatis.annotations.Insert("""
        INSERT INTO agent_task_execution_set(tenant_id,client_id,owner_jiacn,task_id,membership_revision,execution_count,execution_set_sha256)
        VALUES(#{t},#{c},#{o},#{id},0,0,SHA2('',256)) ON DUPLICATE KEY UPDATE task_id=VALUES(task_id)
        """)
    void ensureMemberSet(@Param("t") String tenant,@Param("c") String client,@Param("o") String owner,@Param("id") String task);
    @Update("""
        UPDATE agent_task_execution_set s JOIN agent_task_execution_member m USING (tenant_id,client_id,owner_jiacn,task_id)
        SET s.membership_revision=m.membership_revision,s.execution_count=s.execution_count+1,
            s.execution_set_sha256=SHA2(CONCAT(s.execution_set_sha256,#{leaf}),256)
        WHERE s.tenant_id=#{r.tenantId} AND s.client_id=#{r.clientId} AND s.owner_jiacn=#{r.ownerJiacn} AND s.task_id=#{r.taskId}
          AND m.execution_id=#{r.executionId} AND m.run_id=#{r.runId} AND s.terminal_state IS NULL AND m.membership_revision>s.membership_revision
        """)
    int advanceMemberSet(@Param("r") PersonalWorkspaceExecutionEntity row,@Param("leaf") String leaf);
    @Update("""
        UPDATE agent_task_execution_set SET terminal_state=#{state},terminal_fact_id=#{fact},terminal_revision=#{revision},closed_at=#{time},delivery_state='PENDING'
        WHERE tenant_id=#{t} AND client_id=#{c} AND owner_jiacn=#{o} AND task_id=#{id} AND terminal_state IS NULL
        """)
    int freezeMemberSet(@Param("t") String tenant,@Param("c") String client,@Param("o") String owner,@Param("id") String task,
        @Param("state") String state,@Param("fact") String fact,@Param("revision") long revision,@Param("time") long time);

    @Select("""
        SELECT tenant_id,client_id,owner_jiacn,task_id,terminal_fact_id,terminal_state,terminal_revision,closed_at,membership_revision,execution_count,execution_set_sha256
        FROM agent_task_execution_set WHERE tenant_id=#{t} AND client_id=#{c} AND terminal_fact_id=#{id} AND delivery_state='PENDING'
        """) cn.jia.economy.usage.TaskMembership.Terminal pendingTerminal(@Param("t") String t,@Param("c") String c,@Param("id") String id);
    @Select("""
        SELECT membership_revision,execution_id,run_id,capture_id,admission_sha256 FROM agent_task_execution_member
        WHERE tenant_id=#{t.tenant} AND client_id=#{t.client} AND owner_jiacn=#{t.owner} AND task_id=#{t.task}
          AND membership_revision>#{after} AND membership_revision<=#{t.membershipRevision} ORDER BY membership_revision LIMIT #{limit}
        """) java.util.List<cn.jia.economy.usage.TaskMembership.Member> membersPage(@Param("t") cn.jia.economy.usage.TaskMembership.Terminal t,@Param("after") long after,@Param("limit") int limit);
    @Update("""
        UPDATE agent_task_execution_set SET delivery_state='DELIVERED' WHERE tenant_id=#{tenant} AND client_id=#{client} AND owner_jiacn=#{owner}
          AND task_id=#{task} AND terminal_fact_id=#{fact} AND delivery_state='PENDING'
        """) int markMembershipDelivered(cn.jia.economy.usage.TaskMembership.Terminal t);

    @Select("""
        <script>SELECT client_id,terminal_fact_id FROM agent_task_execution_set WHERE tenant_id=#{t} AND delivery_state='PENDING'
        <if test="c != null">AND client_id=#{c}</if> AND terminal_fact_id &gt; #{after} ORDER BY terminal_fact_id LIMIT #{limit}</script>
        """) java.util.List<cn.jia.economy.usage.TaskMembership.Pending> pendingMembershipPage(@Param("t") String tenant,@Param("c") String client,@Param("after") String after,@Param("limit") int limit);

    /** Append under the original task-root lock. Revision is an ordering cursor, not a count. */
    @org.apache.ibatis.annotations.Insert("""
        INSERT INTO agent_task_execution_member(tenant_id,client_id,owner_jiacn,task_id,execution_id,run_id,admission_sha256,capture_id)
        VALUES(#{row.tenantId},#{row.clientId},#{row.ownerJiacn},#{row.taskId},#{row.executionId},#{row.runId},#{row.requestHash},#{capture})
        """)
    void registerTaskMember(@Param("row") PersonalWorkspaceExecutionEntity row,@Param("capture") String capture);

    @Select("""
            SELECT * FROM agent_personal_workspace_execution
            WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND owner_jiacn=#{ownerJiacn}
              AND task_id=#{taskId} AND execution_mode='CONVERSATION' AND execution_protocol_version=6 AND business_scope='TASK'
              AND BINARY tenant_id=BINARY #{tenantId} AND BINARY client_id=BINARY #{clientId}
              AND BINARY owner_jiacn=BINARY #{ownerJiacn} AND BINARY task_id=BINARY #{taskId}
              AND execution_state NOT IN ('OUTPUT_COMMITTED','RESULT_COMMITTED','FAILED','INPUTS_REVOKED','CANCELLED')
            ORDER BY conversation_id,execution_id
            """)
    java.util.List<PersonalWorkspaceExecutionEntity> findUnfinishedTaskExecutions(
            @Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("ownerJiacn") String ownerJiacn, @Param("taskId") String taskId);

    @Update("""
            UPDATE agent_personal_workspace_execution SET execution_state='CANCELLED',update_time=#{now},
              conversation_lease_token=NULL,conversation_lease_runtime_id=NULL,conversation_lease_expires_at=NULL
            WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND owner_jiacn=#{ownerJiacn}
              AND execution_id=#{executionId} AND execution_state=#{expectedState}
              AND execution_mode='CONVERSATION' AND execution_protocol_version=6
              AND BINARY tenant_id=BINARY #{tenantId} AND BINARY client_id=BINARY #{clientId}
              AND BINARY owner_jiacn=BINARY #{ownerJiacn} AND BINARY execution_id=BINARY #{executionId}
            """)
    int cancelLockedExecution(@Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("ownerJiacn") String ownerJiacn, @Param("executionId") String executionId,
            @Param("expectedState") String expectedState, @Param("now") long now);

    @Select("""
        SELECT e.* FROM agent_personal_workspace_execution e
        WHERE BINARY e.tenant_id=BINARY #{tenantId} AND BINARY e.client_id=BINARY #{clientId}
          AND BINARY e.target_agent_id=BINARY #{agentId} AND BINARY e.execution_id=BINARY #{executionId}
          AND BINARY e.run_id=BINARY #{runId}
          AND e.execution_mode='CONVERSATION' AND e.execution_protocol_version=6
          AND ((e.business_scope='PRIVATE' AND BINARY e.owner_jiacn=BINARY #{executorOwner}
                AND e.task_id IS NULL AND e.task_grant_id IS NULL
                AND e.task_grant_version IS NULL AND e.assignment_revision IS NULL)
            OR (e.business_scope='TASK' AND EXISTS (
              SELECT 1 FROM agent_task_execution_grant g
              WHERE BINARY g.tenant_id=BINARY e.tenant_id AND BINARY g.client_id=BINARY e.client_id
                AND BINARY g.owner_jiacn=BINARY e.owner_jiacn AND BINARY g.task_id=BINARY e.task_id
                AND BINARY g.grant_id=BINARY e.task_grant_id AND g.grant_version=e.task_grant_version
                AND BINARY g.target_agent_id=BINARY e.target_agent_id
                AND BINARY g.executor_owner_jiacn=BINARY #{executorOwner})))
        """)
    PersonalWorkspaceExecutionEntity findForRuntime(@Param("tenantId") String tenantId,
        @Param("clientId") String clientId,@Param("executorOwner") String executorOwner,
        @Param("agentId") String agentId,@Param("executionId") String executionId,@Param("runId") String runId);



    /** Evidence-only authority for current native6 executions, including terminal runs.
     * TASK retains the original executor binding after grant cancellation/version changes;
     * PRIVATE has no task grant and is bound to its original owner and target Agent.
     * Neither branch grants a lease, reactivates work, or mutates business completion.
     */
    @Select("""
        SELECT e.* FROM agent_personal_workspace_execution e
        WHERE e.tenant_id=#{tenantId} AND e.client_id=#{clientId} AND e.execution_id=#{executionId}
          AND BINARY e.tenant_id=BINARY #{tenantId} AND BINARY e.client_id=BINARY #{clientId}
          AND BINARY e.execution_id=BINARY #{executionId}
          AND BINARY e.run_id=BINARY #{runId} AND BINARY e.target_agent_id=BINARY #{agentId}
          AND e.execution_mode='CONVERSATION' AND e.execution_protocol_version=6
          AND ((e.business_scope='PRIVATE' AND BINARY e.owner_jiacn=BINARY #{executorOwner}
                AND e.task_id IS NULL AND e.task_grant_id IS NULL
                AND e.task_grant_version IS NULL AND e.assignment_revision IS NULL
                AND EXISTS (SELECT 1 FROM agent_runtime r
                  WHERE BINARY r.tenant_id=BINARY e.tenant_id AND BINARY r.client_id=BINARY e.client_id
                    AND BINARY r.owner_jiacn=BINARY e.owner_jiacn AND BINARY r.agent_id=BINARY e.target_agent_id))
            OR (e.business_scope='TASK' AND EXISTS (
              SELECT 1 FROM agent_task_execution_grant g
              JOIN agent_runtime r ON r.agent_id=g.target_agent_id AND r.binding_id=g.executor_binding_id
                AND BINARY r.tenant_id=BINARY g.tenant_id AND BINARY r.client_id=BINARY g.client_id
                AND BINARY r.owner_jiacn=BINARY g.executor_owner_jiacn
                AND BINARY r.agent_id=BINARY g.target_agent_id
              WHERE BINARY g.tenant_id=BINARY e.tenant_id AND BINARY g.client_id=BINARY e.client_id
                AND BINARY g.owner_jiacn=BINARY e.owner_jiacn AND BINARY g.task_id=BINARY e.task_id
                AND BINARY g.grant_id=BINARY e.task_grant_id AND BINARY g.target_agent_id=BINARY e.target_agent_id
                AND BINARY g.executor_owner_jiacn=BINARY #{executorOwner})))
        """)
    PersonalWorkspaceExecutionEntity findForNativeUsageCost(@Param("tenantId") String tenantId,
        @Param("clientId") String clientId, @Param("executorOwner") String executorOwner,
        @Param("agentId") String agentId, @Param("executionId") String executionId,
        @Param("runId") String runId);

    @Select("""
            SELECT COUNT(*)
              FROM information_schema.columns
             WHERE table_schema=DATABASE()
               AND table_name='agent_personal_workspace_execution'
               AND column_name='execution_protocol_version'
            """)
    int countExecutionProtocolVersionColumn();

    @Select("""
            <script>
            SELECT tenant_id, client_id, owner_jiacn, execution_id, target_agent_id,
                   execution_state, output_content_mime_type, created_at
              FROM agent_personal_workspace_execution
             WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND owner_jiacn=#{ownerJiacn}
               AND CAST(tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
               AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId})
               AND CAST(client_id AS BINARY)=CAST(#{clientId} AS BINARY)
               AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId})
               AND CAST(owner_jiacn AS BINARY)=CAST(#{ownerJiacn} AS BINARY)
               AND OCTET_LENGTH(owner_jiacn)=OCTET_LENGTH(#{ownerJiacn})
            <if test='beforeCreatedAt != null and beforeExecutionId != null'>
               AND (created_at &lt; #{beforeCreatedAt}
                    OR (created_at=#{beforeCreatedAt}
                        AND CAST(execution_id AS BINARY) &lt; CAST(#{beforeExecutionId} AS BINARY)))
            </if>
             ORDER BY created_at DESC, CAST(execution_id AS BINARY) DESC
             LIMIT #{limit}
            </script>
            """)
    java.util.List<PersonalWorkspaceExecutionEntity> listHistory(
            @Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("ownerJiacn") String ownerJiacn, @Param("beforeCreatedAt") Long beforeCreatedAt,
            @Param("beforeExecutionId") String beforeExecutionId, @Param("limit") int limit);

    @Select("""
            SELECT tenant_id, client_id, owner_jiacn, execution_id, task_id, run_id,
                   execution_mode, work_item_id, conversation_id, target_agent_id,
                   output_content_mime_type, execution_state, failure_code, failure_message,
                   grant_revision, idempotency_key, created_at, revoked_at, failed_at
              FROM agent_personal_workspace_execution
             WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND owner_jiacn=#{ownerJiacn}
               AND idempotency_key=#{idempotencyKey}
               AND CAST(tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
               AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId})
               AND CAST(client_id AS BINARY)=CAST(#{clientId} AS BINARY)
               AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId})
               AND CAST(owner_jiacn AS BINARY)=CAST(#{ownerJiacn} AS BINARY)
               AND OCTET_LENGTH(owner_jiacn)=OCTET_LENGTH(#{ownerJiacn})
               AND CAST(idempotency_key AS BINARY)=CAST(#{idempotencyKey} AS BINARY)
               AND OCTET_LENGTH(idempotency_key)=OCTET_LENGTH(#{idempotencyKey})
             LIMIT 1
            """)
    PersonalWorkspaceExecutionEntity findRequestByIdempotency(
            @Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("ownerJiacn") String ownerJiacn, @Param("idempotencyKey") String idempotencyKey);

    @Select("""
            <script>
            SELECT * FROM agent_personal_workspace_execution
             WHERE tenant_id=#{tenantId} AND client_id=#{clientId}
               AND target_agent_id=#{targetAgentId} AND execution_state='QUEUED'
               AND execution_mode='CONVERSATION'
               AND execution_protocol_version=6
               AND conversation_provider_started_at IS NULL
               AND CAST(tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
               AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId})
               AND CAST(client_id AS BINARY)=CAST(#{clientId} AS BINARY)
               AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId})
               AND ((business_scope='PRIVATE'
                     AND task_id IS NULL AND task_grant_id IS NULL
                     AND task_grant_version IS NULL AND assignment_revision IS NULL
                     AND BINARY owner_jiacn=BINARY #{ownerJiacn})
                 OR (business_scope='TASK'
                     AND task_id IS NOT NULL AND task_grant_id IS NOT NULL
                     AND task_grant_version IS NOT NULL AND assignment_revision IS NOT NULL
                     AND EXISTS (SELECT 1 FROM agent_task_execution_grant g
                       WHERE BINARY g.tenant_id=BINARY agent_personal_workspace_execution.tenant_id
                         AND BINARY g.client_id=BINARY agent_personal_workspace_execution.client_id
                         AND BINARY g.owner_jiacn=BINARY agent_personal_workspace_execution.owner_jiacn
                         AND BINARY g.task_id=BINARY agent_personal_workspace_execution.task_id
                         AND BINARY g.grant_id=BINARY agent_personal_workspace_execution.task_grant_id
                         AND g.grant_version=agent_personal_workspace_execution.task_grant_version
                         AND BINARY g.target_agent_id=BINARY agent_personal_workspace_execution.target_agent_id
                         AND BINARY g.executor_owner_jiacn=BINARY #{ownerJiacn})))
               AND CAST(target_agent_id AS BINARY)=CAST(#{targetAgentId} AS BINARY)
               AND OCTET_LENGTH(target_agent_id)=OCTET_LENGTH(#{targetAgentId})
               AND CAST(execution_mode AS BINARY)=CAST('CONVERSATION' AS BINARY)
               AND OCTET_LENGTH(execution_mode)=OCTET_LENGTH('CONVERSATION')
            <if test='afterCreatedAt != null and afterExecutionId != null'>
               AND (created_at &gt; #{afterCreatedAt}
                    OR (created_at=#{afterCreatedAt}
                        AND CAST(execution_id AS BINARY) &gt; CAST(#{afterExecutionId} AS BINARY)))
            </if>
             ORDER BY created_at ASC, CAST(execution_id AS BINARY) ASC LIMIT #{limit}
            </script>
            """)
    java.util.List<PersonalWorkspaceExecutionEntity> listQueuedConversationsByTarget(
            @Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("ownerJiacn") String ownerJiacn, @Param("targetAgentId") String targetAgentId,
            @Param("afterCreatedAt") Long afterCreatedAt,
            @Param("afterExecutionId") String afterExecutionId, @Param("limit") int limit);

    @Select("""
            SELECT * FROM agent_personal_workspace_execution
             WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND owner_jiacn=#{ownerJiacn}
               AND execution_id=#{executionId}
               AND CAST(tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
               AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId})
               AND CAST(client_id AS BINARY)=CAST(#{clientId} AS BINARY)
               AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId})
               AND CAST(owner_jiacn AS BINARY)=CAST(#{ownerJiacn} AS BINARY)
               AND OCTET_LENGTH(owner_jiacn)=OCTET_LENGTH(#{ownerJiacn})
               AND CAST(execution_id AS BINARY)=CAST(#{executionId} AS BINARY)
               AND OCTET_LENGTH(execution_id)=OCTET_LENGTH(#{executionId})
             LIMIT 1
            """)
    PersonalWorkspaceExecutionEntity findExact(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("ownerJiacn") String ownerJiacn,
            @Param("executionId") String executionId);

    @Select("""
            SELECT * FROM agent_personal_workspace_execution
             WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND owner_jiacn=#{ownerJiacn}
               AND idempotency_key=#{idempotencyKey}
               AND CAST(tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
               AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId})
               AND CAST(client_id AS BINARY)=CAST(#{clientId} AS BINARY)
               AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId})
               AND CAST(owner_jiacn AS BINARY)=CAST(#{ownerJiacn} AS BINARY)
               AND OCTET_LENGTH(owner_jiacn)=OCTET_LENGTH(#{ownerJiacn})
               AND CAST(idempotency_key AS BINARY)=CAST(#{idempotencyKey} AS BINARY)
               AND OCTET_LENGTH(idempotency_key)=OCTET_LENGTH(#{idempotencyKey})
             LIMIT 1
            """)
    PersonalWorkspaceExecutionEntity findByIdempotency(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("ownerJiacn") String ownerJiacn,
            @Param("idempotencyKey") String idempotencyKey);

    @Select("""
            SELECT * FROM agent_personal_workspace_execution
             WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND owner_jiacn=#{ownerJiacn}
               AND revoke_idempotency_key=#{idempotencyKey}
               AND CAST(tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
               AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId})
               AND CAST(client_id AS BINARY)=CAST(#{clientId} AS BINARY)
               AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId})
               AND CAST(owner_jiacn AS BINARY)=CAST(#{ownerJiacn} AS BINARY)
               AND OCTET_LENGTH(owner_jiacn)=OCTET_LENGTH(#{ownerJiacn})
               AND CAST(revoke_idempotency_key AS BINARY)=CAST(#{idempotencyKey} AS BINARY)
               AND OCTET_LENGTH(revoke_idempotency_key)=OCTET_LENGTH(#{idempotencyKey})
             LIMIT 1
            """)
    PersonalWorkspaceExecutionEntity findByRevokeIdempotency(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("ownerJiacn") String ownerJiacn,
            @Param("idempotencyKey") String idempotencyKey);

    @Select("""
            SELECT * FROM agent_personal_workspace_execution
             WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND owner_jiacn=#{ownerJiacn}
               AND task_id=#{taskId} AND run_id=#{runId}
               AND CAST(tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
               AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId})
               AND CAST(client_id AS BINARY)=CAST(#{clientId} AS BINARY)
               AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId})
               AND CAST(owner_jiacn AS BINARY)=CAST(#{ownerJiacn} AS BINARY)
               AND OCTET_LENGTH(owner_jiacn)=OCTET_LENGTH(#{ownerJiacn})
               AND CAST(task_id AS BINARY)=CAST(#{taskId} AS BINARY)
               AND OCTET_LENGTH(task_id)=OCTET_LENGTH(#{taskId})
               AND CAST(run_id AS BINARY)=CAST(#{runId} AS BINARY)
               AND OCTET_LENGTH(run_id)=OCTET_LENGTH(#{runId})
             LIMIT 1
            """)
    PersonalWorkspaceExecutionEntity findByTaskRun(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("ownerJiacn") String ownerJiacn,
            @Param("taskId") String taskId, @Param("runId") String runId);

    @Select("""
            SELECT * FROM agent_personal_workspace_execution
             WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND owner_jiacn=#{ownerJiacn}
               AND task_id=#{taskId} AND run_id=#{runId}
               AND CAST(tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
               AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId})
               AND CAST(client_id AS BINARY)=CAST(#{clientId} AS BINARY)
               AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId})
               AND CAST(owner_jiacn AS BINARY)=CAST(#{ownerJiacn} AS BINARY)
               AND OCTET_LENGTH(owner_jiacn)=OCTET_LENGTH(#{ownerJiacn})
               AND CAST(task_id AS BINARY)=CAST(#{taskId} AS BINARY)
               AND OCTET_LENGTH(task_id)=OCTET_LENGTH(#{taskId})
               AND CAST(run_id AS BINARY)=CAST(#{runId} AS BINARY)
               AND OCTET_LENGTH(run_id)=OCTET_LENGTH(#{runId})
             LIMIT 1 FOR UPDATE
            """)
    PersonalWorkspaceExecutionEntity lockByTaskRun(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("ownerJiacn") String ownerJiacn,
            @Param("taskId") String taskId, @Param("runId") String runId);

    @Select("""
            SELECT * FROM agent_personal_workspace_execution
             WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND owner_jiacn=#{ownerJiacn}
               AND execution_id=#{executionId}
               AND CAST(tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
               AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId})
               AND CAST(client_id AS BINARY)=CAST(#{clientId} AS BINARY)
               AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId})
               AND CAST(owner_jiacn AS BINARY)=CAST(#{ownerJiacn} AS BINARY)
               AND OCTET_LENGTH(owner_jiacn)=OCTET_LENGTH(#{ownerJiacn})
               AND CAST(execution_id AS BINARY)=CAST(#{executionId} AS BINARY)
               AND OCTET_LENGTH(execution_id)=OCTET_LENGTH(#{executionId})
             LIMIT 1 FOR UPDATE
            """)
    PersonalWorkspaceExecutionEntity lockExact(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("ownerJiacn") String ownerJiacn,
            @Param("executionId") String executionId);
}
