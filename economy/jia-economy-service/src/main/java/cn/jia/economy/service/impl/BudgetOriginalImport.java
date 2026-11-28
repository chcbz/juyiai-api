package cn.jia.economy.service.impl;

import cn.jia.core.util.CanonicalValueEncoding;
import cn.jia.economy.mapper.BudgetOriginalImportMapper;
import cn.jia.economy.mapper.ScopedGroupPolicyMapper;
import cn.jia.economy.service.EconomyScope;
import cn.jia.economy.task.ScopedBenchmarkManifest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;

/** Maintenance-only import of externally authorized original bytes. Not component-scanned:
 * the isolated maintenance context must explicitly install it. Does not approve, confirm scope,
 * enroll samples, authorize spending, choose a policy, register membership or publish a price. */
public class BudgetOriginalImport {
    public static final int MAX_BYTES=1048576;
    public enum Kind { CASE, STUDY, AGGREGATE }
    private static final ObjectMapper JSON=JsonMapper.builder(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(24).maxStringLength(MAX_BYTES).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    private final BudgetOriginalImportMapper db;
    public BudgetOriginalImport(BudgetOriginalImportMapper db){this.db=db;}

    @Transactional
    public Kind importApproved(EconomyScope scope,String sha,String body){
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Original import transaction required");
        require(scope!=null);id(scope.tenantId(),50);id(scope.clientId(),50);
        require(sha!=null && sha.matches("[a-f0-9]{64}") && body!=null);
        byte[] bytes=CanonicalValueEncoding.utf8(body);
        require(bytes.length>0 && bytes.length<=MAX_BYTES && sha.equals(CanonicalValueEncoding.sha256(bytes)));
        var root=JSON.readTree(body);
        if(ScopedBenchmarkManifest.SCHEMA.equals(root.path("schema").asText(null))){
            var manifest=TaskBenchmarkAdmissionService.decodeScoped(body,sha);
            require(scope.equals(manifest.task().scope()));id(manifest.task().ownerJiacn(),100);
            id(manifest.task().taskId(),100);id(manifest.caseId(),100);
            var existing=db.benchmark(manifest);
            if(existing!=null){same(existing,sha,body);return Kind.CASE;}
            // The separate unique task key must not silently substitute another case.
            require(db.taskBenchmark(manifest)==null);
            try{require(db.insertBenchmark(manifest,sha,body)==1);}
            catch(DuplicateKeyException raced){same(db.benchmark(manifest),sha,body);}
            // Admission remains prospective: the original task-root consumer independently checks
            // scope/config plus no earlier charge, sample, member or capture before any execution.
            return Kind.CASE;
        }
        Kind kind;
        if(DatabaseScopedGroupPolicies.PURPOSE.equals(root.path("purpose").asText(null))){
            DatabaseScopedGroupPolicies.decodeOriginal(scope,new ScopedGroupPolicyMapper.Row(
                    scope.tenantId(),scope.clientId(),null,null,sha,body,(long)bytes.length,false));
            kind=Kind.STUDY;
        }else if("GROUP_AGGREGATE".equals(root.path("purpose").asText(null))){
            // Strict parsing first; reuse the same domain validation as the registration consumer.
            JSON.readValue(body,GroupPublicationEnrollment.Approval.class);
            var member=GroupPublicationEnrollment.decode(sha,body,JSON);
            require(scope.tenantId().equals(member.tenant()) && scope.clientId().equals(member.client()));
            kind=Kind.AGGREGATE;
        }else throw new IllegalArgumentException("Unsupported budget original");
        var existing=db.approval(sha);
        if(existing!=null){same(existing,sha,body);return kind;}
        try{require(db.insertApproval(sha,body)==1);}
        catch(DuplicateKeyException raced){same(db.approval(sha),sha,body);}
        return kind;
    }
    private static void same(BudgetOriginalImportMapper.Original existing,String sha,String body){
        require(existing!=null && !existing.withdrawn() && sha.equals(existing.sha()) && body.equals(existing.body()));
    }
    private static void id(String value,int max){
        require(value!=null && !value.isBlank() && value.equals(value.strip()) && value.codePointCount(0,value.length())<=max);
        CanonicalValueEncoding.utf8(value);
    }
    private static void require(boolean condition){if(!condition)throw new IllegalArgumentException("Budget original conflicts or is invalid");}
}
