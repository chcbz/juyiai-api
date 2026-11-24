package cn.jia.chat.deliberation;

import jakarta.inject.Named;
import org.springframework.jdbc.core.JdbcTemplate;

/** Candidate transport identity, never proof that a send, model operation or charge occurred. */
@Named
public class ChatTaskDispatchOriginStore {
    private final JdbcTemplate jdbc;
    public ChatTaskDispatchOriginStore(JdbcTemplate jdbc){this.jdbc=jdbc;}
    /** Primary-key read: a changed tuple or any conflicting resend cannot attest this source. */
    public boolean matches(String event,String context,cn.jia.agent.service.AgentManagedSessionLookup.SessionFence fence){
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM chat_task_dispatch_origin WHERE event_id=? AND mixed_origin=FALSE
                  AND context_hash=? AND installation_id=? AND host_id=? AND runtime_instance_id=? AND session_generation=?)
                """,Boolean.class,event,context,fence.runtimeInstallationId(),fence.hostId(),fence.runtimeInstanceId(),fence.sessionGeneration()));
    }
    public void observe(String event,String context,String installation,String host,String runtime,long generation){
        // The owner has already locked the exact live claim. Preserve the first tuple and
        // monotonically disqualify conflicting retries; returning to the first never heals it.
        jdbc.update("""
                INSERT INTO chat_task_dispatch_origin(event_id,context_hash,installation_id,host_id,runtime_instance_id,session_generation)
                VALUES(?,?,?,?,?,?) ON DUPLICATE KEY UPDATE mixed_origin=mixed_origin OR NOT
                (context_hash=? AND installation_id=? AND host_id=? AND runtime_instance_id=? AND session_generation=?)
                """,event,context,installation,host,runtime,generation,context,installation,host,runtime,generation);
    }
}
