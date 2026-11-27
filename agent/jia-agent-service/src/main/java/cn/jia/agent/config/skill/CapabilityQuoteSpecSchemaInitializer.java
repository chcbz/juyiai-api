package cn.jia.agent.config.skill;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import java.sql.*;
import java.util.*;

/** Explicit schema-maintenance only. Preflight both existing tables before additive creation. */
public final class CapabilityQuoteSpecSchemaInitializer implements InitializingBean {
    private static final String SPEC="agent_capability_quote_spec",BINDING="agent_skill_quote_spec_binding";
    private static final String COLLATION="utf8mb4_0900_bin",LOCK="cyf:capability-quote-spec-v1";
    private final JdbcTemplate jdbc;
    public CapabilityQuoteSpecSchemaInitializer(JdbcTemplate jdbc) {this.jdbc=Objects.requireNonNull(jdbc);}
    @Override public void afterPropertiesSet() {
        jdbc.execute((ConnectionCallback<Void>)connection->{
            if(!"MySQL".equals(connection.getMetaData().getDatabaseProductName()))throw drift();
            try(var lock=connection.prepareStatement("SELECT GET_LOCK(?,10)")) {
                lock.setString(1,LOCK);try(var result=lock.executeQuery()) {
                    if(!result.next() || result.getInt(1)!=1)throw drift();
                }
            }
            try {
                for(String table:List.of(SPEC,BINDING))if(exists(connection,table))validate(connection,table);
                new ResourceDatabasePopulator(new ClassPathResource("db/agent-capability-quote-spec-v1.sql")).populate(connection);
                for(String table:List.of(SPEC,BINDING))validate(connection,table);
            } finally {
                try(var release=connection.prepareStatement("SELECT RELEASE_LOCK(?)")){release.setString(1,LOCK);release.execute();}
            }
            return null;
        });
    }
    private static boolean exists(Connection connection,String table) throws SQLException {
        return !rows(connection,"SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?",table).isEmpty();
    }
    private static void validate(Connection connection,String table) throws SQLException {
        if(!rows(connection,"SELECT engine,table_collation FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?",table)
                .equals(List.of(List.of("InnoDB",COLLATION))))throw drift();
        var expected=new HashMap<String,List<String>>();
        column(expected,"tenant_id","varchar(50)",true);column(expected,"client_id","varchar(50)",true);
        column(expected,"capability_id","varchar(128)",true);
        if(SPEC.equals(table)) {
            column(expected,"version","varchar(64)",true);column(expected,"spec_sha","char(64)",true);
            column(expected,"spec_json","json",false);column(expected,"status","varchar(16)",true);column(expected,"published_at","timestamp(3)",false);
        } else {
            column(expected,"product_version_id","varchar(100)",true);column(expected,"capability_version","varchar(64)",true);column(expected,"semantic_sha","char(64)",true);
        }
        var actual=new HashMap<String,List<String>>();
        for(var row:rows(connection,"SELECT column_name,column_type,is_nullable,COALESCE(collation_name,'') FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=?",table))
            actual.put(row.getFirst(),row.subList(1,row.size()));
        if(!expected.equals(actual))throw drift();
        index(connection,table,"PRIMARY",SPEC.equals(table)?List.of("tenant_id","client_id","capability_id","version"):
                List.of("tenant_id","client_id","product_version_id","capability_id","capability_version"));
        if(SPEC.equals(table)) {
            index(connection,table,"uk_capability_quote_semantic",List.of("tenant_id","client_id","capability_id","version","spec_sha"));
        } else {
            foreignKey(connection,table,"fk_skill_quote_product_version","agent_skill_product_version",
                    List.of("tenant_id","client_id","product_version_id"),List.of("tenant_id","client_id","product_version_id"));
            foreignKey(connection,table,"fk_skill_quote_semantic",SPEC,
                    List.of("tenant_id","client_id","capability_id","capability_version","semantic_sha"),
                    List.of("tenant_id","client_id","capability_id","version","spec_sha"));
        }
    }
    private static void column(Map<String,List<String>> columns,String name,String type,boolean text) {
        columns.put(name,List.of(type,"NO",text?COLLATION:""));
    }
    private static void index(Connection c,String table,String name,List<String> expected) throws SQLException {
        var actual=rows(c,"SELECT column_name,non_unique FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name=? AND index_name=? ORDER BY seq_in_index",table,name);
        if(!actual.equals(expected.stream().map(column->List.of(column,"0")).toList()))throw drift();
    }
    private static void foreignKey(Connection c,String table,String name,String referenced,List<String> columns,List<String> targets) throws SQLException {
        var actual=rows(c,"SELECT column_name,referenced_table_name,referenced_column_name FROM information_schema.key_column_usage WHERE table_schema=DATABASE() AND table_name=? AND constraint_name=? ORDER BY ordinal_position",table,name);
        var expected=new ArrayList<List<String>>();for(int i=0;i<columns.size();i++)expected.add(List.of(columns.get(i),referenced,targets.get(i)));
        if(!expected.equals(actual))throw drift();
        var rules=rows(c,"SELECT update_rule,delete_rule FROM information_schema.referential_constraints WHERE constraint_schema=DATABASE() AND table_name=? AND constraint_name=?",table,name);
        if(rules.size()!=1 || rules.getFirst().stream().anyMatch(rule->!Set.of("RESTRICT","NO ACTION").contains(rule)))throw drift();
    }
    private static List<List<String>> rows(Connection c,String sql,String... values) throws SQLException {
        var rows=new ArrayList<List<String>>();
        try(var statement=c.prepareStatement(sql)) {
            for(int i=0;i<values.length;i++)statement.setString(i+1,values[i]);
            try(var result=statement.executeQuery()) {
                while(result.next()) {var row=new ArrayList<String>();for(int i=1;i<=result.getMetaData().getColumnCount();i++)row.add(result.getString(i));rows.add(row);}
            }
        }
        return rows;
    }
    private static IllegalStateException drift() {return new IllegalStateException("CAPABILITY_QUOTE_SCHEMA_MISMATCH");}
}
