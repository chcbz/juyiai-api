package cn.jia.economy.service.impl;

import cn.jia.economy.mapper.GroupBaselineMaintenanceMapper;
import cn.jia.economy.mapper.GroupBaselineMaintenanceMapper.Member;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.ObjectMapper;

/** Maintenance only. Never issues approval, dispatch, rate or online quote. Newly registered groups start unpublished. */
@Service
public class GroupPublicationEnrollment {
    private final GroupBaselineMaintenanceMapper db;private final ObjectMapper json;
    public GroupPublicationEnrollment(GroupBaselineMaintenanceMapper db,ObjectMapper json){this.db=db;this.json=json;}
    record Approval(String purpose,String split,String tenant,String client,String groupSha,String policySha,String sourceOwner,
            String sourceTask,String caseId,String sampleId,long evidenceRevision,String manifestSha,String reviewSha,String approvalRef){
        @Override public String toString(){return "AggregateApproval[private]";}
    }
    static String digest(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
    static final class NotApproved extends RuntimeException{}
    Member read(String sha){
        if(sha==null||!sha.matches("[0-9a-f]{64}"))throw new NotApproved();var raw=db.approvedPublication(sha);return decode(sha,raw);
    }
    Member decode(String sha,String raw){
        if(raw==null)throw new NotApproved();
        if(!sha.equals(digest(raw)))throw new IllegalStateException("Damaged aggregate approval");var a=json.readValue(raw,Approval.class);
        if(!"GROUP_AGGREGATE".equals(a.purpose())||!"TRAIN".equals(a.split())||a.evidenceRevision()<1)throw new NotApproved();
        for(var v:new String[]{a.groupSha(),a.policySha(),a.sampleId(),a.manifestSha(),a.reviewSha()})if(v==null||!v.matches("[a-f0-9]{64}"))throw new NotApproved();
        for(var v:new String[]{a.tenant(),a.client(),a.sourceOwner(),a.sourceTask(),a.caseId(),a.approvalRef()})if(v==null||v.isBlank())throw new NotApproved();
        return new Member(a.tenant(),a.client(),a.groupSha(),a.policySha(),a.sourceOwner(),a.sourceTask(),a.caseId(),a.sampleId(),a.evidenceRevision(),a.manifestSha(),a.reviewSha(),sha);
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void register(cn.jia.economy.service.EconomyScope scope,String approvalSha){
        var m=read(approvalSha);
        if(!m.tenant().equals(scope.tenantId())||!m.client().equals(scope.clientId()))throw new NotApproved();
        var original=db.lockCaseOriginal(m);if(original==null||!m.reviewSha().equals(db.lockReview(m)))throw new NotApproved();
        if(cn.jia.economy.task.ScopedBenchmarkManifest.SCHEMA.equals(json.readTree(original).path("schema").asText())) {
            requireScopedSource(m,original);
        } else if(!m.sampleId().equals(m.manifestSha())||!Objects.equals(m.evidenceRevision(),db.lockSample(m)))throw new NotApproved();
        db.ensureDomain(m);var h=db.lockMemberHead(m);
        if(h!=null&&("RETIRED".equals(h.status())||!m.policySha().equals(h.policySha())))throw new NotApproved();
        if(h!=null && approvalSha.equals(db.existingMember(m)))return;
        if((h==null?db.firstGroup(m):db.dirtyGroup(m))!=1)throw new IllegalStateException("Group registration changed");
        db.registerMember(m);if(!approvalSha.equals(db.existingMember(m)))throw new IllegalStateException("Ambiguous group membership");
    }
    private void requireScopedSource(Member m,String original){
        var manifest=TaskBenchmarkAdmissionService.decodeScoped(original,m.manifestSha());
        var task=manifest.task();var pin=manifest.scopePin();var group=manifest.group();
        if(manifest.purpose()!=cn.jia.economy.task.ScopedBenchmarkManifest.Purpose.TRAIN
                || !task.scope().tenantId().equals(m.tenant()) || !task.scope().clientId().equals(m.client())
                || !task.ownerJiacn().equals(m.sourceOwner()) || !task.taskId().equals(m.sourceTask())
                || !manifest.caseId().equals(m.caseId()) || !group.key().equals(m.groupSha())
                || !group.policySha().equals(m.policySha()))throw new NotApproved();
        var sample=db.lockScopedSample(m);
        if(sample==null || sample.evidenceRevision()!=m.evidenceRevision()
                || !pin.scopeSha256().equals(sample.scopeSha()) || !group.scopeShapeSha().equals(sample.shapeSha())
                || !group.key().equals(sample.groupSha()) || !group.policySha().equals(sample.policySha())
                || !pin.requirementSha256().equals(sample.requirementSha()) || !pin.inputSnapshotDigest().equals(sample.inputSha()))throw new NotApproved();
        // Quote proves only the original paid scope/agent binding, never actual execution or cost.
        var quote=json.readValue(sample.quoteJson(),cn.jia.economy.task.ScopedTaskQuoteSnapshot.class);
        if(!pin.equals(quote.scopePin()) || !manifest.agentId().equals(quote.binding().agentId())
                || !quote.featureSha().equals(sample.featureSha()))throw new NotApproved();
    }

}
