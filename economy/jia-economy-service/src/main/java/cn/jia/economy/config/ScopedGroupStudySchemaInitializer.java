package cn.jia.economy.config;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

/** Explicit maintenance only. Extends the existing study selector without rewriting approvals,
 * legacy selections, triggers or group heads. Missing foundation is an unmet prerequisite, not
 * permission to silently create a different study/approval system. */
public final class ScopedGroupStudySchemaInitializer implements InitializingBean {
    private static final String TABLE="economy_budget_group_study";
    private static final String LOCK="cyf:scoped-group-study-v1";
    private static final String COLLATION="utf8mb4_0900_bin";
    private final JdbcTemplate jdbc;
    public ScopedGroupStudySchemaInitializer(JdbcTemplate jdbc){this.jdbc=Objects.requireNonNull(jdbc);}
    @Override public void afterPropertiesSet(){
        jdbc.execute((ConnectionCallback<Void>)c->{
            if(!"MySQL".equals(c.getMetaData().getDatabaseProductName()))throw drift();
            if(!rows(c,"SELECT GET_LOCK(?,10)",LOCK).equals(List.of(List.of("1"))))
                throw new IllegalStateException("SCOPED_GROUP_STUDY_MIGRATION_BUSY");
            try {
                var state=validate(c);
                // Both states are checked before any DDL. An already-created column can resume
                // after interruption; an existing wrong index must not trigger another ALTER.
                if(!state.column())execute(c,"ALTER TABLE "+TABLE+
                        " ADD COLUMN scope_input_sha CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL");
                if(!state.index())execute(c,"ALTER TABLE "+TABLE+
                        " ADD UNIQUE KEY study_by_scope_input(tenant_id,client_id,scope_input_sha)");
                var finished=validate(c);
                if(!finished.column() || !finished.index())throw drift();
            }finally{rows(c,"SELECT RELEASE_LOCK(?)",LOCK);}
            return null;
        });
    }
    private record State(boolean column,boolean index){}
    private static State validate(Connection c)throws SQLException{
        if(!rows(c,"SELECT engine,table_collation FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?",TABLE)
                .equals(List.of(List.of("InnoDB",COLLATION))))throw drift();
        var columns=new HashMap<String,List<String>>();
        for(var row:rows(c,"SELECT column_name,column_type,is_nullable,COALESCE(collation_name,''),COALESCE(column_default,''),extra FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=?",TABLE))
            columns.put(row.getFirst(),row.subList(1,row.size()));
        for(String name:List.of("tenant_id","client_id"))
            if(!List.of("varchar(50)","NO",COLLATION,"","").equals(columns.get(name)))throw drift();
        for(String name:List.of("group_sha","approval_sha"))
            if(!List.of("char(64)","NO","ascii_bin","","").equals(columns.get(name)))throw drift();
        boolean column=columns.containsKey("scope_input_sha");
        if(column && !List.of("char(64)","YES","ascii_bin","","").equals(columns.get("scope_input_sha")))throw drift();
        checkIndex(c,"PRIMARY",List.of("tenant_id","client_id","group_sha"),"0",true);
        checkIndex(c,"study_by_approval",List.of("approval_sha"),"1",true);
        // Approval identity and deletion/update restrictions remain owned by the original FK.
        var foreign=rows(c,"SELECT k.column_name,k.referenced_table_name,k.referenced_column_name,r.update_rule,r.delete_rule FROM information_schema.key_column_usage k JOIN information_schema.referential_constraints r ON r.constraint_schema=k.constraint_schema AND r.table_name=k.table_name AND r.constraint_name=k.constraint_name WHERE k.table_schema=DATABASE() AND k.table_name=? AND k.column_name='approval_sha' AND k.referenced_table_name IS NOT NULL",TABLE);
        if(foreign.size()!=1 || !foreign.getFirst().subList(0,3).equals(List.of("approval_sha","economy_budget_publish_approval","approval_sha"))
                || foreign.getFirst().subList(3,5).stream().anyMatch(x->!Set.of("RESTRICT","NO ACTION").contains(x)))throw drift();
        boolean index=checkIndex(c,"study_by_scope_input",List.of("tenant_id","client_id","scope_input_sha"),"0",false);
        if(index && !column)throw drift();
        return new State(column,index);
    }
    private static boolean checkIndex(Connection c,String name,List<String> fields,String nonUnique,boolean required)throws SQLException{
        var actual=rows(c,"SELECT column_name,non_unique,COALESCE(CAST(sub_part AS CHAR),''),index_type,is_visible FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name=? AND index_name=? ORDER BY seq_in_index",TABLE,name);
        if(actual.isEmpty()){if(required)throw drift();return false;}
        if(!actual.equals(fields.stream().map(k->List.of(k,nonUnique,"","BTREE","YES")).toList()))throw drift();
        return true;
    }
    private static void execute(Connection c,String sql)throws SQLException{try(var stmt=c.createStatement()){stmt.execute(sql);}}
    private static List<List<String>> rows(Connection c,String sql,String... values)throws SQLException{
        var rows=new ArrayList<List<String>>();
        try(var statement=c.prepareStatement(sql)){
            for(int i=0;i<values.length;i++)statement.setString(i+1,values[i]);
            try(var result=statement.executeQuery()){
                while(result.next()){
                    var row=new ArrayList<String>();for(int i=1;i<=result.getMetaData().getColumnCount();i++)row.add(result.getString(i));rows.add(row);
                }
            }
        }
        return rows;
    }
    private static IllegalStateException drift(){return new IllegalStateException("SCOPED_GROUP_STUDY_SCHEMA_MISMATCH");}
}
