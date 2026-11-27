package cn.jia.agent.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Explicit schema-maintenance: create tables and add nullable scope columns, never backfill history. */
@org.springframework.context.annotation.Profile("schema-maintenance")
public final class AgentTaskRequirementSnapshotSchemaInitializer implements InitializingBean {
    private static final String TABLE = "agent_task_requirement_snapshot";
    private static final List<String> SCOPE_COLUMNS = List.of("scope_schema", "scope_json", "scope_sha256",
            "scope_input_sha256", "scope_confirmed_at");

    private static final String CURRENT = "agent_task_current_requirement";
    private static final Map<String,String> SCOPE_TYPES = Map.of(
            "scope_schema","varchar(48)","scope_json","json","scope_sha256","char(64)",
            "scope_input_sha256","char(64)","scope_confirmed_at","bigint");

    private final JdbcTemplate jdbc;

    public AgentTaskRequirementSnapshotSchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override public void afterPropertiesSet() {
        try (var c = Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            if (!c.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("mysql")
                    || c.getMetaData().getDatabaseMajorVersion()<8)
                throw new IllegalStateException("Requirement snapshot requires MySQL 8");
        } catch (Exception e) { throw new IllegalStateException("Requirement snapshot database unavailable", e); }
        // MySQL DDL commits independently: inspect both existing tables before the first ALTER.
        boolean snapshotExists=exists(TABLE), currentExists=exists(CURRENT);
        if (snapshotExists) { validate(false); validateScopeColumns(TABLE, false); }
        if (currentExists) { validateCurrent(); validateScopeColumns(CURRENT, false); }
        if (!snapshotExists) jdbc.execute(ddl());
        if (!currentExists) executeResource("db/agent-task-current-requirement-v1.sql");
        evolveScopeColumns(TABLE);
        evolveScopeColumns(CURRENT);
        validate();
        validateCurrent();
        executeResource("db/agent-task-requirement-edit-operation-v1.sql");
    }

