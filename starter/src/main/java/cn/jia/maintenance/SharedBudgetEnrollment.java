package cn.jia.maintenance;

import cn.jia.core.config.ProcessTenantConfiguration;
import cn.jia.core.security.FixedTenantAdmissionPolicy;
import cn.jia.economy.mapper.GroupBaselineMaintenanceMapper;
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
    public static void main(String[] args){
        if(args.length!=2 || !"--register-approved".equals(args[0]) || !args[1].matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Usage: --register-approved APPROVAL_SHA256");
        var app=new SpringApplication(Context.class);app.setWebApplicationType(WebApplicationType.NONE);
        app.setAdditionalProfiles("shared-budget-enrollment");
        try(var ctx=app.run()){
            var scope=ctx.getBean(FixedTenantAdmissionPolicy.class);
            if(scope.clientId()==null)throw new IllegalStateException("A fixed maintenance client is required");
            ctx.getBean(GroupPublicationEnrollment.class).register(new EconomyScope(scope.businessTenantId(),scope.clientId()),args[1]);
            System.out.println("REGISTERED_OR_IDENTICAL_REPLAY "+args[1]);
        }
    }
    @Configuration(proxyBeanMethods=false) @Profile("shared-budget-enrollment")
    @EnableTransactionManagement(proxyTargetClass=true) @ImportAutoConfiguration(DataSourceAutoConfiguration.class)
    @Import({ProcessTenantConfiguration.class,GroupPublicationEnrollment.class})
    public static class Context {
        @Bean DataSourceTransactionManager enrollmentTx(DataSource source){return new DataSourceTransactionManager(source);}
        @Bean ObjectMapper enrollmentJson(){return new ObjectMapper();}
        @Bean GroupBaselineMaintenanceMapper enrollmentSql(DataSource ds)throws Exception{
            var config=new MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);config.addMapper(GroupBaselineMaintenanceMapper.class);
            var factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(ds);factory.setConfiguration(config);
            return new SqlSessionTemplate(factory.getObject()).getMapper(GroupBaselineMaintenanceMapper.class);
        }
    }
}
