package cn.jia.economy.service.impl;

import cn.jia.economy.mapper.ConfirmedSampleMapper;
import cn.jia.economy.task.TaskBillingService.Task;
import cn.jia.economy.usage.ConfirmedSampleEvidence;
import cn.jia.economy.usage.TaskMembership;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static cn.jia.economy.usage.ConfirmedSampleEvidence.Verdict.*;

/** Background read, one consistent MVCC snapshot, bounded keyset pages, no task-root lock or provider I/O. */
@Service
public class ConfirmedSampleEvidenceReader implements ConfirmedSampleEvidence,cn.jia.economy.usage.BenchmarkSampleEvidence {
    private final ConfirmedSampleMapper db;
    private final tools.jackson.databind.ObjectMapper json;
    public ConfirmedSampleEvidenceReader(ConfirmedSampleMapper db,tools.jackson.databind.ObjectMapper json){this.db=db;this.json=json;}
    @Transactional(readOnly=true,propagation=Propagation.REQUIRES_NEW,isolation=Isolation.REPEATABLE_READ)
    public Assessment inspect(Task task,String id){
        var h=confirmedHeader(task,id);return h==null?null:assess(task,id,h);
    }
    private ConfirmedSampleMapper.Header confirmedHeader(Task task,String id){
        var h=db.confirmed(task,id);
        if(h!=null && h.agent()==null)throw new IllegalStateException("Unresolvable confirmed quote binding");
        return h;
    }
    private Assessment assess(Task task,String id,ConfirmedSampleMapper.Header h){
        return assess(task,id,h,null);
    }
    // For a retrospective outcome, actual dispatch may differ; each member's own capture policy must still cover all operations.
    private Assessment assess(Task task,String id,ConfirmedSampleMapper.Header h,Decoder outcome){
        var verdict=ACTIVE;long count=0;
        if("CANCELLED".equals(h.lifecycle()))verdict=CANCELLED_OUTCOME;
        else if("ACCEPTED".equals(h.lifecycle())){
            if(!Boolean.TRUE.equals(h.verified()))verdict=MEMBERSHIP_PENDING;
            else if(!h.lifecycle().equals(h.outcome()))verdict=EXECUTION_SET_CONFLICT;
            else if(h.groupVersion()==null||h.executionConfig()==null||h.localRoute()==null||h.tools()==null||h.referenceRoute()==null||h.policy()==null)verdict=CONFIG_UNKNOWN;
            else {
                boolean broken=false,mixed=false,missing=false,closedIncomplete=false,unknown=false;long after=0;
                while(true){var page=db.members(task,id,h.fact(),h.agent(),after);
                    for(var m:page){after=m.revision();count++;broken|=m.attached()==null;
                        boolean complete="COMPLETE".equals(m.coverage());missing|=!complete;
                        // members() joins ONLY the current capture projection; a stale or missing close is not a verdict.
                        closedIncomplete|="INCOMPLETE".equals(m.coverage());
                        if(m.groupVersion()==null){unknown|=complete;continue;}
                        if(outcome!=null){
                            unknown|=m.executionConfig()==null||m.route()==null||m.tools()==null||m.policy()==null;
                            if(m.policy()!=null)missing|=!outcome.coversAllOperations(m.groupVersion(),m.policy());
                            continue;
                        }
                        mixed|=!h.groupVersion().equals(m.groupVersion())||!h.executionConfig().equals(m.executionConfig())
                            || !h.localRoute().equals(m.route())||!h.tools().equals(m.tools())||!h.policy().equals(m.policy());
                    }
                    if(page.size()<TaskMembership.PAGE)break;
                }
                // Reference tariff version belongs to pricing, not the Runtime provider namespace.
                verdict=broken||count!=h.memberCount()?EXECUTION_SET_CONFLICT:mixed?MIXED_EXECUTION_CONFIG:
                        unknown?CONFIG_UNKNOWN:closedIncomplete?CLOSED_USAGE_INCOMPLETE:missing||count==0?USAGE_INCOMPLETE:MATCHED;
            }
        }
        return new Assessment(id,h.evidenceRevision(),h.fact(),count,verdict);
    }
    @Transactional(readOnly=true,propagation=Propagation.REQUIRES_NEW,isolation=Isolation.REPEATABLE_READ)
    public Measured measure(Task task,String id,Decoder decoder){
        return measureHeader(task,id,decoder,confirmedHeader(task,id));
    }
    @Override @Transactional(readOnly=true,propagation=Propagation.REQUIRES_NEW,isolation=Isolation.REPEATABLE_READ)
    public Measured measureOutcome(Task task,String quoteId,String agent,String groupSha,String featureSha,Decoder decoder){
        java.util.Objects.requireNonNull(decoder);
        var id=db.outcomeSample(task,quoteId,groupSha,featureSha);if(id==null)return null;
        var h=confirmedHeader(task,id);if(h==null||!h.agent().equals(agent))return null;
        var a=assess(task,id,h,decoder);return extract(task,h,a,decoder);
    }
    @Override @Transactional(readOnly=true,propagation=Propagation.REQUIRES_NEW,isolation=Isolation.REPEATABLE_READ)
    public Measured measureReviewed(Task task,String caseId,String sha,String fact,String review,Decoder decoder){
        return measureHeader(task,sha,decoder,db.reviewedBenchmark(task,caseId,sha,fact,review));
    }
    @Override @Transactional(readOnly=true,propagation=Propagation.MANDATORY)
    public ScopedMeasured measureScopedReviewed(Task task,String sample,String caseId,String sha,String fact,String review,Decoder decoder){
        var h=db.reviewedScopedBenchmark(task,sample,caseId,sha,fact,review);if(h==null)return null;
        var manifest=TaskBenchmarkAdmissionService.decodeScoped(h.manifest(),sha);var pin=manifest.scopePin();var group=manifest.group();
        if(!manifest.task().equals(task)||!manifest.caseId().equals(caseId)||!manifest.agentId().equals(h.agent())
                ||!pin.scopeSha256().equals(h.scopeSha())||!group.scopeShapeSha().equals(h.shapeSha())
                ||!group.key().equals(h.groupSha())||!group.policySha().equals(h.policySha())
                ||!pin.requirementSha256().equals(h.requirementSha())||!pin.inputSnapshotDigest().equals(h.inputSha()))
            throw new IllegalStateException("Scoped sample source differs from original");
        var quote=json.readValue(h.quoteJson(),cn.jia.economy.task.ScopedTaskQuoteSnapshot.class);
        if(!pin.equals(quote.scopePin())||!manifest.agentId().equals(quote.binding().agentId())||!quote.featureSha().equals(h.featureSha()))
            throw new IllegalStateException("Scoped sample paid binding differs");
        Object configuration=manifest.compatibleConfig().get("measurement");
        if(!(configuration instanceof java.util.Map<?,?>))return scopedResult(sample,h,0,CONFIG_UNKNOWN,java.util.List.of());
        String expected=cn.jia.core.util.CanonicalValueEncoding.digest(configuration);
        var operations=new java.util.ArrayList<ScopedOperation>();long after=0,count=0;Verdict failure=null;
        while(true){var page=db.scopedMembers(task,sample,h.fact(),h.agent(),after);
            for(var member:page){after=member.revision();count++;
                if(member.attached()==null){failure=EXECUTION_SET_CONFLICT;continue;}
                if(!"COMPLETE".equals(member.coverage())||member.capturePk()==null||member.captureRevision()==null){
                    if(failure==null)failure="INCOMPLETE".equals(member.coverage())?CLOSED_USAGE_INCOMPLETE:USAGE_INCOMPLETE;continue;}
                var execution=new ScopedExecution(h.agent(),member.execution(),member.run(),member.captureId(),member.captureRevision(),member.revision());
                var fold=decoder.openScoped(task,execution);if(fold==null){if(failure==null)failure=CONFIG_UNKNOWN;continue;}
                String record="";
                while(true){var rows=db.scopedRecords(task,member.capturePk(),record);for(var row:rows){fold.accept(row);record=row.recordId();}if(rows.size()<TaskMembership.PAGE)break;}
                var measured=fold.finish();if(measured==null||measured.operations().isEmpty()){if(failure==null)failure=USAGE_INCOMPLETE;continue;}
                if(!expected.equals(measured.measurementSha())){if(failure==null)failure=MIXED_EXECUTION_CONFIG;continue;}
                operations.addAll(measured.operations());
            }
            if(page.size()<TaskMembership.PAGE)break;
        }
        if(count!=h.memberCount())failure=EXECUTION_SET_CONFLICT;
        if(count==0&&failure==null)failure=USAGE_INCOMPLETE;
        return scopedResult(sample,h,count,failure==null?MATCHED:failure,operations);
    }
    private static ScopedMeasured scopedResult(String sample,ConfirmedSampleMapper.ReviewedScope h,long count,Verdict verdict,java.util.List<ScopedOperation> operations){
        return new ScopedMeasured(new Assessment(sample,h.evidenceRevision(),h.fact(),count,verdict),verdict==MATCHED?operations:java.util.List.of());
    }
    @Override @Transactional(readOnly=true,propagation=Propagation.REQUIRES_NEW,isolation=Isolation.REPEATABLE_READ)
    public cn.jia.economy.task.TaskCalibrationObservations.Frozen freezeReviewed(Task task,cn.jia.economy.task.TaskHistoryCatalog.Group group,
            cn.jia.economy.task.TaskHistoryCatalog.Revision selected,Pin pin,String family,cn.jia.economy.pricing.TaskEstimateEvaluation.Split split,Decoder decoder){
        var h=db.reviewedBenchmark(task,pin.caseId(),pin.manifestSha(),pin.fact(),pin.reviewSha());
        if(h==null||!pin.manifestSha().equals(selected.sampleId())||h.evidenceRevision()!=selected.revision())throw new cn.jia.economy.task.TaskCalibrationObservations.NotEligible();
        var measured=measureHeader(task,pin.manifestSha(),decoder,h);
        if(measured.assessment().verdict()!=MATCHED)throw new cn.jia.economy.task.TaskCalibrationObservations.NotEligible();
        var originals=db.benchmarkOriginals(task,pin.caseId(),pin.manifestSha(),pin.reviewSha());
        var entry=new cn.jia.economy.pricing.TaskEstimateEvaluation.Case(task.taskId(),family,originals.featuresAt(),db.observedByMillis(),split);
        var snapshot=new FrozenBenchmarkObservation("benchmark-observation-v1",entry,selected,originals.manifest(),originals.review(),
                measured.operations().stream().map(op->ProjectedTaskOperation.from(op,json)).toList());
        snapshot.sample(json,task,group,selected,pin,family,split);return snapshot.seal(json);
    }
    private Measured measureHeader(Task task,String id,Decoder decoder,ConfirmedSampleMapper.Header h){
        if(h==null)return null;
        var a=assess(task,id,h);if(a.verdict()!=MATCHED)return new Measured(a,java.util.List.of());
        if(!decoder.coversAllOperations(h.groupVersion(),h.policy()))return incomplete(a);
        return extract(task,h,a,decoder);
    }
    private Measured extract(Task task,ConfirmedSampleMapper.Header h,Assessment a,Decoder decoder){
        if(a.verdict()!=MATCHED)return new Measured(a,java.util.List.of());
        var lines=new java.util.ArrayList<Operation>();long revision=0,seen=0;String after="";boolean parent=false,child=false,expects=false;
        while(true){var page=db.responses(task,h.fact(),h.agent(),revision,after);
            for(var r:page){if(r.memberRevision()!=revision){
                    if(seen>0 && (!parent || child!=expects))return incomplete(a);
                    seen++;revision=r.memberRevision();parent=false;child=false;expects=r.expectsChild();
                }
                if(r.recordId()==null)return incomplete(a);
                after=r.recordId();boolean isChild="CAPTURE_FINAL".equals(r.kind());
                if(r.expectsChild()!=expects || (isChild ? !expects||child : !"RESPONSE".equals(r.kind())))return incomplete(a);
                var fact=isChild?decoder.decodeChild(task,r):decoder.decode(r);if(fact==null)return incomplete(a);
                if(isChild)child=true;else parent=true;
                if(!r.evidenceSha().equals(fact.evidenceSha()))throw new IllegalStateException("Source measurement evidence mismatch");
                lines.add(new Operation(revision,r.captureId(),r.captureRevision(),r.recordId(),r.sourceRevision(),fact));
            }
            if(page.size()<TaskMembership.PAGE)break;
        }
        return seen==a.members()&&parent&&child==expects&&!lines.isEmpty()?new Measured(a,lines):incomplete(a);
    }
    private static Measured incomplete(Assessment a){return new Measured(new Assessment(a.sampleId(),a.evidenceRevision(),a.terminalFactId(),a.members(),USAGE_INCOMPLETE),java.util.List.of());}
}
