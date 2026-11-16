package cn.jia.chat.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Verifies the explicitly installed MySQL contract without DDL or implicit backfills. */
@Slf4j
@Component
@org.springframework.context.annotation.Profile("schema-maintenance")
public class ChatDeliberationSchemaInitializer implements ApplicationRunner {
    static final List<String> TABLES = List.of(
            "chat_context_snapshot", "chat_request", "chat_turn", "chat_dispatch_outbox",
            "chat_conversation_event", "chat_interaction_step", "chat_step_execution_link",
            "chat_bounty_binding", "chat_conversation_asset");
    private static final String COLLATION = "utf8mb4_0900_bin";
    private static final Map<String, Map<String, ColumnDef>> COLUMNS = columns();
    private static final Map<String, Map<String, IndexDef>> INDEXES = indexes();
    private static final Map<String, Map<String, ForeignKeyDef>> FOREIGN_KEYS = Map.of(
            "chat_interaction_step", Map.of("fk_chat_step_request_scope", new ForeignKeyDef(
                    "chat_request", List.of("tenant_id", "owner_jiacn", "client_id", "request_id", "request_revision"),
                    List.of("tenant_id", "owner_jiacn", "client_id", "request_id", "request_revision"))),
            "chat_step_execution_link", Map.of("fk_chat_exec_step_scope", new ForeignKeyDef(
                    "chat_interaction_step", List.of("tenant_id", "owner_jiacn", "client_id", "step_id"),
                    List.of("tenant_id", "owner_jiacn", "client_id", "step_id"))),
            "chat_conversation_asset", Map.of("fk_chat_asset_step_scope", new ForeignKeyDef(
                    "chat_interaction_step", List.of("tenant_id", "owner_jiacn", "client_id", "step_id"),
                    List.of("tenant_id", "owner_jiacn", "client_id", "step_id"))));

    private final JdbcTemplate jdbc;
    private boolean initialized;

    public ChatDeliberationSchemaInitializer(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        ensureInitialized();
    }

    public final synchronized void ensureInitialized() throws Exception {
        if (initialized) return;
        initializeSchema();
        initialized = true;
    }

    void initializeSchema() throws Exception {
        if (isH2()) return;
        // Schema changes are a separately authorized installation transaction, never a
        // restart side effect. A missing/old schema reports the exact required migration.
        for (String table : TABLES) {
            validateTable(table);
            validateColumns(table);
            validateIndexes(table);
            if (FOREIGN_KEYS.containsKey(table)) validateForeignKeys(table);
        }
    }

