package cn.jia.economy.config;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/** Maintenance-only additive migration. Preflight existing tables before any DDL. */
public final class CostRouteBindingSchemaInitializer implements InitializingBean {
    private static final String REVISION="economy_cost_route_revision", HEAD="economy_cost_route_head";
    private static final String COLLATION="utf8mb4_0900_bin", LOCK="cyf:cost-route-binding-v1";
    private static final List<String> KEY=List.of("tenant_id","client_id","reference_sha");
    private final JdbcTemplate jdbc;
    public CostRouteBindingSchemaInitializer(JdbcTemplate jdbc){this.jdbc=Objects.requireNonNull(jdbc);}
    @Override public void afterPropertiesSet(){
        jdbc.execute((ConnectionCallback<Void>)connection->{
            if(!"MySQL".equals(connection.getMetaData().getDatabaseProductName()))throw drift();
            if(!rows(connection,"SELECT GET_LOCK(?,10)",LOCK).equals(List.of(List.of("1"))))throw drift();
            try{
                for(String table:List.of(REVISION,HEAD)){
                    if(!rows(connection,"SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?",table).isEmpty())validate(connection,table);
                }
                new ResourceDatabasePopulator(new ClassPathResource("db/economy-cost-route-binding.sql")).populate(connection);
                for(String table:List.of(REVISION,HEAD))validate(connection,table);
            }finally{rows(connection,"SELECT RELEASE_LOCK(?)",LOCK);}
            return null;
        });
    }
    private static void validate(Connection c,String table)throws SQLException{
        if(!rows(c,"SELECT engine,table_collation FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?",table)
                .equals(List.of(List.of("InnoDB",COLLATION))))throw drift();
        var expected=new HashMap<String,List<String>>();
        column(expected,"tenant_id","varchar(50)",false,true);column(expected,"client_id","varchar(50)",false,true);
        column(expected,"reference_sha","char(64)",false,true);column(expected,"revision","bigint",false,false);
        var primary=new ArrayList<>(KEY);
        if(REVISION.equals(table)){
            primary.add("revision");column(expected,"reference_json","json",false,false);
            column(expected,"route_json","json",true,false);column(expected,"binding_sha","char(64)",false,true);
        }
        var actual=new HashMap<String,List<String>>();
        for(var row:rows(c,"SELECT column_name,column_type,is_nullable,COALESCE(collation_name,''),COALESCE(column_default,''),extra FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=?",table))
            actual.put(row.getFirst(),row.subList(1,row.size()));
        if(!expected.equals(actual))throw drift();
        if(!rows(c,"SELECT column_name,non_unique,COALESCE(CAST(sub_part AS CHAR),'') FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name=? AND index_name='PRIMARY' ORDER BY seq_in_index",table)
                .equals(primary.stream().map(k->List.of(k,"0","")).toList()))throw drift();
        if(HEAD.equals(table)){
            var columns=new ArrayList<>(KEY);columns.add("revision");
            if(!rows(c,"SELECT column_name,referenced_table_name,referenced_column_name FROM information_schema.key_column_usage WHERE table_schema=DATABASE() AND table_name=? AND constraint_name='fk_cost_route_head_revision' ORDER BY ordinal_position",table)
                    .equals(columns.stream().map(k->List.of(k,REVISION,k)).toList()))throw drift();
            var rules=rows(c,"SELECT update_rule,delete_rule FROM information_schema.referential_constraints WHERE constraint_schema=DATABASE() AND table_name=? AND constraint_name='fk_cost_route_head_revision'",table);
            if(rules.size()!=1 || rules.getFirst().stream().anyMatch(r->!Set.of("RESTRICT","NO ACTION").contains(r)))throw drift();
        }else{
            var check=rows(c,"SELECT cc.check_clause,tc.enforced FROM information_schema.table_constraints tc JOIN information_schema.check_constraints cc ON cc.constraint_schema=tc.constraint_schema AND cc.constraint_name=tc.constraint_name WHERE tc.constraint_schema=DATABASE() AND tc.table_name=? AND tc.constraint_name='ck_cost_route_revision_positive'",table);
            if(check.size()!=1 || !"YES".equals(check.getFirst().get(1)) || !"revision>0".equals(check.getFirst().getFirst().replaceAll("[`()\\s]","")))throw drift();
        }
    }
    private static void column(Map<String,List<String>> map,String name,String type,boolean nullable,boolean text){
        map.put(name,List.of(type,nullable?"YES":"NO",text?COLLATION:"","",""));
    }
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
    private static IllegalStateException drift(){return new IllegalStateException("COST_ROUTE_BINDING_SCHEMA_MISMATCH");}
}
