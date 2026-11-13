package cn.jia.agent.mapper.hosting;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import java.util.LinkedHashMap;
import java.util.Map;

/** Scoped database configuration. No process cache: committed changes are visible to the next read. */
public final class PersonaModelHostingStore {
    public record Tariff(String personaCode, String modelId, String planId, long planVersion,
                         long amountWen, long periodSeconds, String currency, String status) { }
    private static final RowMapper<Tariff> ROW = (r, n) -> new Tariff(r.getString("persona_code"),
            r.getString("model_id"), r.getString("plan_id"), r.getLong("plan_version"),
            r.getLong("amount_wen"), r.getLong("period_seconds"), r.getString("currency"), r.getString("status"));
    private static final String QUERY = """
            SELECT a.persona_code,a.model_id,p.plan_id,p.plan_version,p.amount_wen,p.period_seconds,p.currency,p.status
              FROM agent_persona_model a
              JOIN economy_hosting_model_plan m ON m.tenant_id=a.tenant_id AND m.client_id=a.client_id AND m.model_id=a.model_id
              JOIN economy_hosting_rent_plan p ON p.tenant_id=m.tenant_id AND p.client_id=m.client_id
                   AND p.plan_id=m.plan_id AND p.plan_version=m.plan_version
             WHERE a.tenant_id=? AND a.client_id=?
            """;
    private final JdbcTemplate jdbc;
    public PersonaModelHostingStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Tariff find(String tenantId, String clientId, String personaCode) {
        var rows = jdbc.query(QUERY + " AND a.persona_code=?", ROW, tenantId, clientId, personaCode);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public Map<String, Tariff> list(String tenantId, String clientId) {
        var result = new LinkedHashMap<String, Tariff>();
        for (var row : jdbc.query(QUERY, ROW, tenantId, clientId)) result.put(row.personaCode(), row);
        return Map.copyOf(result);
    }
}
