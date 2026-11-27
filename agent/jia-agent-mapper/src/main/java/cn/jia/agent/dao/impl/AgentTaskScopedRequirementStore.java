package cn.jia.agent.dao.impl;

import cn.jia.agent.service.AgentTaskExecutionGrantService.Scope;
import cn.jia.agent.service.AgentTaskRequirementSnapshotService.Snapshot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Scoped requirement writes only. The caller owns the original owner-scoped task-root transaction. */
public final class AgentTaskScopedRequirementStore {
    private final JdbcTemplate jdbc;
    public AgentTaskScopedRequirementStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    public void insert(Snapshot row,String confirmationId,long expectedTaskVersion,String payloadJson) {
        writable();var s=row.confirmedScope();
        if (s==null) throw new IllegalArgumentException("Confirmed scope required");
        int changed=jdbc.update("INSERT INTO agent_task_requirement_snapshot "
                +"(tenant_id,client_id,owner_jiacn,task_id,revision,confirmation_id,task_version_at_confirmation,"
                +"title,description,content_sha256,source,created_at,scope_schema,scope_json,scope_sha256,scope_input_sha256,scope_confirmed_at) "
                +"VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",row.tenantId(),row.clientId(),row.ownerJiacn(),row.taskId(),
                row.revision(),confirmationId,expectedTaskVersion,row.title(),row.description(),row.sha256(),row.source(),
                s.confirmedAt(),s.schemaVersion(),payloadJson,s.scopeSha256(),s.inputSnapshotDigest(),s.confirmedAt());
        if (changed!=1) throw new IllegalStateException("Scope snapshot insert failed");
    }

    public void replaceCurrent(Snapshot row,long expectedRevision,String payloadJson) {
        writable();var s=row.confirmedScope();
        int changed=jdbc.update("UPDATE agent_task_current_requirement SET revision=?,title=?,description=?,content_sha256=?,source=?,"
                +"scope_schema=?,scope_json=?,scope_sha256=?,scope_input_sha256=?,scope_confirmed_at=? "
                +"WHERE tenant_id=? AND client_id=? AND owner_jiacn=? AND task_id=? AND revision=? "
                +"AND CAST(tenant_id AS BINARY)=CAST(? AS BINARY) AND CAST(client_id AS BINARY)=CAST(? AS BINARY) "
                +"AND CAST(owner_jiacn AS BINARY)=CAST(? AS BINARY) AND CAST(task_id AS BINARY)=CAST(? AS BINARY)",
                row.revision(),row.title(),row.description(),row.sha256(),row.source(),s.schemaVersion(),payloadJson,
                s.scopeSha256(),s.inputSnapshotDigest(),s.confirmedAt(),row.tenantId(),row.clientId(),row.ownerJiacn(),row.taskId(),
                expectedRevision,row.tenantId(),row.clientId(),row.ownerJiacn(),row.taskId());
        if (changed!=1) throw new IllegalStateException("Current scope changed");
    }

    public Long confirmationVersion(Scope scope,String taskId,String confirmationId) {
        var rows=jdbc.query("SELECT task_version_at_confirmation FROM agent_task_requirement_snapshot "
                +"WHERE tenant_id=? AND client_id=? AND owner_jiacn=? AND task_id=? AND confirmation_id=?",
                (r,n)->r.getLong(1),scope.tenantId(),scope.clientId(),scope.ownerJiacn(),taskId,confirmationId);
        if (rows.size()!=1) throw new IllegalStateException("Scope confirmation unavailable");
        return rows.getFirst();
    }

    private static void writable() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw new IllegalStateException("Scope write needs task-root transaction");
    }
}
