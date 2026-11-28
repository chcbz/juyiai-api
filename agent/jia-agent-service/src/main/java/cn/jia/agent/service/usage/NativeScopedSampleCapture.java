package cn.jia.agent.service.usage;

import cn.jia.agent.dao.PersonalWorkspaceExecutionDao;
import cn.jia.agent.dao.SkillExecutionDao;
import cn.jia.agent.skill.SkillManagementCodec;
import cn.jia.core.util.CanonicalValueEncoding;
import cn.jia.economy.pricing.CostRouteReference;
import cn.jia.economy.task.TaskBillingService.Task;
import cn.jia.economy.usage.ConfirmedSampleEvidence.*;
import cn.jia.economy.usage.UsageCaptureProjection;
import cn.jia.economy.usage.UsageCostService;
import java.util.*;
import tools.jackson.databind.ObjectMapper;

/** Trusted native source adapter. Reads original admission/packages and associated evidence in the
 * caller's sample snapshot. Never consults current installations, a quote, a price or Runtime. */
final class NativeScopedSampleCapture implements ScopedFold {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final Task task;private final ScopedExecution execution;private final UsageCostService costs;
    private final NativeSampleResponseDecoder decoder;private final NativeCaptureCoverage coverage;
    private final String snapshotSha;private final List<Map<String,String>> packages;
    private final List<Operation> parents=new ArrayList<>();private ScopedOperation child;
    private boolean incomplete;private String planSnapshot;

