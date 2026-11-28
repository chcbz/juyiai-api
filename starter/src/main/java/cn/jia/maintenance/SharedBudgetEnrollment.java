package cn.jia.maintenance;

import cn.jia.core.config.ProcessTenantConfiguration;
import cn.jia.core.security.FixedTenantAdmissionPolicy;
import cn.jia.economy.mapper.GroupBaselineMaintenanceMapper;
import cn.jia.economy.mapper.ScopedGroupPolicyMapper;
import cn.jia.economy.mapper.BudgetOriginalImportMapper;
import cn.jia.economy.service.impl.BudgetOriginalImport;
import java.io.IOException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
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

/** Explicit maintenance operations on externally approved originals. No approval creation, spending, scheduler or HTTP. */
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
    record ImportCommand(String sha,Path objects) {}
    static ImportCommand parseImport(String[] args){
        if(args.length!=4 || !"--import-approved".equals(args[0]) || !sha(args[1]) || !"--objects".equals(args[2]))
            throw new IllegalArgumentException("Usage: --import-approved ORIGINAL_SHA256 --objects PRIVATE_DIRECTORY");
        return new ImportCommand(args[1],Path.of(args[3]));
    }
    static String readOriginal(ImportCommand command) throws IOException {
        Path root=command.objects().toAbsolutePath().normalize();
        if(!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS) || !root.equals(root.toRealPath())
                || !Files.getPosixFilePermissions(root,LinkOption.NOFOLLOW_LINKS).equals(PosixFilePermissions.fromString("rwx------")))
            throw new IOException("Expected private original directory");
        Path source=root.resolve(command.sha());
        if(!Files.isRegularFile(source,LinkOption.NOFOLLOW_LINKS))throw new IOException("Expected original file");
        try(var in=Files.newInputStream(source,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){
            byte[] bytes=in.readNBytes(BudgetOriginalImport.MAX_BYTES+1);
            if(bytes.length==0 || bytes.length>BudgetOriginalImport.MAX_BYTES)throw new IOException("Invalid original size");
            String body=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if(!command.sha().equals(cn.jia.core.util.CanonicalValueEncoding.sha256(bytes)))throw new IOException("Original digest differs");
            return body;
        }
    }
    public static void main(String[] args) throws IOException {
        boolean importing=args.length>0 && "--import-approved".equals(args[0]);
        var original=importing?parseImport(args):null;
        var body=original==null?null:readOriginal(original);
        var command=importing?null:parse(args);
        var app=new SpringApplication(Context.class);app.setWebApplicationType(WebApplicationType.NONE);
        app.setAdditionalProfiles("shared-budget-enrollment");
        try(var ctx=app.run()){
            var scope=ctx.getBean(FixedTenantAdmissionPolicy.class);
            if(scope.clientId()==null)throw new IllegalStateException("A fixed maintenance client is required");
            var domain=new EconomyScope(scope.businessTenantId(),scope.clientId());
            if(importing){
                try {
                    var kind=ctx.getBean(BudgetOriginalImport.class).importApproved(domain,original.sha(),body);
                    System.out.println("IMPORTED_OR_IDENTICAL_REPLAY "+kind+" "+original.sha());
                }catch(RuntimeException rejected){
                    // JSON/SQL exceptions may contain private original bytes. Do not propagate them to CLI logs.
                    throw new IllegalStateException("Budget original import rejected: "+rejected.getClass().getSimpleName());
                }
            }else if(command.select()){
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
    @Import({ProcessTenantConfiguration.class,GroupPublicationEnrollment.class,DatabaseScopedGroupPolicies.class,BudgetOriginalImport.class})
    public static class Context {
        @Bean DataSourceTransactionManager enrollmentTx(DataSource source){return new DataSourceTransactionManager(source);}
        @Bean ObjectMapper enrollmentJson(){return new ObjectMapper();}
        @Bean SqlSessionTemplate enrollmentSession(DataSource ds)throws Exception{
            var config=new MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);
            config.addMapper(GroupBaselineMaintenanceMapper.class);config.addMapper(ScopedGroupPolicyMapper.class);config.addMapper(BudgetOriginalImportMapper.class);
            var factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(ds);factory.setConfiguration(config);
            return new SqlSessionTemplate(factory.getObject());
        }
        @Bean GroupBaselineMaintenanceMapper enrollmentSql(SqlSessionTemplate session){return session.getMapper(GroupBaselineMaintenanceMapper.class);}
        @Bean BudgetOriginalImportMapper originalSql(SqlSessionTemplate session){return session.getMapper(BudgetOriginalImportMapper.class);}
        @Bean ScopedGroupPolicyMapper policySql(SqlSessionTemplate session){return session.getMapper(ScopedGroupPolicyMapper.class);}
    }
}
