package cn.jia.economy.service.impl;

import cn.jia.economy.mapper.TaskBenchmarkMapper;
import cn.jia.economy.pricing.TaskEstimateFeatures;
import cn.jia.economy.task.TaskBenchmarkAdmission;
import cn.jia.economy.task.TaskBillingService;
import cn.jia.economy.task.TaskHistoryCatalog;
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
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public String enroll(TaskBillingService.Task task,String id,String sha,String agent,TaskBillingService.MeasuredGroup actual,TaskEstimateFeatures features){
        return enrollLocked(task,id,sha,agent,actual,features,EnrollmentMode.NORMAL);
    }
    private enum EnrollmentMode { NORMAL, NEW_SPONSOR, ACTIVE_SPONSOR }
    private String enrollLocked(TaskBillingService.Task task,String id,String sha,String agent,
            TaskBillingService.MeasuredGroup actual,TaskEstimateFeatures features,EnrollmentMode mode) {
        var raw=db.approved(task,id,sha);if(raw==null)throw new NotAdmitted();
        try{if(!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8))).equals(sha))throw new IllegalStateException("Damaged benchmark authority");}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
        var m=json.readValue(raw,Manifest.class);
        if(!task.equals(m.task())||!id.equals(m.caseId())||!agent.equals(m.agentId())||!m.execution().equals(actual)||!m.features().equals(features))throw new NotAdmitted();
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
                funding.state() == SponsorshipState.ACTIVE ? EnrollmentMode.ACTIVE_SPONSOR : EnrollmentMode.NEW_SPONSOR);
        if (funding.state() == SponsorshipState.ACTIVE) return funding;
        transition(task, funding, SponsorshipState.ACTIVE);
        return new Sponsorship(funding.authoritySha256(), authority, SponsorshipState.ACTIVE);
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