    static ScopedFold open(Task task,ScopedExecution execution,UsageCostService costs,PersonalWorkspaceExecutionDao admissions,
            SkillExecutionDao skills,NativeSampleResponseDecoder decoder){
        var row=admissions.find(task.scope().tenantId(),task.scope().clientId(),task.ownerJiacn(),execution.execution());
        if(row==null||!Objects.equals(row.getTaskId(),task.taskId())||!Objects.equals(row.getRunId(),execution.run())
                ||!Objects.equals(row.getTargetAgentId(),execution.agent())||!"TASK".equals(row.getBusinessScope())
                ||!Integer.valueOf(6).equals(row.getExecutionProtocolVersion())||!"RESPONSE_V2".equals(row.getUsageMode())
                ||!"METERED_CAPTURE_V2".equals(row.getCaptureProtocol())||row.getCaptureProfileSha256()==null
                ||!NativeCaptureEvidence.captureIdentity(execution.execution(),execution.run()).equals(execution.captureId()))return null;
        var skill=skills.find(task.scope().tenantId(),task.scope().clientId(),task.ownerJiacn(),execution.execution());
        if(skill==null||skill.getTaskSkillBindingJson()==null)return null;
        Map<String,Object> snapshot=JSON.readValue(skill.getSnapshotJson(),Map.class);
        if(!NativeCaptureEvidence.digest(snapshot).equals(skill.getSnapshotSha256()))throw new IllegalStateException("Damaged original skill snapshot");
        var scope=object(snapshot.get("scope"));
        if(!"TASK".equals(scope.get("kind"))||!task.taskId().equals(scope.get("taskId"))
                ||!execution.agent().equals(snapshot.get("targetAgentId"))||!Integer.valueOf(6).equals(snapshot.get("executionProtocolVersion")))return null;
        var selected=(List<?>)snapshot.get("skills");var stored=JSON.readValue(skill.getTaskSkillBindingJson(),List.class);
        if(selected==null||selected.isEmpty()||selected.size()!=stored.size())return null;
        var packages=new ArrayList<Map<String,String>>();var installations=new HashSet<String>();
        for(int i=0;i<selected.size();i++){
            var selection=object(object(selected.get(i)).get("skill"));var original=object(stored.get(i));
            var boundSkill=object(original.get("skill"));var binding=object(original.get("executionBinding"));
            SkillManagementCodec.validateExecutionBinding(binding);
            if(!execution.execution().equals(binding.get("executionId"))||!execution.agent().equals(binding.get("agentId"))
                    ||!NativeCaptureEvidence.digest(scope).equals(binding.get("scopeSha256"))
                    ||row.getGrantRevision()==null||!row.getGrantRevision().toString().equals(String.valueOf(binding.get("grantRevision")))
                    ||!Objects.equals(selection.get("installationId"),binding.get("skillInstallationId"))
                    ||!installations.add(text(selection,"installationId")))return null;
            for(var names:List.of(List.of("key","skillKey"),List.of("version","skillVersion"),List.of("packageSha256","packageSha256"),List.of("manifestSha256","manifestSha256"))){
                if(!selection.containsKey(names.getFirst())||!boundSkill.containsKey(names.getFirst())
                        ||!Objects.equals(selection.get(names.getFirst()),boundSkill.get(names.getFirst()))
                        ||!Objects.equals(selection.get(names.getFirst()),binding.get(names.getLast())))return null;
            }
            // The admitted generic configuration currently covers native packages, not a guessed domain-API cost.
            if(selection.get("manifestSha256")!=null)return null;
            packages.add(Map.of("skillKey",text(selection,"key"),"skillVersion",text(selection,"version"),"packageSha",text(selection,"packageSha256")));
        }
        var canonical=packages.stream().distinct().sorted((a,b)->{for(String k:List.of("skillKey","skillVersion","packageSha")){int c=CanonicalValueEncoding.compareUtf8(a.get(k),b.get(k));if(c!=0)return c;}return 0;}).toList();
        return new NativeScopedSampleCapture(task,execution,costs,decoder,row.getCaptureProfileSha256(),skill.getSnapshotSha256(),canonical);
    }
    private NativeScopedSampleCapture(Task task,ScopedExecution execution,UsageCostService costs,NativeSampleResponseDecoder decoder,
            String approved,String snapshotSha,List<Map<String,String>> packages){
        this.task=task;this.execution=execution;this.costs=costs;this.decoder=decoder;this.snapshotSha=snapshotSha;this.packages=packages;
        this.coverage=NativeCaptureCoverage.forAdmission(execution.captureId(),approved);
    }
    @Override public void accept(UsageCaptureProjection.Row row){
        if(row.sourceRevision()==null){incomplete=true;return;}
        Map<String,Object> envelope=JSON.readValue(row.evidenceJson(),Map.class);
        if(!row.evidenceSha256().equals(NativeCaptureEvidence.digest(envelope))||!execution.execution().equals(envelope.get("executionId"))
                ||!execution.run().equals(envelope.get("runId"))||!execution.captureId().equals(envelope.get("captureId"))
                ||!row.recordId().equals(envelope.get("recordId"))||!row.sourceRevision().equals(envelope.get("sourceRevision")))
            throw new IllegalStateException("Damaged scoped usage original");
        coverage.accept(row);var o=object(envelope.get("observation"));String kind=text(envelope,"kind");
        var response=new Response(execution.memberRevision(),execution.captureId(),execution.captureRevision(),row.recordId(),row.sourceRevision(),
                row.evidenceSha256(),row.evidenceJson(),execution.execution(),execution.run(),kind,"CAPTURE_FINAL".equals(kind));
        if("PLAN".equals(kind)){
            NativeCaptureEvidence.planKeys(o);planSnapshot=(String)o.get("snapshotSha256");
        }else if("RESPONSE".equals(kind)){
            var fact=decoder.decode(response);if(fact==null)incomplete=true;else parents.add(operation(row,fact));
        }else if("CAPTURE_FINAL".equals(kind)&&o.containsKey("imageInvocation")){
            var invocation=object(o.get("imageInvocation"));
            if(!invocation.containsKey("costRouteReference")){incomplete=true;return;}
            var fact=NativeImageSampleOperation.read(costs,task,response);
            if(fact==null){incomplete=true;return;}
            child=new ScopedOperation(NativeCaptureEvidence.costRouteReference(invocation.get("costRouteReference")),operation(row,fact));
        }
    }
    private Operation operation(UsageCaptureProjection.Row row,cn.jia.economy.pricing.MeasuredTaskRepricing.Fact fact){
        return new Operation(execution.memberRevision(),execution.captureId(),execution.captureRevision(),row.recordId(),row.sourceRevision(),fact);
    }
    @Override public ScopedCapture finish(){
        var result=coverage.finish(row->child!=null&&child.operation().recordId().equals(row.recordId()));
        var configuration=coverage.costConfiguration();
        if(incomplete||result.state()!=UsageCaptureProjection.Coverage.COMPLETE||configuration==null||!snapshotSha.equals(planSnapshot)||parents.isEmpty())return null;
        var parent=configuration.routeRefs().stream().filter(r->r.kind().equals("MODEL_RESPONSE")&&r.component().equals("INFERENCE")).toList();
        if(parent.size()!=1)return null;
        var operations=new ArrayList<ScopedOperation>();for(var op:parents)operations.add(new ScopedOperation(parent.getFirst(),op));
        if(child!=null)operations.add(child);
        if(!Set.copyOf(configuration.routeRefs()).equals(operations.stream().map(ScopedOperation::resource).collect(java.util.stream.Collectors.toSet())))return null;
        var measurement=new HashMap<>(configuration.canonical());measurement.put("skillPackages",packages);
        return new ScopedCapture(CanonicalValueEncoding.digest(measurement),operations);
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value){if(!(value instanceof Map))throw new IllegalStateException("Damaged native admission");return (Map<String,Object>)value;}
    private static String text(Map<String,Object> value,String field){if(!(value.get(field) instanceof String text)||text.isBlank())throw new IllegalStateException("Missing native admission field");return text;}
}
