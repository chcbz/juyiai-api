package cn.jia.maintenance;

import cn.jia.economy.service.impl.GroupPublicationEnrollment;
import cn.jia.economy.service.impl.BudgetOriginalImport;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.io.TempDir;
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
    @TempDir Path temporary;
    @Test void originalImportReadsOnlyPinnedBoundedPrivateRegularUtf8Files() throws Exception {
        var root=temporary.toRealPath();Files.setPosixFilePermissions(root,PosixFilePermissions.fromString("rwx------"));
        String body="{\"schema\":\"fixture-only\"}",sha=cn.jia.core.util.CanonicalValueEncoding.sha256(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var command=SharedBudgetEnrollment.parseImport(new String[]{"--import-approved",sha,"--objects",root.toString()});
        assertThrows(java.io.IOException.class,()->SharedBudgetEnrollment.readOriginal(command));
        Files.writeString(root.resolve(sha),body);assertEquals(body,SharedBudgetEnrollment.readOriginal(command));
        Files.writeString(root.resolve(sha),"changed");assertThrows(java.io.IOException.class,()->SharedBudgetEnrollment.readOriginal(command));
        Files.write(root.resolve(sha),new byte[BudgetOriginalImport.MAX_BYTES+1]);assertThrows(java.io.IOException.class,()->SharedBudgetEnrollment.readOriginal(command));
        Files.write(root.resolve(sha),new byte[]{(byte)0xc3,(byte)0x28});assertThrows(java.io.IOException.class,()->SharedBudgetEnrollment.readOriginal(command));
        Files.delete(root.resolve(sha));Files.writeString(root.resolve("other"),body);Files.createSymbolicLink(root.resolve(sha),root.resolve("other"));
        assertThrows(java.io.IOException.class,()->SharedBudgetEnrollment.readOriginal(command));
        Files.delete(root.resolve(sha));Files.writeString(root.resolve(sha),body);
        Files.setPosixFilePermissions(root,PosixFilePermissions.fromString("rwxr-xr-x"));assertThrows(java.io.IOException.class,()->SharedBudgetEnrollment.readOriginal(command));
        for(String[] args:new String[][]{{"--import-approved",sha},{"--import-approved","{}","--objects",root.toString()},
                {"--approve",sha,"--objects",root.toString()},{"--import-approved",sha,"--objects",root.toString(),"extra"}})
            assertThrows(IllegalArgumentException.class,()->SharedBudgetEnrollment.parseImport(args));
    }
    @Test void scopedExportRequestIsPinnedNotAnApprovalOrCallerSelectedSplit() throws Exception {
        String raw="{\"task\":{\"scope\":{\"tenantId\":\"t\",\"clientId\":\"c\"},\"ownerJiacn\":\"owner\",\"taskId\":\"task\"},\"source\":{\"sampleId\":\"sample\",\"revision\":1},\"pin\":{\"caseId\":\"case\",\"manifestSha\":\""+"a".repeat(64)+"\",\"fact\":\""+"b".repeat(64)+"\",\"reviewSha\":\""+"c".repeat(64)+"\"}}";
        assertEquals("sample",SharedBudgetEnrollment.parseExport(raw).source().sampleId());
        for(String bad:new String[]{raw+"{}",raw.replace("\"revision\":1","\"revision\":\"1\""),raw.replace("\"revision\":1","\"revision\":0"),
                raw.replace("\"source\":","\"purpose\":\"TRAIN\",\"source\":"),raw.replace("\"sampleId\":\"sample\"","\"sampleId\":\"sample\",\"sampleId\":\"other\"")})
            assertThrows(RuntimeException.class,()->SharedBudgetEnrollment.parseExport(bad));
        var root=temporary.toRealPath();Files.setPosixFilePermissions(root,PosixFilePermissions.fromString("rwx------"));
        String body="{\"schema\":\"test-only-observation\"}",sha=cn.jia.core.util.CanonicalValueEncoding.sha256(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var frozen=new cn.jia.economy.task.TaskCalibrationObservations.Frozen(sha,body);
        SharedBudgetEnrollment.writeObservation(root,frozen);SharedBudgetEnrollment.writeObservation(root,frozen);
        assertEquals(body,Files.readString(root.resolve(sha)));
        assertEquals(PosixFilePermissions.fromString("rw-------"),Files.getPosixFilePermissions(root.resolve(sha)));
        Files.writeString(root.resolve(sha),"different");assertThrows(java.io.IOException.class,()->SharedBudgetEnrollment.writeObservation(root,frozen));
        assertEquals("different",Files.readString(root.resolve(sha)));
        Files.delete(root.resolve(sha));Files.createSymbolicLink(root.resolve(sha),root.resolve("absent"));
        assertThrows(java.io.IOException.class,()->SharedBudgetEnrollment.writeObservation(root,frozen));
        assertFalse(Files.exists(root.resolve("absent")));
    }
    @Test void isolatedGraphRegistersTransactionalDelegateButNoDdlOrWorkers(){
        var app=new ApplicationContextRunner().withUserConfiguration(SharedBudgetEnrollment.Context.class);
        app.run(c->{assertNull(c.getStartupFailure());assertTrue(c.getBeansOfType(DataSource.class).isEmpty());});
        app.withInitializer(c->c.getEnvironment().setActiveProfiles("shared-budget-enrollment"))
            .withPropertyValues("cyf.security.fixed-tenant.tenant-id=t","cyf.security.fixed-tenant.client-id=c","spring.datasource.url=jdbc:h2:mem:budgetEnrollment","spring.datasource.username=sa","spring.datasource.driver-class-name=org.h2.Driver")
            .run(c->{assertNull(c.getStartupFailure());assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(c.getBean(GroupPublicationEnrollment.class)));
                assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(c.getBean(DatabaseScopedGroupPolicies.class)));
                assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(c.getBean(BudgetOriginalImport.class)));
                assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(c.getBean(cn.jia.economy.usage.BenchmarkSampleEvidence.class)));
                assertInstanceOf(cn.jia.agent.service.usage.NativeSampleResponseDecoder.class,c.getBean(cn.jia.economy.usage.ConfirmedSampleEvidence.Decoder.class));
                assertTrue(c.getBeansOfType(org.springframework.scheduling.TaskScheduler.class).isEmpty());
                assertTrue(c.getBeansOfType(org.springframework.boot.ApplicationRunner.class).isEmpty());
                assertEquals(0,new JdbcTemplate(c.getBean(DataSource.class)).queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC'",Integer.class));});
    }
}
