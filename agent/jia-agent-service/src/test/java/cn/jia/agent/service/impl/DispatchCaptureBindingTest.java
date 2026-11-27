package cn.jia.agent.service.impl;

import cn.jia.agent.security.*;
import cn.jia.agent.service.*;
import cn.jia.agent.service.usage.NativeCaptureEvidence;
import cn.jia.agent.skill.quote.*;
import cn.jia.economy.pricing.CostRouteReference;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DispatchCaptureBindingTest {
    private static final String H="a".repeat(64), OTHER="b".repeat(64);
    private final RegisteredPricingProfiles profiles=mock(RegisteredPricingProfiles.class);
    private final DispatchCaptureBinding binding=new DispatchCaptureBinding(profiles);
    private final TaskAssigneeAuthorityService.Target target=new TaskAssigneeAuthorityService.Target("t","c","o","agent",1,null);
    private final AgentManagedSessionLookup.SessionFence fence=new AgentManagedSessionLookup.SessionFence("installation","host","instance",1);
    private final Map<String,Object> snapshot=Map.of("toolPolicyRef",SkillExecutionService.NATIVE_SKILL_SCRIPT_POLICY);
    private MeasuredCostProfile profile(String id,int priority,String context,String... skills){
        var slots=new ArrayList<MeasuredCostProfile.Slot>();
        for(int i=0;i<skills.length;i++)slots.add(new MeasuredCostProfile.Slot(i,"capability."+i,"1",H,List.of(),skills[i],"1",H,"installed-"+skills[i]));
        return new MeasuredCostProfile(id,1,priority,slots,"native","context",context,H,"NATIVE_LOCAL_ARTIFACT_TEXT_V1",
            List.of(new CostRouteReference("MODEL_RESPONSE","INFERENCE","channel","model","RESPONSES_TEXT_TOKENS_V1")));
    }
    private SkillExecutionService.AuthorizedSkill skill(String name){
        return new SkillExecutionService.AuthorizedSkill(new SkillExecutionService.Skill(name,"1",H,null),
            Map.of("executionId","execution","agentId","agent","skillKey",name,"skillVersion","1","packageSha256",H,"skillInstallationId","installed-"+name));
    }
    private void registered(MeasuredCostProfile... configurations){
        var report=new CostProfileReport(1,List.of(configurations));
        when(profiles.atDispatch(target,fence)).thenReturn(new RegisteredPricingProfiles.Observed("agent","o",1,"installation","host","instance",1,1,report.sha256(),CostProfileReportJson.write(report)));
    }
    private String dispatch(SkillExecutionService.AuthorizedSkill... skills){return binding.forAuthorizedExecution(target,fence,"execution",List.of(skills),snapshot);}

    @Test void commitsTypedConfigurationWithoutImageOutputsOrPricingIdentity(){
        var p=profile("p",0,H,"summary");registered(p);
        assertEquals(NativeCaptureEvidence.configurationProfile(p.configuration()),dispatch(skill("summary")));
        assertNotEquals(NativeCaptureEvidence.imageProfile("NATIVE_DISPATCH_GROUP_V1",H,H,H),dispatch(skill("summary")));
        verify(profiles,times(2)).atDispatch(target,fence);
    }
    @Test void exactWholePackageSetNotSubsetOrUnionOfIndependentProfiles(){
        var pair=profile("pair",0,H,"summary","extract");registered(pair);
        assertNotNull(dispatch(skill("extract"),skill("summary")));
        assertNull(dispatch(skill("summary")));
        assertNull(dispatch(skill("summary"),skill("extract"),skill("extra")));
        registered(profile("one",0,H,"summary"),profile("two",1,H,"extract"));
        assertNull(dispatch(skill("summary"),skill("extract")));
    }
    @Test void equalSemanticsDeduplicateButDistinctSemanticsNeverChoosePriority(){
        var p=profile("one",0,H,"summary");registered(p,profile("two",1,H,"summary"));
        assertEquals(NativeCaptureEvidence.configurationProfile(p.configuration()),dispatch(skill("summary")));
        registered(p,profile("two",1,OTHER,"summary"));assertNull(dispatch(skill("summary")));
    }
    @Test void exactAuthorizedPackageAndInstallationAreRequired(){
        registered(profile("p",0,H,"summary"));var valid=skill("summary");
        for(var field:List.of("executionId","agentId","skillKey","skillVersion","packageSha256","skillInstallationId")){
            var changed=new HashMap<>(valid.executionBinding());changed.put(field,"different");
            assertNull(dispatch(new SkillExecutionService.AuthorizedSkill(valid.skill(),changed)),field);
        }
        assertNull(dispatch(valid,valid));
        assertNull(dispatch(new SkillExecutionService.AuthorizedSkill(new SkillExecutionService.Skill("summary","1",OTHER,null),valid.executionBinding())));
    }
    @Test void absentAdmissionInputsDoNotReadRegistryAndMissingReportIsUnknown(){
        assertNull(binding.forAuthorizedExecution(target,null,"execution",List.of(skill("summary")),snapshot));
        assertNull(dispatch());
        assertNull(binding.forAuthorizedExecution(target,fence,"execution",List.of(skill("summary")),Map.of("toolPolicyRef","unknown")));
        verifyNoInteractions(profiles);
        assertNull(dispatch(skill("summary")));
        verify(profiles).atDispatch(target,fence);
    }
    @Test void corruptRegistryIsNotSilentlyTreatedAsMissingConfiguration(){
        when(profiles.atDispatch(target,fence)).thenReturn(new RegisteredPricingProfiles.Observed("agent","o",1,"installation","host","instance",1,1,H,"[]"));
        assertThrows(IllegalArgumentException.class,()->dispatch(skill("summary")));
    }
}