    private boolean exists(String table) {
        Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                +"WHERE table_schema=DATABASE() AND table_name=?",Integer.class,table);
        if (count==null || count<0 || count>1) throw new IllegalStateException("Ambiguous requirement table");
        return count==1;
    }

    private void executeResource(String resource) {
        try { jdbc.execute(new ClassPathResource(resource).getContentAsString(StandardCharsets.UTF_8)); }
        catch (java.io.IOException failure) {throw new IllegalStateException("Current requirement DDL unavailable",failure);}
    }

    private void validateScopeColumns(String table, boolean requireAll) {
        for (String name:SCOPE_COLUMNS) {
            var rows=jdbc.queryForList("SELECT column_type,is_nullable,collation_name FROM information_schema.columns "
                    +"WHERE table_schema=DATABASE() AND table_name=? AND column_name=?",table,name);
            if (rows.isEmpty() && !requireAll) continue;
            if (rows.size()!=1 || !SCOPE_TYPES.get(name).equalsIgnoreCase(Objects.toString(rows.getFirst().get("column_type"),""))
                    || !"YES".equalsIgnoreCase(Objects.toString(rows.getFirst().get("is_nullable"),""))
                    || List.of("scope_schema","scope_sha256","scope_input_sha256").contains(name)
                       && !"ascii_bin".equalsIgnoreCase(Objects.toString(rows.getFirst().get("collation_name"),"")))
                throw new IllegalStateException("Confirmed scope column drift: "+table+"."+name);
        }
    }

    private void evolveScopeColumns(String table) {
        if (!List.of(TABLE,CURRENT).contains(table)) throw new IllegalArgumentException("Unknown scope table");
        var missing=new java.util.ArrayList<String>();
        for (String name:SCOPE_COLUMNS) {
            Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns "
                    +"WHERE table_schema=DATABASE() AND table_name=? AND column_name=?",Integer.class,table,name);
            if (count==null || count<0 || count>1) throw new IllegalStateException("Ambiguous scope column");
            if (count==0) missing.add("ADD COLUMN "+name+" "+SCOPE_TYPES.get(name)
                    +(List.of("scope_schema","scope_sha256","scope_input_sha256").contains(name)
                    ? " CHARACTER SET ascii COLLATE ascii_bin" : "")+" NULL");
        }
        if (!missing.isEmpty()) jdbc.execute("ALTER TABLE "+table+" "+String.join(", ",missing));
        validateScopeColumns(table, true);
    }

    private void validateCurrent() {
        var table=jdbc.queryForMap("SELECT engine,table_collation FROM information_schema.tables "
                +"WHERE table_schema=DATABASE() AND table_name=?",CURRENT);
        if (!"InnoDB".equalsIgnoreCase(Objects.toString(table.get("engine"),""))
                || !"utf8mb4_0900_bin".equalsIgnoreCase(Objects.toString(table.get("table_collation"),"")))
            throw new IllegalStateException("Current requirement table engine/collation drift");
        var types=Map.of("tenant_id","varchar(50)","client_id","varchar(50)","owner_jiacn","varchar(50)",
                "task_id","varchar(100)","revision","bigint","title","mediumtext","description","mediumtext",
                "content_sha256","char(64)","source","varchar(20)");
        var columns=jdbc.queryForList("SELECT column_name,column_type,is_nullable,collation_name FROM information_schema.columns "
                +"WHERE table_schema=DATABASE() AND table_name=?",CURRENT);
        var seen=new java.util.HashSet<String>();
        for (var column:columns) {
            String name=Objects.toString(column.get("column_name"),"");
            if (SCOPE_COLUMNS.contains(name)) continue;
            String expected=types.get(name);
            if (expected==null || !seen.add(name)
                    || !expected.equalsIgnoreCase(Objects.toString(column.get("column_type"),""))
                    || !(name.equals("description")?"YES":"NO").equalsIgnoreCase(Objects.toString(column.get("is_nullable"),"")))
                throw new IllegalStateException("Current requirement column drift: "+name);
            if (!name.equals("revision") && !(name.equals("content_sha256")?"ascii_bin":"utf8mb4_0900_bin")
                    .equalsIgnoreCase(Objects.toString(column.get("collation_name"),"")))
                throw new IllegalStateException("Current requirement collation drift: "+name);
        }
        if (!seen.equals(types.keySet())) throw new IllegalStateException("Current requirement columns missing");
        var indexes=jdbc.queryForList("SELECT index_name,non_unique,seq_in_index,column_name,sub_part "
                +"FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name=? "
                +"ORDER BY index_name,seq_in_index",CURRENT);
        index(indexes,"PRIMARY",0,List.of("tenant_id","client_id","owner_jiacn","task_id"));
    }

    static String ddl() {
        try {
            String source = new ClassPathResource("db/agent-task-requirement-snapshot-v1.sql")
                    .getContentAsString(StandardCharsets.UTF_8).trim();
            String normalized = source.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
            if (!normalized.contains("create table if not exists " + TABLE)
                    || normalized.contains(" alter table ") || normalized.contains(" drop ")
                    || normalized.contains(" insert ") || normalized.contains(" update "))
                throw new IllegalStateException("Unsafe snapshot DDL");
            return source.endsWith(";") ? source.substring(0, source.length()-1) : source;
        } catch (Exception e) { throw new IllegalStateException("Invalid snapshot DDL", e); }
    }

    void validate() { validate(true); }

    private void validate(boolean requireScopeColumns) {
        Map<String,Object> table = jdbc.queryForMap("SELECT engine,table_collation FROM "
                + "information_schema.tables WHERE table_schema=DATABASE() AND table_name=?", TABLE);
        if (!"InnoDB".equalsIgnoreCase(Objects.toString(table.get("engine"), ""))
                || !"utf8mb4_0900_bin".equalsIgnoreCase(Objects.toString(table.get("table_collation"), "")))
            throw new IllegalStateException("Snapshot table engine/collation drift");
        var columns = jdbc.queryForList("SELECT column_name,column_type,is_nullable,collation_name "
                + "FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? "
                + "ORDER BY ordinal_position", TABLE);
        var expected = new java.util.ArrayList<>(List.of("id","tenant_id","client_id","owner_jiacn","task_id",
                "revision","confirmation_id","task_version_at_confirmation","title","description","content_sha256","source","created_at"));
        if (!columns.stream().map(r -> Objects.toString(r.get("column_name"), ""))
                .filter(name -> !SCOPE_COLUMNS.contains(name)).toList().equals(expected))
            throw new IllegalStateException("Snapshot columns drift");
        if (requireScopeColumns) validateScopeColumns(TABLE, true);
        for (var r : columns) {
            String name = Objects.toString(r.get("column_name"), "");
            if (List.of("tenant_id","client_id","owner_jiacn","task_id","confirmation_id","title","description","source").contains(name)
                    && !"utf8mb4_0900_bin".equalsIgnoreCase(Objects.toString(r.get("collation_name"), "")))
                throw new IllegalStateException("Snapshot text collation drift: " + name);
            if ((name.equals("title") || name.equals("description"))
                    && !"mediumtext".equalsIgnoreCase(Objects.toString(r.get("column_type"), "")))
                throw new IllegalStateException("Snapshot text width drift: " + name);
            if ((!name.equals("description") && !SCOPE_COLUMNS.contains(name)) && !"NO".equalsIgnoreCase(Objects.toString(r.get("is_nullable"), "")))
                throw new IllegalStateException("Snapshot nullability drift: " + name);
        }
        List<Map<String,Object>> indexes = jdbc.queryForList("SELECT index_name,non_unique,seq_in_index,column_name,sub_part "
                + "FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name=? "
                + "ORDER BY index_name,seq_in_index", TABLE);
        index(indexes,"PRIMARY",0,List.of("id"));
        index(indexes,"uk_atrs_scope_revision",0,List.of("tenant_id","client_id","owner_jiacn","task_id","revision"));
        index(indexes,"uk_atrs_confirmation",0,List.of("tenant_id","client_id","owner_jiacn","task_id","confirmation_id"));
        index(indexes,"idx_atrs_task_latest",1,List.of("tenant_id","client_id","owner_jiacn","task_id","revision","id"));
        if (indexes.size()!=17) throw new IllegalStateException("Snapshot extra index drift");
    }
    static void index(List<Map<String,Object>> rows,String name,int nonUnique,List<String> expected) {
        var matching=rows.stream().filter(r -> name.equals(r.get("index_name"))).toList();
        if (matching.size()!=expected.size()) throw new IllegalStateException("Snapshot index drift: "+name);
        for (int i=0;i<expected.size();i++) {
            var r=matching.get(i);
            if (!expected.get(i).equals(r.get("column_name"))
                    || ((Number)r.get("seq_in_index")).intValue()!=i+1
                    || ((Number)r.get("non_unique")).intValue()!=nonUnique || r.get("sub_part")!=null)
                throw new IllegalStateException("Snapshot index drift: "+name);
        }
    }
}
