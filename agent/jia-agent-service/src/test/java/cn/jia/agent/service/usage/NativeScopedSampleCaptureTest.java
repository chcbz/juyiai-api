package cn.jia.agent.service.usage;

import cn.jia.agent.dao.*;
import cn.jia.agent.entity.*;
import cn.jia.core.util.CanonicalValueEncoding;
import cn.jia.economy.pricing.*;
import cn.jia.economy.task.TaskBillingService.Task;
import cn.jia.economy.service.EconomyScope;
import cn.jia.economy.usage.*;
import cn.jia.economy.usage.ConfirmedSampleEvidence.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NativeScopedSampleCaptureTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final String H="a".repeat(64),C="b".repeat(64);
    private final Task task=new Task(new EconomyScope("t","c"),"owner","task");
    private final PersonalWorkspaceExecutionDao admissions=mock(PersonalWorkspaceExecutionDao.class);
    private final SkillExecutionDao skills=mock(SkillExecutionDao.class);
    private final UsageCostService costs=mock(UsageCostService.class);
    private final NativeSampleResponseDecoder decoder=new NativeSampleResponseDecoder(costs,admissions,skills);
    private final String cap=NativeCaptureEvidence.captureIdentity("exec1","run1");
    private final ScopedExecution execution=new ScopedExecution("agent","exec1","run1",cap,7,1);
    private final TreeMap<String,UsageCaptureProjection.Row> rows=new TreeMap<>();
    private Map<String,Object> configuration;private PersonalWorkspaceExecutionEntity admitted;private SkillExecutionEntity skill;
    private String measurementSha;
    private UsageCaptureProjection.Row add(String kind,List<String> identity,Map<String,Object> observation){
        String id=NativeCaptureEvidence.digest(List.of("USAGE_RECORD_V2",cap,kind,identity));
        var envelope=Map.of("schemaVersion",2,"executionId","exec1","runId","run1","kind",kind,"captureId",cap,"recordId",id,"sourceRevision","1","observedAt",1,"observation",observation);
        var row=new UsageCaptureProjection.Row(id,1,"1",NativeCaptureEvidence.digest(envelope),JSON.writeValueAsString(envelope));rows.put(id,row);return row;
    }
    private String tuples(List<UsageCaptureProjection.Row> members){return NativeCaptureEvidence.digest(members.stream().sorted(Comparator.comparing(UsageCaptureProjection.Row::recordId)).map(r->List.of(r.recordId(),r.sourceRevision(),r.evidenceSha256())).toList());}
    private void fixture(boolean image)throws Exception{
        var selected=new HashMap<String,Object>(Map.of("key","summary","version","1.0.0","installationId","installed","packageSha256",H));selected.put("manifestSha256",null);
        var scope=Map.of("kind","TASK","conversationId","conversation","conversationGeneration","1","taskId","task");
        var snapshot=Map.of("scope",scope,"executionProtocolVersion",6,"targetAgentId","agent","skills",List.of(Map.of("skill",selected)));
        var binding=new HashMap<String,Object>(Map.of("executionId","exec1","agentId","agent","scopeSha256",NativeCaptureEvidence.digest(scope),"grantRevision","1","skillKey","summary","skillVersion","1.0.0","skillInstallationId","installed","packageSha256",H,"productVersionId","product","approvedPermissionsSha256",C));binding.put("manifestSha256",null);
        var bound=new HashMap<>(selected);bound.remove("installationId");
        skill=new SkillExecutionEntity().setSnapshotJson(JSON.writeValueAsString(snapshot)).setSnapshotSha256(NativeCaptureEvidence.digest(snapshot)).setTaskSkillBindingJson(JSON.writeValueAsString(List.of(Map.of("skill",bound,"executionBinding",binding))));
        var parent=new CostRouteReference("MODEL_RESPONSE","INFERENCE","native-parent","parent","RESPONSES_TEXT_TOKENS_V1");
        var refs=new ArrayList<Object>();refs.add(parent.canonical());Map<String,Object> child=null;
        if(image){
            try(var in=getClass().getResourceAsStream("/usage/native-child-cost-reference.json")){
                var fixture=JSON.readTree(Objects.requireNonNull(in));child=JSON.convertValue(fixture.path("final").path("observation").get("imageInvocation"),Map.class);
                refs.add(child.get("costRouteReference"));String id=(String)child.get("invocationId");
                var original=new UsageCostService.Binding(task.scope(),"owner","task","NATIVE_PROVIDER_CALL","exec1:"+id);
                var qty=ImageUsageNormalizer.normalize("g","im","s",fixture.path("terminal").get("evidence"));
                when(costs.observation(original,"started")).thenReturn(Optional.of(new UsageCostService.Observation(original,"started",0,fixture.get("started").toString(),null,null,4)));
                when(costs.observation(original,"terminal")).thenReturn(Optional.of(new UsageCostService.Observation(original,"terminal",1,fixture.get("terminal").toString(),qty,null,5)));
            }
        }
        String policy=image?"NATIVE_PINNED_IMAGE_V1":"NATIVE_LOCAL_ARTIFACT_TEXT_V1";
        configuration=Map.of("executionStrategyVersion","native","contextPolicyId","context","contextPolicyVersion",H,"toolPolicyVersion",C,"capturePolicyVersion",policy,"routeRefs",refs);
        var config=NativeCaptureEvidence.costConfiguration(configuration);
        admitted=new PersonalWorkspaceExecutionEntity().setTaskId("task").setRunId("run1").setTargetAgentId("agent").setBusinessScope("TASK").setExecutionProtocolVersion(6).setUsageMode("RESPONSE_V2").setCaptureProtocol("METERED_CAPTURE_V2").setGrantRevision(1L).setCaptureProfileSha256(NativeCaptureEvidence.configurationProfile(config));
        when(admissions.find("t","c","owner","exec1")).thenReturn(admitted);when(skills.find("t","c","owner","exec1")).thenReturn(skill);
        var measurement=new HashMap<>(config.canonical());measurement.put("skillPackages",List.of(Map.of("skillKey","summary","skillVersion","1.0.0","packageSha",H)));measurementSha=CanonicalValueEncoding.digest(measurement);
        String route=image?NativeCaptureEvidence.digest(List.of("NATIVE_COMPOSITE_ROUTE_V1","native-parent","parent",child.get("childRouteSha256"))):H;
        var plan=new HashMap<String,Object>(Map.of("captureProtocol","METERED_CAPTURE_V2","executionConfigSha256",H,"rootSourceId","root","snapshotSha256",skill.getSnapshotSha256(),"engineVersion","codex-cli-0.167.0","coveragePolicyVersion",policy,"costConfiguration",configuration,"dispatchGroup",Map.of("version","NATIVE_DISPATCH_GROUP_V1","executionConfigSha256",H,"routeSha256",route,"toolConfigSha256",C)));
        if(image)plan.put("imageRoute",Map.of("model","parent","childRouteSha256",child.get("childRouteSha256")));
        var p=add("PLAN",List.of(),plan);var members=new ArrayList<UsageCaptureProjection.Row>();
        members.add(add("SOURCE",List.of("root","0"),Map.of("sourceId","root","epoch","0","adapterVersion","RESPONSES_TEXT_TOKENS_V1","sourceKind","METERED_HTTP_RESPONSES_V1","channelNamespace","native-parent")));
        for(int i=1;i<=2;i++){
            String attempt=Integer.toString(i).repeat(64);
            members.add(add("REQUEST",List.of("root","0",Integer.toString(i)),Map.of("sourceId","root","epoch","0","sequence",Integer.toString(i),"resolution","RESPONSE","sourceAttemptId",attempt,"reasonCodes",List.of("BILLING_IDENTITY_UNKNOWN"))));
            members.add(add("RESPONSE",List.of(attempt),Map.of("sourceId","root","epoch","0","sourceAttemptId",attempt,"reasonCodes",List.of("BILLING_IDENTITY_UNKNOWN"),"measuredQuantities",Map.of("coverage","COMPLETE","normalizationBasis","RESPONSES_TEXT_TOKENS_V1","tokens",Map.of("INPUT","3","OUTPUT","1")))));
        }
        var f=add("SOURCE_FINAL",List.of("root","0"),Map.of("sourceId","root","epoch","0","requestCount","2","responseCount","2","reservedSequenceHighWatermark","2","recordsSha256",tuples(members),"closeState","DRAINED","reasonCodes",List.of()));
        var close=new HashMap<String,Object>(Map.of("planEvidenceSha256",p.evidenceSha256(),"sourceCount","1","sourcesSha256",tuples(List.of(f)),"closeState","DRAINED","reasonCodes",List.of()));if(child!=null)close.put("imageInvocation",child);
        add("CAPTURE_FINAL",List.of(),close);
    }
    private ScopedCapture read(){var fold=decoder.openScoped(task,execution);if(fold==null)return null;rows.values().forEach(fold::accept);return fold.finish();}
    @Test void readsOriginalAdmissionPlanPackagesAndTwoActualModelCalls()throws Exception{
        fixture(false);var measured=read();assertNotNull(measured);assertEquals(measurementSha,measured.measurementSha());assertEquals(2,measured.operations().size());
        assertTrue(measured.operations().stream().allMatch(o->o.resource().namespace().equals("native-parent")&&o.resource().modelOrTool().equals("parent")));
        assertNotEquals(measured.operations().get(0).operation().fact().operation(),measured.operations().get(1).operation().fact().operation());
        verifyNoInteractions(costs);
    }
    @Test void readsChildOnlyFromActualFinalReferenceAndOriginalLedger()throws Exception{
        fixture(true);var measured=read();assertNotNull(measured);assertEquals(measurementSha,measured.measurementSha());assertEquals(3,measured.operations().size());
        assertEquals(1,measured.operations().stream().filter(o->o.resource().kind().equals("NATIVE_PROVIDER_CALL")).count());
        reset(costs);assertNull(read());verify(costs,never()).record(any());
    }
    @Test void refusesForeignAdmissionSnapshotAndBindingWithoutInventingConfiguration()throws Exception{
        fixture(false);admitted.setRunId("other");assertNull(read());admitted.setRunId("run1");
        admitted.setCaptureProfileSha256(H);assertNull(read());admitted.setCaptureProfileSha256(NativeCaptureEvidence.configurationProfile(NativeCaptureEvidence.costConfiguration(configuration)));
        skill.setSnapshotSha256(C);assertThrows(IllegalStateException.class,this::read);
        skill.setSnapshotSha256(NativeCaptureEvidence.digest(JSON.readValue(skill.getSnapshotJson(),Map.class)));
        skill.setTaskSkillBindingJson(skill.getTaskSkillBindingJson().replace("installed","foreign"));assertNull(read());
    }
    @Test void incompleteCaptureReturnsNoPartialCostsAndDamagedHashFails()throws Exception{
        fixture(false);var first=rows.firstEntry();rows.put(first.getKey(),new UsageCaptureProjection.Row(first.getKey(),2,"1",first.getValue().evidenceSha256(),first.getValue().evidenceJson()));assertNull(read());
        rows.put(first.getKey(),new UsageCaptureProjection.Row(first.getKey(),1,"1",H,first.getValue().evidenceJson()));assertThrows(IllegalStateException.class,this::read);
    }
}
