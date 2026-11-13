package cn.jia.agent.mapper.hosting;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import java.util.List;

/** Independent JDBC storage boundary: explicit partitioning, including before ORDER/LIMIT/locks. */
public final class HostingOutboundStore {
    public record Grant(String tenantId, String clientId, String authorizationId, String hostId,
            String ownerJiacn, String canonicalAgentId, String purpose, String status) {
        @Override public String toString() { return "HostingGrant[credentials=REDACTED]"; }
    }
    public record Command(long id, String messageId, String commandJson, String resultMessageId, String resultJson) { }
    private static final RowMapper<Grant> GRANT = (r, n) -> new Grant(r.getString("tenant_id"), r.getString("client_id"),
            r.getString("authorization_id"), r.getString("host_id"), r.getString("owner_jiacn"),
            r.getString("canonical_agent_id"), r.getString("purpose"), r.getString("status"));
    private static final RowMapper<Command> COMMAND = (r, n) -> new Command(r.getLong("id"), r.getString("message_id"),
            r.getString("command_json"), r.getString("result_message_id"), r.getString("result_json"));
    private final JdbcTemplate jdbc;
    public HostingOutboundStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    private static <T> T one(List<T> rows) {
        if (rows.size() > 1) throw new IllegalStateException("Hosting transport identity is not unique");
        return rows.isEmpty() ? null : rows.getFirst();
    }
    public List<Grant> directory(String tenant, String client, String host, String after, int limit) {
        return jdbc.query("SELECT * FROM agent_hosting_authorization WHERE tenant_id=? AND client_id=? AND host_id=? AND status='ACTIVE' AND authorization_id>? ORDER BY authorization_id LIMIT ?", GRANT, tenant, client, host, after, limit);
    }
    public int activate(Grant grant) {
        return jdbc.update("UPDATE agent_hosting_authorization SET purpose='CONTROL' WHERE tenant_id=? AND client_id=? AND authorization_id=? AND purpose='SETUP' AND status='ACTIVE'", grant.tenantId(),grant.clientId(),grant.authorizationId());
    }
    public Grant activeSubject(String tenant, String client, String owner, String agent) {
        return one(jdbc.query("SELECT * FROM agent_hosting_authorization WHERE tenant_id=? AND client_id=? AND owner_jiacn=? AND canonical_agent_id=? AND purpose='CONTROL' AND status='ACTIVE'", GRANT, tenant, client, owner, agent));
    }
    public boolean hasSubject(String tenant, String client, String owner, String agent) {
        return !jdbc.queryForList("SELECT authorization_id FROM agent_hosting_authorization WHERE tenant_id=? AND client_id=? AND owner_jiacn=? AND canonical_agent_id=? AND purpose='CONTROL' LIMIT 1",String.class,tenant,client,owner,agent).isEmpty();
    }
    public Grant lock(String tenant, String client, String id) {
        return one(jdbc.query("SELECT * FROM agent_hosting_authorization WHERE tenant_id=? AND client_id=? AND authorization_id=? FOR UPDATE",
                GRANT, tenant, client, id));
    }
    public void insert(Grant grant, long now) {
        jdbc.update("INSERT INTO agent_hosting_authorization (tenant_id,client_id,authorization_id,host_id,owner_jiacn,canonical_agent_id,purpose,status,created_at) VALUES (?,?,?,?,?,?,?,?,?)",
                grant.tenantId(), grant.clientId(), grant.authorizationId(), grant.hostId(), grant.ownerJiacn(), grant.canonicalAgentId(),
                grant.purpose(), grant.status(), now);
    }
    public int revoke(Grant grant, long now) {
        return jdbc.update("UPDATE agent_hosting_authorization SET status='REVOKED',revoked_at=? WHERE tenant_id=? AND client_id=? AND authorization_id=? AND status='ACTIVE'",
                now, grant.tenantId(), grant.clientId(), grant.authorizationId());
    }
    public Command command(Grant grant, String messageId) {
        return one(jdbc.query("SELECT * FROM agent_hosting_exchange WHERE tenant_id=? AND client_id=? AND authorization_id=? AND message_id=? FOR UPDATE",
                COMMAND, grant.tenantId(), grant.clientId(), grant.authorizationId(), messageId));
    }
    public Command latest(Grant grant, String prefix) {
        return one(jdbc.query("SELECT * FROM agent_hosting_exchange WHERE tenant_id=? AND client_id=? AND authorization_id=? AND message_id LIKE ? ORDER BY id DESC LIMIT 1 FOR UPDATE",
                COMMAND, grant.tenantId(), grant.clientId(), grant.authorizationId(), prefix + "%"));
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
