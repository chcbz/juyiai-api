package cn.jia.agent.api;

import cn.jia.agent.entity.AgentIdentityRegistryEntity;
import cn.jia.agent.entity.skill.SkillManagedStateEntity;
import cn.jia.agent.mapper.skill.SkillManagementMapper;
import cn.jia.agent.position.*;
import cn.jia.agent.security.*;
import cn.jia.agent.service.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.function.Supplier;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class PrivateNativeCapabilitiesTest {
    @Test void genuineInstructionUsesDesiredPointerAndPrefixedPositionReceiptNotLegacyRunnerFlag(){
        var auth=mock(AgentRuntimeAuthenticationService.class); var live=mock(SkillCapabilityLookup.class);
        var installed=mock(InstalledSkillDescriptorLookup.class); var positions=mock(PositionSkillService.class);var identities=mock(AgentIdentityService.class);
        var managed=mock(SkillManagementMapper.class); var models=mock(ModelConversationToolAdapter.class);
        var service=new PrivateNativeCapabilitiesImpl(auth,live,installed,positions,identities,managed,models);
        String pkg="a".repeat(64);var scope=new SkillCapabilityLookup.Scope("t","c","o","a");var role=new PositionAuthority.Scope("t","c","o");
        var fence=new AgentManagedSessionLookup.SessionFence("install","host","instance",1);
        when(auth.currentRegisteredProof("t","c","a")).thenReturn(new AgentRuntimeAuthenticationService.Proof(new AgentRuntimeAuthentication.Scope("t","c","o","a","instance"),"install","host",1,"token-sha",1,1));
        when(live.lookup(scope,fence)).thenReturn(new SkillCapabilityLookup.View(SkillCapabilityLookup.State.AVAILABLE,"socket",fence,new SkillCapabilityLookup.Catalog("1",List.of(new SkillCapabilityLookup.RuntimeSkill("skill","1.0.0",pkg,null,null,SkillCapabilityLookup.Readiness.NOT_EXECUTABLE,null,null,null,"si",null,"INSTRUCTION",null,true,1L)))));
        when(installed.withPreparedRead(eq(scope),any())).thenAnswer(i->i.<Supplier<?>>getArgument(1).get());
        when(identities.requireRegistrationIdentityInScope("t","c","o","a")).thenReturn(new AgentIdentityRegistryEntity().setCanonicalAgentId("a").setBindingId(1L));
        var state=new PositionSkillState(1,Map.of(),Map.of("p",new PositionSkillState.Position("p","P",1,"models:platform",true,List.of("skill"),1)),Map.of("a",new PositionSkillState.Appointment("a","p","a",1,true,1)),Map.of("i",new PositionSkillState.Installation("i","a",1,"skill",false,true,1,PositionSkillState.InstallState.INSTALLED,"digest","sha256:"+pkg,null,"install","host",null,"si")));
        when(positions.currentState(role)).thenReturn(state);
        var pointer=new SkillManagedStateEntity().setActiveInstallationId("si").setDesiredEnabled(true).setAdmissionEnabled(false);
        when(managed.statesForPrivateAdmission("t","c","a")).thenReturn(List.of(pointer));
        var selection=service.prepared(scope,"socket",false,Supplier::get);assertEquals(1,selection.skills().size());assertTrue(selection.catalog().isEmpty());
        pointer.setDesiredEnabled(false);assertTrue(service.prepared(scope,"socket",false,Supplier::get).skills().isEmpty());
        assertThrows(IllegalStateException.class,()->service.prepared(scope,"wrong",true,Supplier::get));verifyNoInteractions(models);
    }
}
