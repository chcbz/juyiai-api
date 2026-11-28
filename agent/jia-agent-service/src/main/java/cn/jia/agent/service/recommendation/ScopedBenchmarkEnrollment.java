package cn.jia.agent.service.recommendation;

import cn.jia.agent.mapper.skill.CapabilityQuoteSpecMapper.SpecKey;
import cn.jia.agent.security.CostProfileReportJson;
import cn.jia.agent.security.RegisteredPricingProfiles;
import cn.jia.agent.service.AgentTaskExecutionGrantService.InputCatalogue;
import cn.jia.agent.service.AgentTaskRequirementSnapshotService.Snapshot;
import cn.jia.agent.service.TaskAssigneeAuthorityService.Target;
import cn.jia.agent.skill.PublishedCapabilityQuoteSpecs;
import cn.jia.agent.skill.quote.*;
import cn.jia.economy.pricing.GroupMeanPricing;
import cn.jia.economy.task.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** Prospective benchmark admission in the existing task-root transaction, not a quote or dispatch.
 * All scope/config observations come from Agent authority; no fabricated quote intent or Runtime RPC. */
@Service
public final class ScopedBenchmarkEnrollment {
    private final TaskBenchmarkAdmission admission;
    private final RegisteredPricingProfiles profiles;
    private final PublishedCapabilityQuoteSpecs specs;
    private final ResolvedCostProfiles routes;
    public ScopedBenchmarkEnrollment(TaskBenchmarkAdmission admission,RegisteredPricingProfiles profiles,
            PublishedCapabilityQuoteSpecs specs,ResolvedCostProfiles routes){
        this.admission=Objects.requireNonNull(admission);this.profiles=Objects.requireNonNull(profiles);
        this.specs=Objects.requireNonNull(specs);this.routes=Objects.requireNonNull(routes);
    }
    public TaskBenchmarkAdmission.ScopedEnrollment prepare(TaskBillingService.Task task,Target target,
            Snapshot requirement,InputCatalogue catalogue){
        return admission.prepareScopedPaidIfPresent(task,target.agentId(),policy->observe(task,target,requirement,catalogue,policy));
    }
    public static String sponsoredInitialIntentSha(String assignmentSha,String requirementSha,String inputDigest){
        return cn.jia.core.util.CanonicalValueEncoding.digest(Map.of("schema","SCOPED_SPONSORED_INITIAL_INTENT_V1",
                "assignmentSha",assignmentSha,"requirementSha",requirementSha,"inputSnapshotDigest",inputDigest,"operation","DELIBERATE"));
    }
    public void sponsor(TaskBillingService.Task task,Target target,Snapshot requirement,InputCatalogue catalogue,String action,String assignmentSha){
        admission.sponsorScope(task,target.agentId(),action,assignmentSha,sponsoredInitialIntentSha(assignmentSha,requirement.sha256(),catalogue.digest()),
                policy->observe(task,target,requirement,catalogue,policy));
    }
    public void replaySponsor(TaskBillingService.Task task,String agent,String action,String assignmentSha,String requirementSha,String inputDigest){
        var funding=admission.sponsorship(task);if(funding==null)throw new TaskBenchmarkAdmission.NotAdmitted();
        var a=funding.authority();
        if(!a.agentId().equals(agent)||!a.actionId().equals(action)||!a.assignmentSha256().equals(assignmentSha)
                ||!a.initialIntentSha256().equals(sponsoredInitialIntentSha(assignmentSha,requirementSha,inputDigest)))throw new TaskBenchmarkAdmission.NotAdmitted();
    }
    private TaskBenchmarkAdmission.ObservedScopeGroup observe(TaskBillingService.Task task,Target target,
            Snapshot requirement,InputCatalogue catalogue,GroupMeanPricing.ScopedPolicy policy){
        var scope=requirement.confirmedScope();
        if(scope==null || !task.scope().tenantId().equals(requirement.tenantId()) || !task.scope().clientId().equals(requirement.clientId())
                || !task.ownerJiacn().equals(requirement.ownerJiacn()) || !task.taskId().equals(requirement.taskId())
                || !target.tenantId().equals(task.scope().tenantId()) || !target.clientId().equals(task.scope().clientId())
                || scope.requirementRevision()!=requirement.revision() || !scope.requirementSha256().equals(requirement.sha256())
                || !scope.inputSnapshotDigest().equals(catalogue.digest()))throw new TaskBenchmarkAdmission.NotAdmitted();
        // This locked reader owns current installation, all reported slot authorization and fence checks.
        var report=profiles.atAssignment(target);if(report==null)throw new TaskBenchmarkAdmission.NotAdmitted();
        var keys=scope.workItems().stream().map(i->new SpecKey(i.capabilityId(),i.capabilityVersion())).distinct().toList();
        var declarations=new HashMap<ScopedCostInputs.Capability,CapabilityQuoteSpec>();
        specs.lockActive(task.scope().tenantId(),task.scope().clientId(),keys).forEach((key,spec)->
                declarations.put(new ScopedCostInputs.Capability(key.capabilityId(),key.version()),spec));
        var metadata=new HashMap<String,CapabilityQuoteSpec.InputMetadata>();
        for(var input:catalogue.inputs()){
            String ref=input.fileId()+"@"+input.version();
            var value=cn.jia.agent.service.PersonalWorkspaceInputMetadata.fixedVersion(input.fileId(),input.version(),input.contentMimeType(),input.contentHash(),input.byteLength());
            var previous=metadata.putIfAbsent(ref,value);
            if(previous!=null && !previous.equals(value))throw new TaskBenchmarkAdmission.NotAdmitted();
        }
        if(scope.workItems().stream().flatMap(i->i.inputRefs().stream()).anyMatch(ref->!metadata.containsKey(ref)))throw new TaskBenchmarkAdmission.NotAdmitted();
        var inputs=ScopedCostInputs.prepare(scope,declarations,metadata);
        var resolved=routes.prepareScoped(task.scope(),inputs,Map.of(target.agentId(),CostProfileReportJson.read(report.canonical()).profiles()));
        var input=resolved.input(target.agentId()).orElseThrow(TaskBenchmarkAdmission.NotAdmitted::new);
        try{policy.requireInput(input);}catch(IllegalArgumentException changed){throw new TaskBenchmarkAdmission.NotAdmitted();}
        routes.recheckScoped(task.scope(),resolved);
        var group=resolved.group(target.agentId(),policy.featureVersion(),policy.sha()).orElseThrow(TaskBenchmarkAdmission.NotAdmitted::new);
        return new TaskBenchmarkAdmission.ObservedScopeGroup(new ScopedTaskQuoteSnapshot.ScopePin(scope.requirementRevision(),scope.requirementSha256(),scope.scopeSha256(),scope.inputSnapshotDigest()),group);
    }
}
