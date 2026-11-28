package cn.jia.economy.service.impl;

import cn.jia.economy.mapper.GroupBaselineMaintenanceMapper;
import cn.jia.economy.mapper.GroupBaselineMaintenanceMapper.Work;
import cn.jia.economy.pricing.*;
import cn.jia.economy.service.EconomyScope;
import cn.jia.economy.task.TaskBudgetEstimator;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.ObjectMapper;

/** Restricted background publisher: preparation performs no writes; publish updates all three records or none. */
@Service
public class GroupBaselinePublisher {
    private final GroupBaselineMaintenanceMapper db;
    private final ObjectMapper json;
    public GroupBaselinePublisher(GroupBaselineMaintenanceMapper db,ObjectMapper json){this.db=db;this.json=json;}
    /** Created only by the evidence reader after checking a TRAIN member against its approved revision. */
    record Eligible(String sampleId,long evidenceRevision,String manifestSha,String reviewSha,String approvalSha,
                    String usageSha,List<MeasuredTaskRepricing.Fact> facts) { Eligible {facts=List.copyOf(facts);} }
    record ScopedEligible(String sampleId,long evidenceRevision,String manifestSha,String reviewSha,String approvalSha,String usageSha,List<MeasuredTaskRepricing.ResourceFact> facts){
        ScopedEligible {facts=List.copyOf(facts);}
    }
    record Line(String sampleId,long evidenceRevision,String manifestSha,String reviewSha,String approvalSha,String usageSha,BigDecimal yuan) {}
    record Evidence(String version,String policySha,String studyApprovalSha,String tariffCanonicalBase64,List<Line> samples) {}
    record Prepared(Work work,String claim,String tariffSha,int count,BigDecimal total,String roster,String evidenceSha) {}
    static class MoreInputRequired extends RuntimeException {}
    Prepared calculate(Work work,String claim,GroupTariffSnapshot tariff,List<Eligible> members,int minSupport,String studyApprovalSha) {
        if(studyApprovalSha==null || !studyApprovalSha.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Missing reviewed study identity");
        if(minSupport<=0)throw new IllegalArgumentException("Invalid pricing policy");
        if(members.size()<minSupport)throw new MoreInputRequired();
        var lines=new ArrayList<Line>();String last=null;var sum=BigDecimal.ZERO;
        for(var m:members.stream().sorted(Comparator.comparing(Eligible::sampleId)).toList()) {
            for(var s:List.of(m.sampleId(),m.manifestSha(),m.reviewSha(),m.approvalSha(),m.usageSha()))
                if(!s.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid group evidence identity");
            if(m.evidenceRevision()<=0 || m.sampleId().equals(last))throw new IllegalArgumentException("Invalid group member revision or duplication");
            last=m.sampleId(); var c=MeasuredTaskRepricing.reprice(tariff.reference(),m.facts()).yuan();sum=sum.add(c);
            lines.add(new Line(last,m.evidenceRevision(),m.manifestSha(),m.reviewSha(),m.approvalSha(),m.usageSha(),c));
        }
        sum=sum.setScale(30,RoundingMode.UNNECESSARY);if(sum.precision()>65)throw new ArithmeticException("Group total exceeds storage precision");
        GroupMeanPricing.amountWen(sum,lines.size()); // reject a result that cannot be issued in integer wen
        var roster=json.writeValueAsString(new Evidence("group-roster-1",work.policySha(),studyApprovalSha,tariff.canonicalBase64(),lines));
        try { return new Prepared(work,claim,tariff.tariffSha(),lines.size(),sum,roster,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(roster.getBytes(StandardCharsets.UTF_8)))); }
        catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
    Prepared calculateScoped(Work work,String claim,GroupTariffSnapshot.Scoped tariff,List<ScopedEligible> members,int minSupport,String studyApprovalSha){
        if(studyApprovalSha==null||!studyApprovalSha.matches("[a-f0-9]{64}")||minSupport<=0)throw new IllegalArgumentException("Invalid scoped study");
        if(members.size()<minSupport)throw new MoreInputRequired();
        var lines=new ArrayList<Line>();String last=null;var sum=BigDecimal.ZERO;
        for(var m:members.stream().sorted(Comparator.comparing(ScopedEligible::sampleId)).toList()){
            for(var value:List.of(m.sampleId(),m.manifestSha(),m.reviewSha(),m.approvalSha(),m.usageSha()))if(!value.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid scoped evidence identity");
            if(m.evidenceRevision()<=0||m.sampleId().equals(last))throw new IllegalArgumentException("Invalid scoped member revision or duplication");
            last=m.sampleId();var cost=MeasuredTaskRepricing.reprice(tariff.reference(),m.facts()).yuan();sum=sum.add(cost);
            lines.add(new Line(last,m.evidenceRevision(),m.manifestSha(),m.reviewSha(),m.approvalSha(),m.usageSha(),cost));
        }
        sum=sum.setScale(30,RoundingMode.UNNECESSARY);if(sum.precision()>65)throw new ArithmeticException("Group total exceeds storage precision");
        GroupMeanPricing.amountWen(sum,lines.size());
        var roster=json.writeValueAsString(new Evidence("scope-group-roster-1",work.policySha(),studyApprovalSha,tariff.canonicalBase64(),lines));
        return new Prepared(work,claim,tariff.tariffSha(),lines.size(),sum,roster,GroupPublicationEnrollment.digest(roster));
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void publish(Prepared p) {
        var w=p.work();if(!Objects.equals(db.lockDomain(new EconomyScope(w.tenantId(),w.clientId())),w.bookEpoch()) ||
            !Objects.equals(db.lockOwned(w,p.claim()),w.generation()))throw new TaskBudgetEstimator.Stale();
        if(db.insertRevision(w,p.tariffSha(),p.count(),p.total(),p.evidenceSha())!=1 || db.insertEvidence(w,p.roster(),p.evidenceSha())!=1 ||
            db.publish(w,p.claim(),p.tariffSha())!=1)throw new TaskBudgetEstimator.Stale();
    }
}
