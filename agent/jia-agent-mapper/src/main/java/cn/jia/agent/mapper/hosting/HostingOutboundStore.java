package cn.jia.agent.mapper.hosting;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import java.util.List;

/** Independent JDBC storage boundary: explicit partitioning, including before ORDER/LIMIT/locks. */
@Repository
public final class HostingOutboundStore {
    public record Grant(String tenantId, String clientId, String authorizationId, String hostId,
            String ownerJiacn, String canonicalAgentId, String purpose, byte[] tokenHash, String status) {
        public Grant { tokenHash = tokenHash.clone(); }
        @Override public byte[] tokenHash() { return tokenHash.clone(); }
        @Override public String toString() { return "HostingGrant[credentials=REDACTED]"; }
    }
    public record Command(long id, String messageId, String commandJson, String resultMessageId, String resultJson) { }
    private static final RowMapper<Grant> GRANT = (r, n) -> new Grant(r.getString("tenant_id"), r.getString("client_id"),
            r.getString("authorization_id"), r.getString("host_id"), r.getString("owner_jiacn"),
            r.getString("canonical_agent_id"), r.getString("purpose"), r.getBytes("token_hash"), r.getString("status"));
    private static final RowMapper<Command> COMMAND = (r, n) -> new Command(r.getLong("id"), r.getString("message_id"),
            r.getString("command_json"), r.getString("result_message_id"), r.getString("result_json"));
    private final JdbcTemplate jdbc;
    public HostingOutboundStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    private static <T> T one(List<T> rows) {
        if (rows.size() > 1) throw new IllegalStateException("Hosting transport identity is not unique");
        return rows.isEmpty() ? null : rows.getFirst();
    }
    public Grant authenticateForUpdate(String tenant, String configuredClient, String id, byte[] hash) {
        // Token lookup stays within the configured tenant even when that environment admits multiple clients.
        return one(jdbc.query("SELECT * FROM agent_hosting_authorization WHERE tenant_id=? AND authorization_id=? "
                + "AND token_hash=? AND (? IS NULL OR client_id=?) FOR UPDATE", GRANT,
                tenant, id, hash, configuredClient, configuredClient));
    }
    public Grant lock(String tenant, String client, String id) {
        return one(jdbc.query("SELECT * FROM agent_hosting_authorization WHERE tenant_id=? AND client_id=? AND authorization_id=? FOR UPDATE",
                GRANT, tenant, client, id));
    }
    public void insert(Grant grant, long now) {
        jdbc.update("INSERT INTO agent_hosting_authorization (tenant_id,client_id,authorization_id,host_id,owner_jiacn,canonical_agent_id,purpose,token_hash,status,created_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                grant.tenantId(), grant.clientId(), grant.authorizationId(), grant.hostId(), grant.ownerJiacn(), grant.canonicalAgentId(),
                grant.purpose(), grant.tokenHash(), grant.status(), now);
    }
    public int revoke(Grant grant, long now) {
        return jdbc.update("UPDATE agent_hosting_authorization SET status='REVOKED',revoked_at=? WHERE tenant_id=? AND client_id=? AND authorization_id=? AND status='ACTIVE'",
                now, grant.tenantId(), grant.clientId(), grant.authorizationId());
    }
    public Command command(Grant grant, String messageId) {
        return one(jdbc.query("SELECT * FROM agent_hosting_exchange WHERE tenant_id=? AND client_id=? AND authorization_id=? AND message_id=? FOR UPDATE",
                COMMAND, grant.tenantId(), grant.clientId(), grant.authorizationId(), messageId));
    }
    public Command pending(Grant grant) {
        return one(jdbc.query("SELECT * FROM agent_hosting_exchange WHERE tenant_id=? AND client_id=? AND authorization_id=? AND completed_at IS NULL ORDER BY id LIMIT 1 FOR UPDATE",
                COMMAND, grant.tenantId(), grant.clientId(), grant.authorizationId()));
    }
    public void enqueue(Grant grant, String messageId, String json, long now) {
        jdbc.update("INSERT INTO agent_hosting_exchange (tenant_id,client_id,authorization_id,message_id,command_json,created_at) VALUES (?,?,?,?,?,?)",
                grant.tenantId(), grant.clientId(), grant.authorizationId(), messageId, json, now);
    }
    public int complete(Grant grant, Command command, String resultId, String json, long now) {
        return jdbc.update("UPDATE agent_hosting_exchange SET result_message_id=?,result_json=?,completed_at=? WHERE tenant_id=? AND client_id=? AND authorization_id=? AND id=? AND completed_at IS NULL",
                resultId, json, now, grant.tenantId(), grant.clientId(), grant.authorizationId(), command.id());
    }
}
