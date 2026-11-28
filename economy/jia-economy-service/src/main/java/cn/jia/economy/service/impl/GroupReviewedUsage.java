package cn.jia.economy.service.impl;

import cn.jia.economy.mapper.*;
import cn.jia.economy.mapper.GroupBaselineMaintenanceMapper.*;
import cn.jia.economy.pricing.*;
import cn.jia.economy.service.EconomyScope;
import cn.jia.economy.task.*;
import cn.jia.economy.usage.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;

/** Private worker read of one previously frozen membership; never enrolls or signs an offer. */
@Service
public class GroupReviewedUsage {
    private static final ObjectMapper CANON=JsonMapper.builder().enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
    private final GroupBaselineMaintenanceMapper db;private final GroupPublicationEnrollment approvals;
    private final ConfirmedSampleMapper samples;private final BenchmarkSampleEvidence evidence;
    public GroupReviewedUsage(GroupBaselineMaintenanceMapper db,GroupPublicationEnrollment approvals,ConfirmedSampleMapper samples,BenchmarkSampleEvidence evidence){
        this.db=db;this.approvals=approvals;this.samples=samples;this.evidence=evidence;
    }
    @Transactional(readOnly=true,propagation=Propagation.REQUIRES_NEW,isolation=Isolation.REPEATABLE_READ)
    public GroupBaselinePublisher.Eligible read(Work w,SourcePin pin,GroupMeanPricing.Policy policy,ConfirmedSampleEvidence.Decoder decoder){
        var m=approvals.decode(pin.approvalSha(),db.publicationSnapshot(pin.approvalSha()));
        require(w.tenantId().equals(m.tenant())&&w.clientId().equals(m.client())&&w.groupSha().equals(m.groupSha())&&w.policySha().equals(m.policySha())&&policy.sha().equals(w.policySha()));
        require(pin.equals(new SourcePin(m.sampleId(),m.sourceOwner(),m.sourceTask(),m.evidenceRevision(),m.manifestSha(),m.reviewSha(),m.approvalSha())));
        var task=new TaskBillingService.Task(new EconomyScope(m.tenant(),m.client()),m.sourceOwner(),m.sourceTask());
        var originals=samples.benchmarkOriginals(task,m.caseId(),m.manifestSha(),m.reviewSha());
        require(originals!=null&&m.manifestSha().equals(GroupPublicationEnrollment.digest(originals.manifest()))&&m.reviewSha().equals(GroupPublicationEnrollment.digest(originals.review())));
        var manifest=CANON.readValue(originals.manifest(),TaskBenchmarkAdmission.Manifest.class);
        var review=CANON.readValue(originals.review(),BenchmarkSampleEvidence.Review.class);
        require(task.equals(manifest.task())&&m.caseId().equals(manifest.caseId())&&manifest.groupInput()!=null);
        var group=classify(manifest,policy);
        require(w.groupSha().equals(GroupMeanPricing.key(group))&&manifest.execution().capturePolicyVersion().equals(group.capturePolicyVersion()));
        var measured=evidence.measureReviewed(task,m.caseId(),m.manifestSha(),review.terminalFactId(),m.reviewSha(),decoder);
        return measured(m,review.terminalFactId(),group,measured);
    }
    private record ScopedRow(CostRouteReference resource,ProjectedTaskOperation operation) {}
    @Transactional(readOnly=true,propagation=Propagation.REQUIRES_NEW,isolation=Isolation.REPEATABLE_READ)
    public GroupBaselinePublisher.ScopedEligible readScoped(Work w,SourcePin pin,GroupStudyPolicy.ScopedSelected policy,ConfirmedSampleEvidence.Decoder decoder){
        var m=approvals.decode(pin.approvalSha(),db.publicationSnapshot(pin.approvalSha()));
        require(w.tenantId().equals(m.tenant())&&w.clientId().equals(m.client())&&w.groupSha().equals(m.groupSha())&&w.policySha().equals(m.policySha())&&policy.approved().policy().sha().equals(w.policySha()));
        require(pin.equals(new SourcePin(m.sampleId(),m.sourceOwner(),m.sourceTask(),m.evidenceRevision(),m.manifestSha(),m.reviewSha(),m.approvalSha())));
        var task=new TaskBillingService.Task(new EconomyScope(m.tenant(),m.client()),m.sourceOwner(),m.sourceTask());
        var originals=samples.scopedBenchmarkOriginals(task,m.sampleId(),m.caseId(),m.manifestSha(),m.reviewSha());
        require(originals!=null&&m.manifestSha().equals(GroupPublicationEnrollment.digest(originals.manifest()))&&m.reviewSha().equals(GroupPublicationEnrollment.digest(originals.review())));
        var manifest=TaskBenchmarkAdmissionService.decodeScoped(originals.manifest(),m.manifestSha());
        require(manifest.purpose()==ScopedBenchmarkManifest.Purpose.TRAIN&&manifest.task().equals(task)&&manifest.caseId().equals(m.caseId())
                &&manifest.groupSha().equals(w.groupSha())&&manifest.policy().sha().equals(w.policySha()));
        var review=CANON.readValue(originals.review(),BenchmarkSampleEvidence.Review.class);
        var measured=evidence.measureScopedReviewed(task,m.sampleId(),m.caseId(),m.manifestSha(),review.terminalFactId(),m.reviewSha(),decoder);
        require(measured!=null);var a=measured.assessment();
        require(a.verdict()==ConfirmedSampleEvidence.Verdict.MATCHED&&a.members()>0&&a.evidenceRevision()==m.evidenceRevision()
                &&m.sampleId().equals(a.sampleId())&&Objects.equals(review.terminalFactId(),a.terminalFactId()));
        var rows=new ArrayList<ScopedRow>();
        for(var op:measured.operations()){
            require(policy.routes().containsKey(op.resource()));rows.add(new ScopedRow(op.resource(),ProjectedTaskOperation.from(op.operation(),CANON)));
        }
        require(!rows.isEmpty());rows.sort(Comparator.comparingLong((ScopedRow r)->r.operation().memberRevision()).thenComparing(r->r.operation().captureId()).thenComparing(r->r.operation().recordId()).thenComparing(r->r.resource().sha()));
        return new GroupBaselinePublisher.ScopedEligible(m.sampleId(),m.evidenceRevision(),m.manifestSha(),m.reviewSha(),m.approvalSha(),
                GroupPublicationEnrollment.digest(CANON.writeValueAsString(rows)),rows.stream().map(r->new MeasuredTaskRepricing.ResourceFact(r.resource(),r.operation().fact(CANON))).toList());
    }
    static GroupMeanPricing.Group classify(TaskBenchmarkAdmission.Manifest manifest,GroupMeanPricing.Policy policy){
        require(manifest.groupInput()!=null);
        var basis=manifest.groupInput();var features=manifest.features();var dims=new EnumMap<GroupMeanPricing.Dimension,Long>(GroupMeanPricing.Dimension.class);
        long bytes=0;for(var f:features.files()){require(f.byteLength()>=0);bytes=Math.addExact(bytes,f.byteLength());}
        dims.put(GroupMeanPricing.Dimension.INPUT_FILE_COUNT,(long)features.files().size());dims.put(GroupMeanPricing.Dimension.TOTAL_INPUT_BYTES,bytes);
        if(features.requestedDeliverables()!=null)dims.put(GroupMeanPricing.Dimension.REQUESTED_DELIVERABLES,features.requestedDeliverables().longValue());
        return policy.classify(basis.operation(),dims,basis.configuration()).orElseThrow(GroupBaselinePublisher.MoreInputRequired::new);
    }
    static GroupBaselinePublisher.Eligible measured(Member m,String terminal,GroupMeanPricing.Group group,ConfirmedSampleEvidence.Measured measured){
        require(measured!=null);var a=measured.assessment();
        require(a.verdict()==ConfirmedSampleEvidence.Verdict.MATCHED&&a.members()>0&&a.evidenceRevision()==m.evidenceRevision()&&m.sampleId().equals(a.sampleId())&&Objects.equals(terminal,a.terminalFactId()));
        boolean parent=false,child=false;var rows=new ArrayList<ProjectedTaskOperation>();
        for(var op:measured.operations()){
            var f=op.fact();require(group.routes().stream().anyMatch(r->r.kind().equals(f.kind())&&r.component().equals(f.component())));
            parent|="MODEL_RESPONSE".equals(f.kind())&&"INFERENCE".equals(f.component());child|="NATIVE_PROVIDER_CALL".equals(f.kind())&&group.operation().equals(f.component());
            rows.add(ProjectedTaskOperation.from(op,CANON));
        }
        require(parent&&child);
        rows.sort(Comparator.comparingLong(ProjectedTaskOperation::memberRevision).thenComparing(ProjectedTaskOperation::captureId).thenComparing(ProjectedTaskOperation::recordId));
        return new GroupBaselinePublisher.Eligible(m.sampleId(),m.evidenceRevision(),m.manifestSha(),m.reviewSha(),m.approvalSha(),GroupPublicationEnrollment.digest(CANON.writeValueAsString(rows)),rows.stream().map(r->r.fact(CANON)).toList());
    }
    private static void require(boolean condition){if(!condition)throw new GroupBaselinePublisher.MoreInputRequired();}
}
