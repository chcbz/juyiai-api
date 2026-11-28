package cn.jia.economy.service.impl;

import cn.jia.economy.mapper.TaskBenchmarkMapper;
import cn.jia.economy.pricing.TaskEstimateFeatures;
import cn.jia.economy.task.TaskBenchmarkAdmission;
import cn.jia.economy.task.TaskBillingService;
import cn.jia.economy.task.TaskHistoryCatalog;
import cn.jia.economy.task.ScopedBenchmarkManifest;
import cn.jia.economy.task.ScopedTaskQuoteSnapshot;
import cn.jia.core.util.CanonicalValueEncoding;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import tools.jackson.databind.ObjectMapper;

@Service
public class TaskBenchmarkAdmissionService implements TaskBenchmarkAdmission {
    private final TaskBenchmarkMapper db;private final ObjectMapper json;
    public TaskBenchmarkAdmissionService(TaskBenchmarkMapper db,ObjectMapper json){this.db=db;this.json=json;}
    private static final ObjectMapper SCOPED_JSON=tools.jackson.databind.json.JsonMapper.builder(
            tools.jackson.core.json.JsonFactory.builder()
                    .streamReadConstraints(tools.jackson.core.StreamReadConstraints.builder().maxNestingDepth(24).maxStringLength(1048576).build())
                    .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                    tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    tools.jackson.databind.DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                    tools.jackson.databind.DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
            .disable(tools.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS).build();

    @Override @Transactional(propagation=Propagation.MANDATORY)
    public ScopedEnrollment prepareScopedPaid(TaskBillingService.Task task,String id,String sha,String agent,ObservedScopeGroup observed){
        requireTransaction();java.util.Objects.requireNonNull(observed);
        if(sha==null || !sha.matches("[0-9a-f]{64}"))throw new NotAdmitted();
        var raw=db.approved(task,id,sha);if(raw==null)throw new NotAdmitted();
        var manifest=decodeScoped(raw,sha);
        if(!id.equals(manifest.caseId()))throw new NotAdmitted();
        return prepareScoped(task,agent,manifest,sha,observed);
    }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public ScopedEnrollment prepareScopedPaidIfPresent(TaskBillingService.Task task,String agent,
            java.util.function.Function<cn.jia.economy.pricing.GroupMeanPricing.ScopedPolicy,ObservedScopeGroup> observer){
        requireTransaction();java.util.Objects.requireNonNull(observer);
        var row=db.scopedCase(task);if(row==null)return null;
        if(row.retired())throw new NotAdmitted();
        var manifest=decodeScoped(row.manifestJson(),row.manifestSha());
        if(!row.benchmarkCaseId().equals(manifest.caseId()) || !task.equals(manifest.task()) || !agent.equals(manifest.agentId()))throw new NotAdmitted();
        return prepareScoped(task,agent,manifest,row.manifestSha(),observer.apply(manifest.policy()));
    }
    static ScopedBenchmarkManifest decodeScoped(String raw,String sha){
        if(raw==null || sha==null || CanonicalValueEncoding.utf8(raw).length>1048576 || !CanonicalValueEncoding.sha256(CanonicalValueEncoding.utf8(raw)).equals(sha))
            throw new IllegalStateException("SCOPED_BENCHMARK_AUTHORITY_CORRUPT");
        try{return SCOPED_JSON.readValue(raw,ScopedBenchmarkManifest.class);}
        catch(RuntimeException invalid){throw new IllegalStateException("SCOPED_BENCHMARK_AUTHORITY_CORRUPT",invalid);}
    }
    private ScopedEnrollment prepareScoped(TaskBillingService.Task task,String agent,ScopedBenchmarkManifest manifest,String sha,ObservedScopeGroup observed){
        if(observed==null || !task.equals(manifest.task()) || !agent.equals(manifest.agentId())
                || !observed.scopePin().equals(manifest.scopePin()) || !observed.group().key().equals(manifest.groupSha()))throw new NotAdmitted();
        if(db.charges(task)!=0 || db.samples(task)!=0 || db.hasEvidence(task))throw new NotAdmitted();
        var marker=new TransactionSynchronization(){};
        TransactionSynchronizationManager.registerSynchronization(marker);
        return new ScopedPaid(manifest,sha,marker);
    }
    private static void requireTransaction(){
        if(!TransactionSynchronizationManager.isActualTransactionActive() || !TransactionSynchronizationManager.isSynchronizationActive())
            throw new IllegalStateException("Scoped benchmark requires caller transaction");
    }
    private final class ScopedPaid implements ScopedEnrollment {
        private final ScopedBenchmarkManifest manifest;private final String sha;
        private final TransactionSynchronization marker;private boolean recorded;
        ScopedPaid(ScopedBenchmarkManifest manifest,String sha,TransactionSynchronization marker){this.manifest=manifest;this.sha=sha;this.marker=marker;}
        @Override public void requireQuote(TaskBillingService.Task task,ScopedTaskQuoteSnapshot quote){
            requireTransaction();
            if(!TransactionSynchronizationManager.getSynchronizations().contains(marker))throw new NotAdmitted();
            if(!manifest.task().equals(task) || !manifest.agentId().equals(quote.binding().agentId())
                    || !manifest.scopePin().equals(quote.scopePin()))throw new NotAdmitted();
        }
        @Override public void recordPaidSample(TaskBillingService.Task task,String quoteId,String sampleId,ScopedTaskQuoteSnapshot quote){
            requireQuote(task,quote);if(recorded)throw new NotAdmitted();
            var group=manifest.group();
            if(db.seedScopedPaid(manifest,sha,quoteId,sampleId,quote.featureSha(),group.scopeShapeSha(),group.policySha())!=1)
                throw new NotAdmitted();
            recorded=true;
        }
        @Override public String toString(){return "ScopedEnrollment[private]";}
    }

    @Override @Transactional(propagation=Propagation.MANDATORY)
    public String enroll(TaskBillingService.Task task,String id,String sha,String agent,TaskBillingService.MeasuredGroup actual,TaskEstimateFeatures features){
        return enrollLocked(task,id,sha,agent,actual,features,EnrollmentMode.NORMAL,null);
    }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public String enrollStructured(TaskBillingService.Task task,String id,String sha,String agent,TaskBillingService.MeasuredGroup actual,TaskEstimateFeatures features,GroupInput observed){
        if(observed==null)throw new NotAdmitted();return enrollLocked(task,id,sha,agent,actual,features,EnrollmentMode.NORMAL,observed);
    }
    private enum EnrollmentMode { NORMAL, NEW_SPONSOR, ACTIVE_SPONSOR }
    private String enrollLocked(TaskBillingService.Task task,String id,String sha,String agent,
            TaskBillingService.MeasuredGroup actual,TaskEstimateFeatures features,EnrollmentMode mode,GroupInput observed) {
        var raw=db.approved(task,id,sha);if(raw==null)throw new NotAdmitted();
        try{if(!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8))).equals(sha))throw new IllegalStateException("Damaged benchmark authority");}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
        var m=json.readValue(raw,Manifest.class);
        if(!task.equals(m.task())||!id.equals(m.caseId())||!agent.equals(m.agentId())||!m.execution().equals(actual)||!m.features().equals(features)||!java.util.Objects.equals(m.groupInput(),observed))throw new NotAdmitted();
        String existing=db.existing(task,id);
        if(existing!=null){
            if(!sha.equals(existing))throw new IllegalStateException("Benchmark identity changed");
            if(mode==EnrollmentMode.NEW_SPONSOR)throw new NotAdmitted(); // never retrospectively sponsor an enrollment
            return existing;
        }
        if(mode==EnrollmentMode.ACTIVE_SPONSOR)throw new IllegalStateException("Missing sponsored benchmark enrollment");
        if(db.seed(task,id,sha,features.requirementSha256(),features.inputSnapshotSha256(),features.digest(),TaskHistoryCatalog.Group.of(actual,features).digest())!=1)throw new NotAdmitted();
        return sha;
    }

    @Override @Transactional(propagation=Propagation.MANDATORY)
    public Sponsorship sponsor(TaskBillingService.Task task, String id, String manifestSha, String agent,
            TaskBillingService.MeasuredGroup group, TaskEstimateFeatures features,
            String action, String assignmentSha, String initialIntentSha) {
        return authorizeFunding(task,id,manifestSha,agent,group,features,action,assignmentSha,initialIntentSha,null);
    }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public Sponsorship sponsorStructured(TaskBillingService.Task task,String id,String manifestSha,String agent,TaskBillingService.MeasuredGroup group,TaskEstimateFeatures features,String action,String assignmentSha,String initialIntentSha,GroupInput observed){
        if(observed==null)throw new NotAdmitted();return authorizeFunding(task,id,manifestSha,agent,group,features,action,assignmentSha,initialIntentSha,observed);
    }
    private Sponsorship authorizeFunding(TaskBillingService.Task task,String id,String manifestSha,String agent,TaskBillingService.MeasuredGroup group,TaskEstimateFeatures features,String action,String assignmentSha,String initialIntentSha,GroupInput observed){
        var row = db.funding(task);
        if (row == null) throw new NotAdmitted();
        var funding = decodeFunding(task, row);
        var authority = funding.authority();
        if (!authority.caseId().equals(id) || !authority.manifestSha256().equals(manifestSha)
                || !authority.agentId().equals(agent) || !authority.actionId().equals(action)
                || !authority.assignmentSha256().equals(assignmentSha)
                || !authority.initialIntentSha256().equals(initialIntentSha)) throw new NotAdmitted();
        if (funding.state() != SponsorshipState.APPROVED && funding.state() != SponsorshipState.ACTIVE)
            throw new NotAdmitted();
        if (row.withdrawn()) throw new NotAdmitted();
        // The caller owns the task root. Serialize sponsor -> original case -> sample;
        // never create a quote/charge, provider request or journal entry here.
        enrollLocked(task, id, manifestSha, agent, group, features,
                funding.state() == SponsorshipState.ACTIVE ? EnrollmentMode.ACTIVE_SPONSOR : EnrollmentMode.NEW_SPONSOR,observed);
        if (funding.state() == SponsorshipState.ACTIVE) return funding;
        transition(task, funding, SponsorshipState.ACTIVE);
        return new Sponsorship(funding.authoritySha256(), authority, SponsorshipState.ACTIVE);
    }

    @Override @Transactional(propagation=Propagation.MANDATORY)
    public Sponsorship sponsorScope(TaskBillingService.Task task,String agent,String action,String assignmentSha,
            String initialIntentSha,java.util.function.Function<cn.jia.economy.pricing.GroupMeanPricing.ScopedPolicy,ObservedScopeGroup> observer){
        requireTransaction();java.util.Objects.requireNonNull(observer);
        var row=db.funding(task);if(row==null||row.withdrawn())throw new NotAdmitted();
        var funding=decodeFunding(task,row);var authority=funding.authority();
        if(funding.state()!=SponsorshipState.APPROVED||!authority.agentId().equals(agent)||!authority.actionId().equals(action)
                ||!authority.assignmentSha256().equals(assignmentSha)||!authority.initialIntentSha256().equals(initialIntentSha))throw new NotAdmitted();
        var raw=db.approved(task,authority.caseId(),authority.manifestSha256());if(raw==null)throw new NotAdmitted();
        var manifest=decodeScoped(raw,authority.manifestSha256());
        if(!task.equals(manifest.task())||!authority.caseId().equals(manifest.caseId())||!agent.equals(manifest.agentId()))throw new NotAdmitted();
        var observed=observer.apply(manifest.policy());
        if(observed==null||!observed.scopePin().equals(manifest.scopePin())||!observed.group().key().equals(manifest.groupSha())
                ||db.samples(task)!=0||db.hasEvidence(task))throw new NotAdmitted();
        if(db.seedScopedSponsored(manifest,authority.manifestSha256(),ScopedTaskQuoteSnapshot.featureSha(manifest.scopePin()),manifest.group().scopeShapeSha())!=1)throw new NotAdmitted();
        transition(task,funding,SponsorshipState.ACTIVE);
        return new Sponsorship(funding.authoritySha256(),authority,SponsorshipState.ACTIVE);
    }

    @Override @Transactional(propagation=Propagation.MANDATORY)
    public Sponsorship sponsorship(TaskBillingService.Task task) {
        var row = db.funding(task);
        if (row == null) return null;
        var funding = decodeFunding(task, row);
        if (funding.state() == SponsorshipState.APPROVED) return null;
        // Original enrollment must still exist. A forged ACTIVE row must not make missing billing free.
        if (!funding.authority().manifestSha256().equals(db.existing(task, funding.authority().caseId())))
            throw new IllegalStateException("Missing sponsored benchmark enrollment");
        return funding;
    }

    @Override @Transactional(propagation=Propagation.MANDATORY)
    public boolean settleSponsorship(TaskBillingService.Task task, SponsorshipState terminal) {
        if (terminal != SponsorshipState.ACCEPTED && terminal != SponsorshipState.CANCELLED)
            throw new IllegalArgumentException("Expected sponsorship terminal");
        var funding = sponsorship(task);
        if (funding == null) return false;
        if (funding.state() == terminal) return true;
        if (funding.state() != SponsorshipState.ACTIVE)
            throw new IllegalStateException("Conflicting sponsorship terminal");
        transition(task, funding, terminal);
        return true;
    }

    private Sponsorship decodeFunding(TaskBillingService.Task task, TaskBenchmarkMapper.FundingRow row) {
        try {
            String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(row.authorityJson().getBytes(StandardCharsets.UTF_8)));
            if (!actual.equals(row.authoritySha())) throw new IllegalStateException("Damaged sponsorship authority");
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        var authority = json.readValue(row.authorityJson(), SponsorshipAuthority.class);
        if (!task.equals(authority.task())) throw new NotAdmitted();
        if (db.charges(task) != 0) throw new IllegalStateException("Conflicting ordinary and sponsored funding");
        return new Sponsorship(row.authoritySha(), authority, SponsorshipState.valueOf(row.state()));
    }

    private void transition(TaskBillingService.Task task, Sponsorship funding, SponsorshipState next) {
        if (db.transitionFunding(task, funding.authoritySha256(), funding.state().name(), next.name()) != 1)
            throw new IllegalStateException("Sponsorship changed within task transaction");
    }
}
