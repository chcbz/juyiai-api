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
 * enroll samples, activate sponsored spending, choose a policy, register membership or publish a price. */
public class BudgetOriginalImport {
    public static final int MAX_BYTES=1048576;
    public enum Kind { CASE, SPONSORSHIP, REVIEW, STUDY, AGGREGATE }
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
        if("benchmark-sponsorship-v1".equals(root.path("schema").asText(null)))return sponsorship(scope,sha,body);
        if("benchmark-review-v1".equals(root.path("schema").asText(null)))return review(scope,sha,body);
        if(ScopedBenchmarkManifest.SCHEMA.equals(root.path("schema").asText(null))){
            var manifest=TaskBenchmarkAdmissionService.decodeScoped(body,sha);
            require(scope.equals(manifest.task().scope()));id(manifest.task().ownerJiacn(),100);
            id(manifest.task().taskId(),100);id(manifest.caseId(),100);
            var existing=db.benchmark(manifest);
            if(existing!=null){same(existing,sha,body);return Kind.CASE;}
            // The separate unique task key must not silently substitute another case.
            require(db.taskBenchmark(manifest)==null && !db.hasActivity(manifest.task()));
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
    private ScopedBenchmarkManifest sourceCase(EconomyScope scope,String caseId,String sha){
        var original=db.sourceCase(scope,caseId);
        require(original!=null && !original.withdrawn() && sha.equals(original.sha()));
        var manifest=TaskBenchmarkAdmissionService.decodeScoped(original.body(),sha);
        require(scope.equals(manifest.task().scope()) && caseId.equals(manifest.caseId()));
        return manifest;
    }
    private Kind sponsorship(EconomyScope scope,String sha,String body){
        var authority=TaskBenchmarkAdmissionService.decodeSponsorship(body,sha);var task=authority.task();
        require(scope.equals(task.scope()));id(task.ownerJiacn(),100);id(task.taskId(),100);
        var manifest=sourceCase(scope,authority.caseId(),authority.manifestSha256());
        require(task.equals(manifest.task()) && authority.agentId().equals(manifest.agentId()));
        var previous=db.sponsorship(task);
        if(previous!=null){same(previous,sha,body);return Kind.SPONSORSHIP;}
        require(!db.hasActivity(task));
        try{require(db.insertSponsorship(task,sha,body)==1);}
        catch(DuplicateKeyException raced){same(db.sponsorship(task),sha,body);}
        // Only APPROVED is inserted. Task-root activation still independently checks exact
        // assignment/action/intent and rejects any prior charge/member/sample/capture.
        return Kind.SPONSORSHIP;
    }
    private Kind review(EconomyScope scope,String sha,String body){
        var review=JSON.readValue(body,cn.jia.economy.usage.BenchmarkSampleEvidence.Review.class);
        require("ACCEPTED".equals(review.decision()));id(review.caseId(),100);
        for(var pin:java.util.List.of(review.manifestSha(),review.terminalFactId(),review.membershipSha()))
            require(pin.matches("[a-f0-9]{64}"));
        require(!review.reviewerRef().isBlank() && !review.deliverableEvidenceRef().isBlank());
        var manifest=sourceCase(scope,review.caseId(),review.manifestSha());
        var sample=db.reviewedSample(manifest.task(),review);require(sample!=null);
        var previous=db.review(sample,sha);
        if(previous!=null){same(previous,sha,body);return Kind.REVIEW;}
        try{require(db.insertReview(sample,sha,body)==1);}
        catch(DuplicateKeyException raced){same(db.review(sample,sha),sha,body);}
        // An accepted deliverable review is not proof of complete costs. The existing evidence
        // reader still requires every closed member and its authenticated source records.
        return Kind.REVIEW;
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
