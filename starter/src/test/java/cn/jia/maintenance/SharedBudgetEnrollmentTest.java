package cn.jia.maintenance;

import cn.jia.economy.service.impl.GroupPublicationEnrollment;
import cn.jia.economy.service.impl.DatabaseScopedGroupPolicies;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import static org.junit.jupiter.api.Assertions.*;

class SharedBudgetEnrollmentTest {
    @Test void commandAcceptsOnlyAnExistingReceiptReferenceNotAuthorityBytes(){
        assertThrows(IllegalArgumentException.class,()->SharedBudgetEnrollment.main(new String[]{"--register-approved","{}"}));
        assertThrows(IllegalArgumentException.class,()->SharedBudgetEnrollment.main(new String[]{"--approve","a".repeat(64)}));
    }
    @Test void selectionRequiresExplicitExpectedReferenceAndNeverAcceptsApprovalContents(){
        String a="a".repeat(64),b="b".repeat(64);
        assertEquals(new SharedBudgetEnrollment.Command(false,a,null),SharedBudgetEnrollment.parse(new String[]{"--register-approved",a}));
        assertEquals(new SharedBudgetEnrollment.Command(true,a,null),SharedBudgetEnrollment.parse(new String[]{"--select-approved",a,"--expected-approval","NONE"}));
        assertEquals(new SharedBudgetEnrollment.Command(true,a,b),SharedBudgetEnrollment.parse(new String[]{"--select-approved",a,"--expected-approval",b}));
        for(String[] args:new String[][]{{"--select-approved",a},{"--select-approved",a,"--expected-approval",""},
                {"--select-approved","{}","--expected-approval","NONE"},{"--select-approved",a,"--force","NONE"},
                {"--select-approved",a,"--expected-approval","NONE","extra"}})
            assertThrows(IllegalArgumentException.class,()->SharedBudgetEnrollment.parse(args));
    }
    @Test void isolatedGraphRegistersTransactionalDelegateButNoDdlOrWorkers(){
        var app=new ApplicationContextRunner().withUserConfiguration(SharedBudgetEnrollment.Context.class);
        app.run(c->{assertNull(c.getStartupFailure());assertTrue(c.getBeansOfType(DataSource.class).isEmpty());});
        app.withInitializer(c->c.getEnvironment().setActiveProfiles("shared-budget-enrollment"))
            .withPropertyValues("cyf.security.fixed-tenant.tenant-id=t","cyf.security.fixed-tenant.client-id=c","spring.datasource.url=jdbc:h2:mem:budgetEnrollment","spring.datasource.username=sa","spring.datasource.driver-class-name=org.h2.Driver")
            .run(c->{assertNull(c.getStartupFailure());assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(c.getBean(GroupPublicationEnrollment.class)));
                assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(c.getBean(DatabaseScopedGroupPolicies.class)));
                assertTrue(c.getBeansOfType(org.springframework.scheduling.TaskScheduler.class).isEmpty());
                assertTrue(c.getBeansOfType(org.springframework.boot.ApplicationRunner.class).isEmpty());
                assertEquals(0,new JdbcTemplate(c.getBean(DataSource.class)).queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC'",Integer.class));});
    }
}
