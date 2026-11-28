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
    record ExportRequest(cn.jia.economy.task.TaskBillingService.Task task,
            cn.jia.economy.task.TaskHistoryCatalog.Revision source,cn.jia.economy.usage.BenchmarkSampleEvidence.Pin pin) {}
    static ExportRequest parseExport(String body){
        var json=tools.jackson.databind.json.JsonMapper.builder(tools.jackson.core.json.JsonFactory.builder()
                .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
                .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                    tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    tools.jackson.databind.DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                    tools.jackson.databind.DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
                .disable(tools.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
        var r=json.readValue(body,ExportRequest.class);
        if(r.source().sampleId().isBlank() || r.source().revision()<1 || r.pin().caseId().isBlank()
                || !sha(r.pin().manifestSha()) || !sha(r.pin().reviewSha()) || !sha(r.pin().fact()))
            throw new IllegalArgumentException("Invalid scoped observation request");
        return r;
    }
    static void writeObservation(Path root,cn.jia.economy.task.TaskCalibrationObservations.Frozen artifact) throws IOException {
        byte[] bytes=artifact.json().getBytes(StandardCharsets.UTF_8);
        if(!sha(artifact.sha256()) || !artifact.sha256().equals(cn.jia.core.util.CanonicalValueEncoding.sha256(bytes)))
            throw new IOException("Invalid observation digest");
        Path tmp=Files.createTempFile(root,".observation-",".tmp",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            try(var file=java.nio.channels.FileChannel.open(tmp,StandardOpenOption.WRITE)){
                var buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())file.write(buffer);file.force(true);
            }
            try { Files.createLink(root.resolve(artifact.sha256()),tmp); }
            catch(FileAlreadyExistsException exists){
                var target=root.resolve(artifact.sha256());
                if(!Files.isRegularFile(target,LinkOption.NOFOLLOW_LINKS))throw new IOException("Conflicting observation");
                try(var in=Files.newInputStream(target,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){
                    if(!java.util.Arrays.equals(bytes,in.readNBytes(bytes.length+1)))throw new IOException("Conflicting observation");
                }
            }
        } finally {Files.deleteIfExists(tmp);}
        try(var dir=java.nio.channels.FileChannel.open(root,StandardOpenOption.READ)){dir.force(true);}
    }
    public static void main(String[] args) throws IOException {
        boolean reference=args.length>0 && "--export-scoped-reference".equals(args[0]);
        boolean exporting=reference || args.length>0 && "--export-reviewed".equals(args[0]);
        boolean importing=args.length>0 && "--import-approved".equals(args[0]);
        var readArgs=args.clone();if(exporting)readArgs[0]="--import-approved";
        var original=importing||exporting?parseImport(readArgs):null;
        var body=original==null?null:readOriginal(original);
        ExportRequest request=null;
        if(exporting)try {request=parseExport(body);}catch(RuntimeException rejected){throw new IllegalArgumentException("Invalid scoped observation request");}
        var command=importing||exporting?null:parse(args);
        var app=new SpringApplication(Context.class);app.setWebApplicationType(WebApplicationType.NONE);
        app.setAdditionalProfiles("shared-budget-enrollment");
        try(var ctx=app.run()){
            var scope=ctx.getBean(FixedTenantAdmissionPolicy.class);
            if(scope.clientId()==null)throw new IllegalStateException("A fixed maintenance client is required");
            var domain=new EconomyScope(scope.businessTenantId(),scope.clientId());
            if(exporting){
                try {
                    if(!domain.equals(request.task().scope()))throw new IllegalArgumentException("Observation domain differs");
                    if(reference){
                        var result=ctx.getBean(cn.jia.economy.service.impl.ScopedBenchmarkReferenceExport.class).freeze(request.task(),request.source(),request.pin(),
                                ctx.getBean(cn.jia.economy.usage.ConfirmedSampleEvidence.Decoder.class));
                        writeObservation(original.objects().toRealPath(),result.observation());writeObservation(original.objects().toRealPath(),result.reference());
                        writeObservation(original.objects().toRealPath(),result.provenance());
                        System.out.println("EXPORTED_SCOPED_REFERENCE "+result.reference().sha256()+" SOURCE "+result.provenance().sha256());
                    }else{
                    var observation=ctx.getBean(cn.jia.economy.usage.BenchmarkSampleEvidence.class).freezeScopedReviewed(
                            request.task(),request.source(),request.pin(),ctx.getBean(cn.jia.economy.usage.ConfirmedSampleEvidence.Decoder.class));
                    writeObservation(original.objects().toRealPath(),observation);
                    System.out.println("EXPORTED_REVIEWED_OBSERVATION "+observation.sha256());
                    }
                }catch(RuntimeException rejected){throw new IllegalStateException("Scoped observation export rejected: "+rejected.getClass().getSimpleName());}
            }else if(importing){
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
    @Import({ProcessTenantConfiguration.class,GroupPublicationEnrollment.class,DatabaseScopedGroupPolicies.class,BudgetOriginalImport.class,
        cn.jia.economy.service.impl.ConfirmedSampleEvidenceReader.class,cn.jia.economy.service.impl.ScopedBenchmarkReferenceExport.class,cn.jia.economy.service.impl.DatabaseTaskPricingService.class,cn.jia.economy.service.impl.UsageCostServiceImpl.class,
        cn.jia.agent.service.usage.NativeSampleResponseDecoder.class,cn.jia.agent.dao.impl.PersonalWorkspaceExecutionDaoImpl.class,
        cn.jia.agent.dao.impl.SkillExecutionDaoImpl.class})
    public static class Context {
        @Bean DataSourceTransactionManager enrollmentTx(DataSource source){return new DataSourceTransactionManager(source);}
        @Bean ObjectMapper enrollmentJson(){return new ObjectMapper();}
        @Bean SqlSessionTemplate enrollmentSession(DataSource ds)throws Exception{
            var config=new MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);
            config.addMapper(GroupBaselineMaintenanceMapper.class);config.addMapper(ScopedGroupPolicyMapper.class);config.addMapper(BudgetOriginalImportMapper.class);
            for(var type:java.util.List.of(cn.jia.economy.mapper.EconomyPriceCatalogMapper.class,cn.jia.economy.mapper.ConfirmedSampleMapper.class,cn.jia.economy.mapper.EconomyUsageCostMapper.class,
                    cn.jia.agent.mapper.PersonalWorkspaceExecutionMapper.class,cn.jia.agent.mapper.PersonalWorkspaceExecutionInputMapper.class,
                    cn.jia.agent.mapper.PersonalWorkspaceExecutionOutputMapper.class,cn.jia.agent.mapper.SkillExecutionMapper.class))config.addMapper(type);
            var factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(ds);factory.setConfiguration(config);
            return new SqlSessionTemplate(factory.getObject());
        }
        @Bean cn.jia.economy.mapper.EconomyPriceCatalogMapper pricesSql(SqlSessionTemplate s){return s.getMapper(cn.jia.economy.mapper.EconomyPriceCatalogMapper.class);}
        @Bean cn.jia.economy.mapper.ConfirmedSampleMapper observationSql(SqlSessionTemplate s){return s.getMapper(cn.jia.economy.mapper.ConfirmedSampleMapper.class);}
        @Bean cn.jia.economy.mapper.EconomyUsageCostMapper usageSql(SqlSessionTemplate s){return s.getMapper(cn.jia.economy.mapper.EconomyUsageCostMapper.class);}
        @Bean cn.jia.agent.mapper.PersonalWorkspaceExecutionMapper executionsSql(SqlSessionTemplate s){return s.getMapper(cn.jia.agent.mapper.PersonalWorkspaceExecutionMapper.class);}
        @Bean cn.jia.agent.mapper.PersonalWorkspaceExecutionInputMapper inputsSql(SqlSessionTemplate s){return s.getMapper(cn.jia.agent.mapper.PersonalWorkspaceExecutionInputMapper.class);}
        @Bean cn.jia.agent.mapper.PersonalWorkspaceExecutionOutputMapper outputsSql(SqlSessionTemplate s){return s.getMapper(cn.jia.agent.mapper.PersonalWorkspaceExecutionOutputMapper.class);}
        @Bean cn.jia.agent.mapper.SkillExecutionMapper skillsSql(SqlSessionTemplate s){return s.getMapper(cn.jia.agent.mapper.SkillExecutionMapper.class);}
        @Bean GroupBaselineMaintenanceMapper enrollmentSql(SqlSessionTemplate session){return session.getMapper(GroupBaselineMaintenanceMapper.class);}
        @Bean BudgetOriginalImportMapper originalSql(SqlSessionTemplate session){return session.getMapper(BudgetOriginalImportMapper.class);}
        @Bean ScopedGroupPolicyMapper policySql(SqlSessionTemplate session){return session.getMapper(ScopedGroupPolicyMapper.class);}
    }
}
