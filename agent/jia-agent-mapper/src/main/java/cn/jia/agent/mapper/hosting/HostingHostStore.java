package cn.jia.agent.mapper.hosting;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import java.util.List;

/** Host credentials are independent of invitation, installation and rent lifetimes. */
public final class HostingHostStore {
    public record Host(String tenantId, String clientId, String hostId, String mode, String ownerJiacn,
                       byte[] tokenHash, String status, String idempotencyKey, byte[] requestHash) {
        public Host { tokenHash = tokenHash.clone(); requestHash = requestHash.clone(); }
        @Override public byte[] tokenHash() { return tokenHash.clone(); }
        @Override public byte[] requestHash() { return requestHash.clone(); }
        @Override public String toString() { return "HostingHost[credentials=REDACTED]"; }
    }
    private static final RowMapper<Host> ROW = (r,n) -> new Host(r.getString("tenant_id"), r.getString("client_id"),
            r.getString("host_id"), r.getString("deployment_mode"), r.getString("owner_jiacn"), r.getBytes("token_hash"),
            r.getString("status"), r.getString("idempotency_key"), r.getBytes("request_hash"));
    private final JdbcTemplate jdbc;
    public HostingHostStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    private static Host one(List<Host> rows) {
        if (rows.size() > 1) throw new IllegalStateException("Ambiguous hosting host");
        return rows.isEmpty() ? null : rows.getFirst();
    }
    public Host find(String tenant, String client, String host) {
        return one(jdbc.query("SELECT * FROM agent_hosting_host WHERE tenant_id=? AND client_id=? AND host_id=? FOR SHARE", ROW, tenant, client, host));
    }
    public Host authenticate(String tenant, String client, byte[] hash) {
        return one(jdbc.query("SELECT * FROM agent_hosting_host WHERE tenant_id=? AND (? IS NULL OR client_id=?) AND token_hash=? FOR SHARE", ROW, tenant, client, client, hash));
    }
    public Host replay(String tenant, String client, String owner, String key) {
        return one(jdbc.query("SELECT * FROM agent_hosting_host WHERE tenant_id=? AND client_id=? AND owner_jiacn <=> ? AND idempotency_key=? FOR UPDATE", ROW, tenant, client, owner, key));
    }
    public List<Host> owned(String tenant, String client, String owner, String after, int limit) {
        return jdbc.query("SELECT * FROM agent_hosting_host WHERE tenant_id=? AND client_id=? AND owner_jiacn=? AND deployment_mode='local' AND status='ACTIVE' AND host_id>? ORDER BY host_id LIMIT ?", ROW, tenant, client, owner, after, limit);
    }
    public void insert(Host h, long now) {
        jdbc.update("INSERT INTO agent_hosting_host (tenant_id,client_id,host_id,deployment_mode,owner_jiacn,token_hash,status,idempotency_key,request_hash,created_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                h.tenantId(),h.clientId(),h.hostId(),h.mode(),h.ownerJiacn(),h.tokenHash(),h.status(),h.idempotencyKey(),h.requestHash(),now);
    }
    public int replaceCredential(String tenant, String client, String host, byte[] expected, byte[] replacement, long now) {
        if (replacement==null) return jdbc.update("UPDATE agent_hosting_host SET status='REVOKED',revoked_at=? WHERE tenant_id=? AND client_id=? AND host_id=? AND token_hash=? AND status='ACTIVE'", now,tenant,client,host,expected);
        return jdbc.update("UPDATE agent_hosting_host SET token_hash=? WHERE tenant_id=? AND client_id=? AND host_id=? AND token_hash=? AND status='ACTIVE'", replacement,tenant,client,host,expected);
    }
    public int revoke(String tenant, String client, String host, long now) {
        return jdbc.update("UPDATE agent_hosting_host SET status='REVOKED',revoked_at=? WHERE tenant_id=? AND client_id=? AND host_id=? AND status='ACTIVE'",now,tenant,client,host);
    }
}
