package cn.jia.maintenance;

import cn.jia.core.config.ProcessTenantConfiguration;
import cn.jia.core.security.FixedTenantAdmissionPolicy;
import cn.jia.economy.mapper.GroupBaselineMaintenanceMapper;
import cn.jia.economy.mapper.ScopedGroupPolicyMapper;
import cn.jia.economy.service.impl.DatabaseScopedGroupPolicies;
import cn.jia.economy.service.EconomyScope;
import cn.jia.economy.service.impl.GroupPublicationEnrollment;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import javax.sql.DataSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import tools.jackson.databind.ObjectMapper;

/** One explicit, previously approved receipt. No authority import, spending, scheduler or HTTP. */
public final class SharedBudgetEnrollment {
    record Command(boolean select,String approvalSha,String expectedApprovalSha) {}
    static Command parse(String[] args){
        if(args.length==2 && "--register-approved".equals(args[0]) && sha(args[1]))
            return new Command(false,args[1],null);
        if(args.length==4 && "--select-approved".equals(args[0]) && sha(args[1])
                && "--expected-approval".equals(args[2]) && ("NONE".equals(args[3]) || sha(args[3])))
            return new Command(true,args[1],"NONE".equals(args[3])?null:args[3]);
        throw new IllegalArgumentException("Usage: --register-approved APPROVAL_SHA256 | --select-approved APPROVAL_SHA256 --expected-approval NONE|PREVIOUS_SHA256");
    }
    private static boolean sha(String value){return value!=null && value.matches("[0-9a-f]{64}");}
    public static void main(String[] args){
        var command=parse(args);
        var app=new SpringApplication(Context.class);app.setWebApplicationType(WebApplicationType.NONE);
        app.setAdditionalProfiles("shared-budget-enrollment");
        try(var ctx=app.run()){
            var scope=ctx.getBean(FixedTenantAdmissionPolicy.class);
            if(scope.clientId()==null)throw new IllegalStateException("A fixed maintenance client is required");
            var domain=new EconomyScope(scope.businessTenantId(),scope.clientId());
            if(command.select()){
                var selected=ctx.getBean(DatabaseScopedGroupPolicies.class).selectApproved(domain,command.approvalSha(),command.expectedApprovalSha());
                System.out.println("SELECTED_OR_IDENTICAL_REPLAY "+selected.approvalSha());
            }else{
                ctx.getBean(GroupPublicationEnrollment.class).register(domain,command.approvalSha());
                System.out.println("REGISTERED_OR_IDENTICAL_REPLAY "+command.approvalSha());
            }
        }
    }
    @Configuration(proxyBeanMethods=false) @Profile("shared-budget-enrollment")
    @EnableTransactionManagement(proxyTargetClass=true) @ImportAutoConfiguration(DataSourceAutoConfiguration.class)
    @Import({ProcessTenantConfiguration.class,GroupPublicationEnrollment.class,DatabaseScopedGroupPolicies.class})
    public static class Context {
        @Bean DataSourceTransactionManager enrollmentTx(DataSource source){return new DataSourceTransactionManager(source);}
        @Bean ObjectMapper enrollmentJson(){return new ObjectMapper();}
        @Bean SqlSessionTemplate enrollmentSession(DataSource ds)throws Exception{
            var config=new MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);
            config.addMapper(GroupBaselineMaintenanceMapper.class);config.addMapper(ScopedGroupPolicyMapper.class);
            var factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(ds);factory.setConfiguration(config);
            return new SqlSessionTemplate(factory.getObject());
        }
        @Bean GroupBaselineMaintenanceMapper enrollmentSql(SqlSessionTemplate session){return session.getMapper(GroupBaselineMaintenanceMapper.class);}
        @Bean ScopedGroupPolicyMapper policySql(SqlSessionTemplate session){return session.getMapper(ScopedGroupPolicyMapper.class);}
    }
}
