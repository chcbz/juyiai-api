package cn.jia.agent.service.impl;

import cn.jia.agent.requirement.ConfirmedScope;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Strict private persistence codec, not a permissive public-envelope parser. */
final class ConfirmedScopeJson {
    static final String COLUMNS = "scope_schema,scope_json,scope_sha256,scope_input_sha256,scope_confirmed_at";
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    private ConfirmedScopeJson() { }

    static String write(ConfirmedScope scope) {
        try {
            return JSON.writeValueAsString(new ConfirmedScope.Payload(scope.schemaVersion(),
                    scope.workItems(),scope.acceptanceText()));
        } catch (Exception failure) { throw corrupt(failure); }
    }

    static ConfirmedScope read(ResultSet row,long revision,String requirementSha) throws SQLException {
        String schema=row.getString("scope_schema"),json=row.getString("scope_json"),
                sha=row.getString("scope_sha256"),input=row.getString("scope_input_sha256");
        Long confirmedAt=row.getObject("scope_confirmed_at",Long.class);
        if (schema==null && json==null && sha==null && input==null && confirmedAt==null) return null;
        if (schema==null || json==null || sha==null || input==null || confirmedAt==null) throw corrupt(null);
        try {
            var payload=JSON.readValue(json,ConfirmedScope.Payload.class);
            if (!schema.equals(payload.schemaVersion())) throw corrupt(null);
            return new ConfirmedScope(revision,requirementSha,sha,input,schema,confirmedAt,
                    payload.workItems(),payload.acceptanceText());
        } catch (Exception failure) { throw corrupt(failure); }
    }

    private static AgentTaskRequirementSnapshotServiceImpl.SnapshotIntegrityException corrupt(Throwable cause) {
        return new AgentTaskRequirementSnapshotServiceImpl.SnapshotIntegrityException(cause);
    }
}
