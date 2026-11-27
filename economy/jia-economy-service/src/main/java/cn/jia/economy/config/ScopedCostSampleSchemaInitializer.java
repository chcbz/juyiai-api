package cn.jia.economy.config;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

/** Additive maintenance of scope sample facts, never enrollment or retroactive shared-data approval. */
public final class ScopedCostSampleSchemaInitializer implements InitializingBean {
    private static final String TABLE="economy_task_cost_sample",LOCK="cyf:scoped-cost-sample-v1";
    private static final List<String> COLUMNS=List.of("scope_sha256","scope_shape_sha256","scoped_group_sha256","scoped_policy_sha256");
    private final JdbcTemplate jdbc;
    public ScopedCostSampleSchemaInitializer(JdbcTemplate jdbc){this.jdbc=Objects.requireNonNull(jdbc);}
    @Override public void afterPropertiesSet(){
        jdbc.execute((ConnectionCallback<Void>)c->{
            if(!"MySQL".equals(c.getMetaData().getDatabaseProductName()))throw drift();
            if(!rows(c,"SELECT GET_LOCK(?,10)",LOCK).equals(List.of(List.of("1"))))throw new IllegalStateException("SCOPED_COST_SAMPLE_MIGRATION_BUSY");
            try{
                var missing=validate(c);
                if(!missing.isEmpty())try(var stmt=c.createStatement()){
                    stmt.execute("ALTER TABLE "+TABLE+" "+String.join(", ",missing.stream()
                            .map(name->"ADD COLUMN "+name+" CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL").toList()));
                }
                if(!validate(c).isEmpty())throw drift();
            }finally{rows(c,"SELECT RELEASE_LOCK(?)",LOCK);}
            return null;
        });
    }
    private static List<String> validate(Connection c)throws SQLException{
        if(!rows(c,"SELECT engine,table_collation FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?",TABLE)
                .equals(List.of(List.of("InnoDB","utf8mb4_0900_bin"))))throw drift();
        var columns=new HashMap<String,List<String>>();
        for(var row:rows(c,"SELECT column_name,column_type,is_nullable,COALESCE(collation_name,''),COALESCE(column_default,''),extra FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=?",TABLE))columns.put(row.getFirst(),row.subList(1,row.size()));
        var key=List.of("tenant_id","client_id","owner_jiacn","task_id");
        for(var name:key)if(!List.of((name.equals("tenant_id")||name.equals("client_id"))?"varchar(50)":"varchar(100)","NO","utf8mb4_0900_bin","","").equals(columns.get(name)))throw drift();
        if(!List.of("char(64)","NO","utf8mb4_0900_bin","","").equals(columns.get("sample_id")))throw drift();
        for(var name:List.of("quote_id","benchmark_case_id"))if(!List.of("varchar(100)","YES","utf8mb4_0900_bin","","").equals(columns.get(name)))throw drift();
        index(c,"PRIMARY",List.of("sample_id"));
        for(var pair:Map.of("uk_sample_quote","quote_id","uk_sample_benchmark","benchmark_case_id").entrySet()){
            var fields=new ArrayList<>(key);fields.add(pair.getValue());fields.add("sample_definition_version");index(c,pair.getKey(),fields);
        }
        var missing=new ArrayList<String>();
        for(var name:COLUMNS){
            if(!columns.containsKey(name))missing.add(name);
            else if(!List.of("char(64)","YES","ascii_bin","","").equals(columns.get(name)))throw drift();
        }
        return missing;
    }
    private static void index(Connection c,String name,List<String> fields)throws SQLException{
        if(!rows(c,"SELECT column_name,non_unique,COALESCE(CAST(sub_part AS CHAR),'') FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name=? AND index_name=? ORDER BY seq_in_index",TABLE,name)
                .equals(fields.stream().map(k->List.of(k,"0","")).toList()))throw drift();
    }
    private static List<List<String>> rows(Connection c,String sql,String... values)throws SQLException{
        var rows=new ArrayList<List<String>>();try(var stmt=c.prepareStatement(sql)){
            for(int i=0;i<values.length;i++)stmt.setString(i+1,values[i]);
            try(var result=stmt.executeQuery()){while(result.next()){var row=new ArrayList<String>();for(int i=1;i<=result.getMetaData().getColumnCount();i++)row.add(result.getString(i));rows.add(row);}}
        }return rows;
    }
    private static IllegalStateException drift(){return new IllegalStateException("SCOPED_COST_SAMPLE_SCHEMA_MISMATCH");}
}
