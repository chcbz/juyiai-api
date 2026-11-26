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
        if(sha==null||!sha.matches("[0-9a-f]{64}"))throw new NotApproved();var raw=db.approvedPublication(sha);if(raw==null)throw new NotApproved();
        if(!sha.equals(digest(raw)))throw new IllegalStateException("Damaged aggregate approval");var a=json.readValue(raw,Approval.class);
        if(!"GROUP_AGGREGATE".equals(a.purpose())||!"TRAIN".equals(a.split())||a.evidenceRevision()<1||!Objects.equals(a.sampleId(),a.manifestSha()))throw new NotApproved();
        for(var v:new String[]{a.groupSha(),a.policySha(),a.sampleId(),a.manifestSha(),a.reviewSha()})if(v==null||!v.matches("[a-f0-9]{64}"))throw new NotApproved();
        for(var v:new String[]{a.tenant(),a.client(),a.sourceOwner(),a.sourceTask(),a.caseId(),a.approvalRef()})if(v==null||v.isBlank())throw new NotApproved();
        return new Member(a.tenant(),a.client(),a.groupSha(),a.policySha(),a.sourceOwner(),a.sourceTask(),a.caseId(),a.sampleId(),a.evidenceRevision(),a.manifestSha(),a.reviewSha(),sha);
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void register(String approvalSha){
        var m=read(approvalSha);
        if(!m.manifestSha().equals(db.lockCase(m))||!m.reviewSha().equals(db.lockReview(m))||!Objects.equals(m.evidenceRevision(),db.lockSample(m)))throw new NotApproved();
        db.ensureDomain(m);var h=db.lockMemberHead(m);
        if(h!=null&&("RETIRED".equals(h.status())||!m.policySha().equals(h.policySha())))throw new NotApproved();
        if(h!=null && approvalSha.equals(db.existingMember(m)))return;
        if((h==null?db.firstGroup(m):db.dirtyGroup(m))!=1)throw new IllegalStateException("Group registration changed");
        db.registerMember(m);if(!approvalSha.equals(db.existingMember(m)))throw new IllegalStateException("Ambiguous group membership");
    }
}
