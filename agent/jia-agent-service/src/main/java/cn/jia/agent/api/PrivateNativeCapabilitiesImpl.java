package cn.jia.agent.api;

import cn.jia.agent.position.*;
import cn.jia.agent.security.AgentRuntimeAuthenticationService;
import cn.jia.agent.service.*;
import cn.jia.agent.mapper.skill.SkillManagementMapper;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.function.*;

@Service
public final class PrivateNativeCapabilitiesImpl implements PrivateNativeCapabilities {
    private final AgentRuntimeAuthenticationService authentication; private final SkillCapabilityLookup live;
    private final InstalledSkillDescriptorLookup installed; private final PositionSkillService positions; private final AgentIdentityService identities;
    private final SkillManagementMapper managed; private final ModelConversationToolAdapter models;
    public PrivateNativeCapabilitiesImpl(AgentRuntimeAuthenticationService authentication,SkillCapabilityLookup live,InstalledSkillDescriptorLookup installed,PositionSkillService positions,AgentIdentityService identities,SkillManagementMapper managed,ModelConversationToolAdapter models){
        this.authentication=authentication;this.live=live;this.installed=installed;this.positions=positions;this.identities=identities;this.managed=managed;this.models=models;}
    private SkillCapabilityLookup.View current(SkillCapabilityLookup.Scope s,String socket){
        var p=authentication.currentRegisteredProof(s.tenantId(),s.clientId(),s.agentId());
        if(p==null||!s.ownerJiacn().equals(p.scope().ownerJiacn()))throw new IllegalStateException("Private runtime changed");
        var fence=new AgentManagedSessionLookup.SessionFence(p.installationId(),p.hostId(),p.scope().runtimeInstanceId(),p.sessionGeneration());
        var view=live.lookup(s,fence); if(view==null||view.state()!=SkillCapabilityLookup.State.AVAILABLE)return null;
        if(!socket.equals(view.sessionId())||!fence.equals(view.fence()))throw new IllegalStateException("Private runtime changed");return view;
    }
    @Override public <T>T prepared(SkillCapabilityLookup.Scope s,String socket,boolean tools,Function<Supplier<Selection>,T> action){
        var v=current(s,socket);if(v==null||v.catalog().skills().stream().noneMatch(f->f.enabled()&&"INSTRUCTION".equals(f.kind())))return action.apply(Selection::empty);
        return installed.withPreparedRead(s,()->action.apply(()->freeze(s,socket,v,tools)));
    }
    private Selection freeze(SkillCapabilityLookup.Scope s,String socket,SkillCapabilityLookup.View v,boolean tools){
        if(!v.sameExecutionFacts(current(s,socket)))throw new IllegalStateException("Private runtime changed");
        var scope=new PositionAuthority.Scope(s.tenantId(),s.clientId(),s.ownerJiacn());var state=positions.currentState(scope);
        var identity=identities.requireRegistrationIdentityInScope(s.tenantId(),s.clientId(),s.ownerJiacn(),s.agentId());
        if(identity.getBindingId()==null||!s.agentId().equals(identity.getCanonicalAgentId()))return Selection.empty();
        var keys=new HashSet<String>();for(var a:state.appointments().values())if(a.active()&&a.bindingId()==identity.getBindingId()&&s.agentId().equals(a.agentId())){var p=state.positions().get(a.positionId());if(p!=null)keys.addAll(p.skillKeys());}
        var grants=new HashMap<String,PositionSkillState.Installation>();for(var i:state.installations().values())if(i.usable()&&keys.contains(i.skillKey())&&i.bindingId()==identity.getBindingId()&&s.agentId().equals(i.agentId())&&v.fence().runtimeInstallationId().equals(i.runtimeInstallationId())&&v.fence().hostId().equals(i.runtimeHostId()))grants.put(i.managedInstallationId(),i);
        var pointers=new HashSet<String>();for(var m:managed.statesForPrivateAdmission(s.tenantId(),s.clientId(),s.agentId()))if(Boolean.TRUE.equals(m.getDesiredEnabled())&&Boolean.TRUE.equals(m.getAdmissionEnabled())&&m.getPendingChangeId()==null)pointers.add(m.getActiveInstallationId());
        var byKey=new TreeMap<String,List<Map<String,Object>>>();for(var f:v.catalog().skills()){
            var g=grants.get(f.skillInstallationId());if(!f.enabled()||!"INSTRUCTION".equals(f.kind())||f.manifestSha256()!=null||!pointers.contains(f.skillInstallationId())||g==null||!g.skillKey().equals(f.skillKey())||!f.packageSha256().equals(g.packageDigest()))continue;
            var skill=new LinkedHashMap<String,Object>();skill.put("key",f.skillKey());skill.put("version",f.skillVersion());skill.put("packageSha256",f.packageSha256());skill.put("manifestSha256",null);skill.put("installationId",f.skillInstallationId());
            byKey.computeIfAbsent(f.skillKey(),unused->new ArrayList<>()).add(Map.of("skill",skill));
        }
        var selected=byKey.values().stream().filter(l->l.size()==1).map(List::getFirst).toList();
        if(!tools||selected.isEmpty())return new Selection(selected,Map.of());
        var catalog=models.nativeCatalog(NativePrivateModelCalls.context(s,v.fence().runtimeInstallationId(),v.fence().hostId()),scope,selected).stream().sorted(Comparator.comparing(t->(String)t.get("name"))).toList();
        if(catalog.isEmpty())return new Selection(selected,Map.of());
        var result=new LinkedHashMap<String,Object>();result.put("version",1);result.put("tools",catalog);result.put("catalogSha256",PositionSkillService.hash(SkillCanonicalJson.write(result)));return new Selection(selected,Map.copyOf(result));
    }
}