    private void validateTable(String table) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT engine, table_collation FROM information_schema.tables
                WHERE table_schema=DATABASE() AND table_name=?
                """, table);
        if (rows.size() != 1 || !"InnoDB".equalsIgnoreCase(text(rows.getFirst(), "engine"))
                || !COLLATION.equalsIgnoreCase(text(rows.getFirst(), "table_collation"))) {
            throw incompatible(table, "InnoDB/" + COLLATION, rows.toString());
        }
    }

    private void validateColumns(String table) {
        Map<String, Map<String, Object>> actual = readColumns(table);
        Map<String, ColumnDef> expected = COLUMNS.get(table);
        if (!actual.keySet().equals(expected.keySet())) {
            throw incompatible(table + " columns", expected.keySet().toString(), actual.keySet().toString());
        }
        expected.forEach((name, def) -> {
            Map<String, Object> row = actual.get(name);
            boolean nullable = "YES".equalsIgnoreCase(text(row, "is_nullable"));
            String extra = text(row, "extra").toLowerCase(Locale.ROOT);
            String defaultValue = nullableText(row, "column_default");
            String collation = nullableText(row, "collation_name");
            if (!def.type().equalsIgnoreCase(text(row, "data_type"))
                    || !Objects.equals(def.length(), nullableNumber(row, "character_maximum_length"))
                    || def.nullable() != nullable || !Objects.equals(def.defaultValue(), defaultValue)
                    || !def.extra().equals(extra)
                    || def.collated() && !COLLATION.equalsIgnoreCase(collation)) {
                throw incompatible(table + "." + name, def.toString(), row.toString());
            }
        });
    }

    private void validateIndexes(String table) {
        Map<String, IndexDef> actual = readIndexes(table);
        // These old unique keys prohibit a second, explicitly unexecuted context attempt.
        // Never drop them on application startup; installation owns the controlled migration.
        String obsolete = "chat_context_snapshot".equals(table) ? "uk_chat_snapshot_request_target"
                : "chat_dispatch_outbox".equals(table) ? "uk_chat_outbox_turn_event" : null;
        if (obsolete != null && actual.containsKey(obsolete))
            throw incompatible(table + " index " + obsolete, "removed by context-attempt migration", "present");
        Map<String, IndexDef> expected = INDEXES.get(table);
        for (var entry : expected.entrySet()) {
            if (!entry.getValue().equals(actual.get(entry.getKey()))) {
                throw incompatible(table + " index " + entry.getKey(), entry.getValue().toString(),
                        String.valueOf(actual.get(entry.getKey())));
            }
        }
    }

    private void validateForeignKeys(String table) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT constraint_name, column_name, referenced_table_name, referenced_column_name, ordinal_position
                FROM information_schema.key_column_usage
                WHERE table_schema=DATABASE() AND table_name=? AND referenced_table_name IS NOT NULL
                ORDER BY constraint_name, ordinal_position
                """, table);
        Map<String, List<String>> columns = new LinkedHashMap<>();
        Map<String, List<String>> references = new LinkedHashMap<>();
        Map<String, String> parents = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String name = text(row, "constraint_name");
            String parent = text(row, "referenced_table_name");
            String previous = parents.putIfAbsent(name, parent);
            if (previous != null && !previous.equals(parent)) {
                throw incompatible(table + " foreign key " + name, previous, parent);
            }
            columns.computeIfAbsent(name, ignored -> new java.util.ArrayList<>()).add(text(row, "column_name"));
            references.computeIfAbsent(name, ignored -> new java.util.ArrayList<>()).add(text(row, "referenced_column_name"));
        }
        Map<String, ForeignKeyDef> actual = new LinkedHashMap<>();
        parents.forEach((name, parent) -> actual.put(name,
                new ForeignKeyDef(parent, List.copyOf(columns.get(name)), List.copyOf(references.get(name)))));
        if (!actual.equals(FOREIGN_KEYS.get(table))) {
            throw incompatible(table + " foreign keys", FOREIGN_KEYS.get(table).toString(), actual.toString());
        }
    }

    private Map<String, Map<String, Object>> readColumns(String table) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT column_name,data_type,character_maximum_length,is_nullable,column_default,collation_name,extra
                FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name=? ORDER BY ordinal_position
                """, table);
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) result.put(text(row, "column_name"), row);
        return result;
    }

    private Map<String, IndexDef> readIndexes(String table) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT index_name,non_unique,seq_in_index,column_name,sub_part
                FROM information_schema.statistics
                WHERE table_schema=DATABASE() AND table_name=? ORDER BY index_name,seq_in_index
                """, table);
        Map<String, java.util.ArrayList<String>> columns = new LinkedHashMap<>();
        Map<String, Boolean> unique = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String name = text(row, "index_name");
            if (row.get("SUB_PART") != null || row.get("sub_part") != null) {
                throw incompatible(table + " index prefix", "none", row.toString());
            }
            columns.computeIfAbsent(name, ignored -> new java.util.ArrayList<>()).add(text(row, "column_name"));
            unique.put(name, number(row, "non_unique") == 0L);
        }
        Map<String, IndexDef> result = new LinkedHashMap<>();
        columns.forEach((name, cols) -> result.put(name, new IndexDef(unique.get(name), List.copyOf(cols))));
        return result;
    }

    private static Map<String, Map<String, ColumnDef>> columns() {
        Map<String, Map<String, ColumnDef>> all = new LinkedHashMap<>();
        all.put("chat_context_snapshot", ordered(
                c("snapshot_id", v(64,false)), c("tenant_id",v(50,false)), c("owner_jiacn",v(50,false)),
                c("client_id",v(50,false)), c("conversation_id",v(100,false)), c("conversation_generation",b(false,null)),
                c("request_id",v(100,false)), c("request_revision",b(false,null)), c("target_agent_id",v(100,false)),
                c("route",v(20,false)), c("source_vector_json",txt("text",false)), c("facts_manifest_json",txt("mediumtext",false)),
                c("context_digest",v(100,false)), c("created_at",b(false,null))));
        all.put("chat_request", ordered(c("id",auto()), c("tenant_id",v(50,false)), c("owner_jiacn",v(50,false)),
                c("client_id",v(50,false)), c("request_id",v(100,false)), c("request_revision",b(false,null)),
                c("request_digest",v(100,false)), c("client_intent_sha256",new ColumnDef("char",64L,true,null,"",false,"CHAR(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL")), c("conversation_id",v(100,false)), c("conversation_generation",b(false,null)),
                c("user_message_id",b(false,null)), c("aggregate_state",v(30,false)), c("state_version",b(false,"0")),
                c("created_at",b(false,null)), c("updated_at",b(false,null))));
        all.put("chat_turn", ordered(c("turn_id",v(64,false)), c("tenant_id",v(50,false)), c("owner_jiacn",v(50,false)),
                c("client_id",v(50,false)), c("request_id",v(100,false)), c("request_revision",b(false,null)),
                c("conversation_id",v(100,false)), c("conversation_generation",b(false,null)), c("target_agent_id",v(100,false)),
                c("snapshot_id",v(64,false)), c("context_digest",v(100,false)), c("dispatch_id",v(64,false)),
                c("route",v(20,false)), c("state",v(30,false)), c("state_version",b(false,"0")),
                c("last_delta_seq",b(false,"0")), c("last_delta_digest",v(100,true)), c("final_digest",v(100,true)),
                c("final_message_id",b(true,null)), c("terminal_reason",v(500,true)), c("created_at",b(false,null)), c("updated_at",b(false,null))));
        all.put("chat_dispatch_outbox", ordered(c("event_id",v(64,false)), c("tenant_id",v(50,false)),
                c("owner_jiacn",v(50,false)), c("client_id",v(50,false)), c("turn_id",v(64,false)),
                c("dispatch_id",v(64,false)), c("event_type",v(30,false)), c("status",v(30,false)),
                c("task_grant_id",v(100,true)), c("task_grant_version",b(true,null)),
                c("task_assignment_revision",b(true,null)), c("task_requirement_revision",b(true,null)),
                c("payload_json",txt("mediumtext",false)), c("version",b(false,"0")),
                c("available_at",b(true,null)), c("lease_owner",v(100,true)), c("lease_until",b(true,null)),
                c("attempt_count",i(false,"0")), c("fencing_token",b(false,"0")), c("last_error",v(500,true)),
                c("sent_at",b(true,null)), c("created_at",b(false,null)), c("updated_at",b(false,null))));
        all.put("chat_conversation_event", ordered(c("event_sequence",auto()), c("event_id",v(64,false)),
                c("tenant_id",v(50,false)), c("owner_jiacn",v(50,false)), c("client_id",v(50,false)),
                c("conversation_id",v(100,false)), c("conversation_generation",b(false,null)),
                c("request_id",v(100,true)), c("turn_id",v(64,true)), c("dispatch_id",v(64,true)),
                c("event_type",v(40,false)), c("event_version",b(false,null)), c("payload_json",txt("mediumtext",false)),
                c("occurred_at",b(false,null))));
        all.put("chat_interaction_step", ordered(c("step_id",v(64,false)), c("tenant_id",v(50,false)),
                c("owner_jiacn",v(50,false)), c("client_id",v(50,false)), c("request_id",v(100,false)),
                c("request_revision",b(false,null)), c("step_number",b(false,null)),
                c("conversation_id",v(100,false)), c("conversation_generation",b(false,null)),
                c("task_id",v(100,true)), c("assignment_revision",b(true,null)),
                c("grant_id",v(100,true)), c("grant_version",b(true,null)),
                c("target_agent_id",v(100,false)), c("kind",v(20,false)), c("state",v(30,false)),
                c("state_version",b(false,"0")), c("input_snapshot_digest",v(100,false)),
                c("created_at",b(false,null)), c("updated_at",b(false,null))));
        all.put("chat_step_execution_link", ordered(c("execution_intent_id",v(64,false)),
                c("tenant_id",v(50,false)), c("owner_jiacn",v(50,false)), c("client_id",v(50,false)),
                c("step_id",v(64,false)), c("execution_id",v(64,true)), c("state",v(30,false)),
                c("state_version",b(false,"0")), c("created_at",b(false,null)), c("updated_at",b(false,null))));
        all.put("chat_bounty_binding", ordered(c("tenant_id",v(50,false)),
                c("owner_jiacn",v(50,false)), c("client_id",v(50,false)),
                c("task_id",v(100,false)), c("conversation_id",b(true,null)),
                c("assignment_revision",b(false,null)), c("created_at",b(false,null)),
                c("updated_at",b(false,null))));
        all.put("chat_conversation_asset", ordered(c("asset_id",v(64,false)),
                c("tenant_id",v(50,false)), c("owner_jiacn",v(50,false)), c("client_id",v(50,false)),
                c("conversation_id",v(100,false)), c("conversation_generation",b(false,null)),
                c("request_id",v(100,false)), c("step_id",v(64,false)),
                c("execution_id",v(100,false)), c("run_id",v(100,false)),
                c("output_id",v(100,false)), c("message_id",b(false,null)), c("part_id",v(64,false)),
                c("kind",v(10,false)), c("content_mime_type",v(127,false)), c("sha256",v(64,false)),
                c("byte_length",b(false,null)), c("revision",b(false,null)), c("created_at",b(false,null))));
        return Map.copyOf(all);
    }

    private static Map<String, Map<String, IndexDef>> indexes() {
        Map<String, Map<String, IndexDef>> all = new LinkedHashMap<>();
        all.put("chat_context_snapshot", Map.of(
                "PRIMARY", ix(true,"snapshot_id"),
                "idx_chat_snapshot_request_target", ix(false,"tenant_id","owner_jiacn","client_id","request_id","request_revision","target_agent_id"),
                "idx_chat_snapshot_conversation", ix(false,"tenant_id","owner_jiacn","client_id","conversation_id","conversation_generation","created_at")));
        all.put("chat_request", Map.of("PRIMARY",ix(true,"id"),
                "uk_chat_request_scope_revision",ix(true,"tenant_id","owner_jiacn","client_id","request_id","request_revision"),
                "idx_chat_request_conversation",ix(false,"tenant_id","owner_jiacn","client_id","conversation_id","conversation_generation","created_at"),
                "idx_chat_request_context_order",ix(false,"tenant_id","owner_jiacn","client_id","conversation_id","conversation_generation","id"),
                "idx_chat_request_user_message",ix(false,"user_message_id")));
        all.put("chat_turn", Map.of("PRIMARY",ix(true,"turn_id"),
                "uk_chat_turn_request_target",ix(true,"tenant_id","owner_jiacn","client_id","request_id","target_agent_id"),
                "uk_chat_turn_dispatch",ix(true,"dispatch_id"), "uk_chat_turn_snapshot",ix(true,"snapshot_id"),
                "idx_chat_turn_conversation",ix(false,"tenant_id","owner_jiacn","client_id","conversation_id","conversation_generation","state","updated_at")));
        all.put("chat_dispatch_outbox", Map.of("PRIMARY",ix(true,"event_id"),
                "uk_chat_outbox_turn_dispatch_event",ix(true,"turn_id","dispatch_id","event_type"),
                "idx_chat_outbox_ready",ix(false,"status","available_at","event_id"),
                "idx_chat_outbox_dispatch",ix(false,"tenant_id","client_id","dispatch_id","event_type"),
                "idx_chat_outbox_scope",ix(false,"tenant_id","owner_jiacn","client_id","turn_id","event_type")));
        all.put("chat_conversation_event", Map.of("PRIMARY",ix(true,"event_sequence"),
                "uk_chat_conversation_event_id",ix(true,"event_id"),
                "idx_chat_conversation_event_replay",ix(false,"tenant_id","owner_jiacn","client_id","conversation_id","conversation_generation","event_sequence")));
        all.put("chat_interaction_step", Map.of("PRIMARY",ix(true,"step_id"),
                "uk_chat_step_request_number",ix(true,"tenant_id","owner_jiacn","client_id","request_id","request_revision","step_number"),
                "uk_chat_step_scope_identity",ix(true,"tenant_id","owner_jiacn","client_id","step_id"),
                "idx_chat_step_task",ix(false,"tenant_id","owner_jiacn","client_id","task_id","assignment_revision","target_agent_id")));
        all.put("chat_step_execution_link", Map.of("PRIMARY",ix(true,"execution_intent_id"),
                "uk_chat_exec_step",ix(true,"step_id"), "uk_chat_exec_execution",ix(true,"execution_id"),
                "idx_chat_exec_scope_step",ix(false,"tenant_id","owner_jiacn","client_id","step_id")));
        all.put("chat_bounty_binding", Map.of(
                "PRIMARY",ix(true,"tenant_id","owner_jiacn","client_id","task_id"),
                "uk_chat_bounty_binding_conversation",ix(true,"tenant_id","owner_jiacn","client_id","conversation_id")));
        all.put("chat_conversation_asset", Map.of(
                "PRIMARY",ix(true,"asset_id"),
                "uk_chat_asset_step_output",ix(true,"tenant_id","owner_jiacn","client_id","step_id","output_id"),
                "idx_chat_asset_message",ix(false,"tenant_id","owner_jiacn","client_id","conversation_id","conversation_generation","message_id")));
        return Map.copyOf(all);
    }

    @SafeVarargs private static Map<String, ColumnDef> ordered(Map.Entry<String,ColumnDef>... entries) {
        Map<String,ColumnDef> result=new LinkedHashMap<>(); for(var e:entries) result.put(e.getKey(),e.getValue()); return Map.copyOf(result);
    }
    private static Map.Entry<String,ColumnDef> c(String n,ColumnDef d){return Map.entry(n,d);}
    private static ColumnDef v(long n,boolean nullable){return new ColumnDef("varchar",n,nullable,null,"",true,"VARCHAR("+n+") CHARACTER SET utf8mb4 COLLATE "+COLLATION+(nullable?" DEFAULT NULL":" NOT NULL"));}
    private static ColumnDef b(boolean nullable,String d){return new ColumnDef("bigint",null,nullable,d,"",false,"BIGINT"+(nullable?" DEFAULT NULL":" NOT NULL")+(d==null?"":" DEFAULT "+d));}
    private static ColumnDef auto(){return new ColumnDef("bigint",null,false,null,"auto_increment",false,"BIGINT NOT NULL AUTO_INCREMENT");}
    private static ColumnDef i(boolean nullable,String d){return new ColumnDef("int",null,nullable,d,"",false,"INT"+(nullable?" DEFAULT NULL":" NOT NULL")+(d==null?"":" DEFAULT "+d));}
    private static ColumnDef txt(String type,boolean nullable){
        // Actual MySQL metadata supplies a maximum character length for TEXT families too.
        // These are type capacities observed on the isolated MySQL 8.0.21 fixture, not payload limits.
        long length=switch(type){case "text" -> 65535L;case "mediumtext" -> 16777215L;
            default -> throw new IllegalArgumentException("Unsupported deliberation text type: "+type);};
        return new ColumnDef(type,length,nullable,null,"",true,type.toUpperCase()+" CHARACTER SET utf8mb4 COLLATE "+COLLATION+(nullable?" DEFAULT NULL":" NOT NULL"));
    }
    private static IndexDef ix(boolean unique,String... c){return new IndexDef(unique,List.of(c));}
    private static String text(Map<String,Object> row,String key){Object v=value(row,key); return v==null?"":String.valueOf(v);}
    private static String nullableText(Map<String,Object> row,String key){Object v=value(row,key); return v==null?null:String.valueOf(v);}
    private static Long nullableNumber(Map<String,Object> row,String key){Object v=value(row,key); return v==null?null:(v instanceof Number n?n.longValue():Long.valueOf(String.valueOf(v)));}
    private static long number(Map<String,Object> row,String key){Long v=nullableNumber(row,key); if(v==null)throw new IllegalStateException("Missing numeric metadata: "+key); return v;}
    private static Object value(Map<String,Object> row,String key){for(var e:row.entrySet())if(e.getKey().equalsIgnoreCase(key))return e.getValue();return null;}
    private static IllegalStateException incompatible(String what,String expected,String actual){return new IllegalStateException("Incompatible "+what+"; expected="+expected+", actual="+actual);}
    private boolean isH2() throws Exception { DataSource source=jdbc.getDataSource(); if(source==null)return false; try(Connection c=source.getConnection()){String n=c.getMetaData().getDatabaseProductName();return n!=null&&n.toLowerCase(Locale.ROOT).contains("h2");}}
    private record ColumnDef(String type,Long length,boolean nullable,String defaultValue,String extra,boolean collated,String ddl) { }
    private record IndexDef(boolean unique,List<String> columns) { }
    private record ForeignKeyDef(String referencedTable, List<String> columns, List<String> referencedColumns) { }
}
