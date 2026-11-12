package cn.jia.agent.mapper.hosting;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import java.util.List;

/** Unbound invitation target metadata, separate from paid bindings and Runtime execution identity. */
public final class HostingSetupStore {
    public record Setup(String tenantId, String clientId, String setupId, String actorId, String ownerJiacn,
            String personaCode, String canonicalAgentId, String hostId, String mode, String idempotencyKey, byte[] requestHash) {
        public Setup { requestHash = requestHash.clone(); }
        @Override public byte[] requestHash() { return requestHash.clone(); }
    }
    private static final RowMapper<Setup> ROW = (r, n) -> new Setup(r.getString("tenant_id"), r.getString("client_id"),
            r.getString("setup_id"), r.getString("actor_id"), r.getString("owner_jiacn"), r.getString("persona_code"),
            r.getString("canonical_agent_id"), r.getString("host_id"), r.getString("deployment_mode"),
            r.getString("idempotency_key"), r.getBytes("request_hash"));
    private final JdbcTemplate jdbc;
    public HostingSetupStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    private static <T> T one(List<T> rows) {
        if (rows.size() > 1) throw new IllegalStateException("Ambiguous hosting setup");
        return rows.isEmpty() ? null : rows.getFirst();
    }
    public Setup replay(String tenant, String client, String actor, String key) {
        return one(jdbc.query("SELECT * FROM agent_hosting_setup WHERE tenant_id=? AND client_id=? AND actor_id=? AND idempotency_key=?",
                ROW, tenant, client, actor, key));
    }
    public Setup find(String tenant, String client, String setup) {
        return one(jdbc.query("SELECT * FROM agent_hosting_setup WHERE tenant_id=? AND client_id=? AND setup_id=?", ROW, tenant, client, setup));
    }
    public void insert(Setup s, long now) {
        jdbc.update("INSERT INTO agent_hosting_setup (tenant_id,client_id,setup_id,actor_id,owner_jiacn,persona_code,canonical_agent_id,host_id,deployment_mode,idempotency_key,request_hash,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                s.tenantId(), s.clientId(), s.setupId(), s.actorId(), s.ownerJiacn(), s.personaCode(), s.canonicalAgentId(), s.hostId(), s.mode(), s.idempotencyKey(), s.requestHash(), now);
    }
    public void attachQuote(Setup setup, String quoteId) {
        jdbc.update("INSERT INTO agent_hosting_setup_quote (tenant_id,client_id,quote_id,setup_id) VALUES (?,?,?,?)",
                setup.tenantId(), setup.clientId(), quoteId, setup.setupId());
    }
    public boolean isSetupQuote(String tenant, String client, String quote) {
        return !jdbc.queryForList("SELECT quote_id FROM agent_hosting_setup_quote WHERE tenant_id=? AND client_id=? AND quote_id=?",
                String.class, tenant, client, quote).isEmpty();
    }
}
