package cn.jia.agent.service.impl;

import cn.jia.agent.service.*;
import cn.jia.agent.security.RegisteredPricingProfiles;
import cn.jia.agent.security.PreparedRuntimeEpochFence;
import cn.jia.agent.security.CostProfileReportJson;
import cn.jia.agent.skill.quote.CostConfigurationObservation;
import cn.jia.agent.service.usage.NativeCaptureEvidence;
import java.util.*;
import org.springframework.stereotype.Service;

/** No authority to execute. Called only after verifying the grant, selected packages and exact live installation.
 * Commits expected configuration; the independently produced PLAN and complete usage must still prove coverage. */
@Service
public class DispatchCaptureBinding {
    private final RegisteredPricingProfiles profiles;
    public DispatchCaptureBinding(RegisteredPricingProfiles profiles){this.profiles=profiles;}
    private record Package(String key,String version,String sha,String installation) {}
    public String forAuthorizedExecution(TaskAssigneeAuthorityService.Target target,AgentManagedSessionLookup.SessionFence fence,String execution,
            List<SkillExecutionService.AuthorizedSkill> authorized,Map<String,Object> snapshot){
        if(fence==null || authorized.isEmpty() || !SkillExecutionService.NATIVE_SKILL_SCRIPT_POLICY.equals(snapshot.get("toolPolicyRef")))return null;
        var selected=new HashSet<Package>();
        for(var a:authorized){
            var binding=a.executionBinding();var skill=a.skill();
            if(skill.manifestSha256()!=null || !execution.equals(binding.get("executionId")) || !target.agentId().equals(binding.get("agentId"))
                || !skill.key().equals(binding.get("skillKey")) || !skill.version().equals(binding.get("skillVersion"))
                || !skill.packageSha256().equals(binding.get("packageSha256")) || binding.get("manifestSha256")!=null
                || !(binding.get("skillInstallationId") instanceof String installation) || installation.isBlank())return null;
            if(!selected.add(new Package(skill.key(),skill.version(),skill.packageSha256(),installation)))return null;
        }
        RegisteredPricingProfiles.Observed observed;
        try{observed=profiles.atDispatch(target,fence);}catch(PreparedRuntimeEpochFence.Busy busy){return null;}
        if(observed==null)return null;
        CostConfigurationObservation expected=null;
        for(var profile:CostProfileReportJson.read(observed.canonical()).profiles()){
            var packages=new HashSet<Package>();
            for(var slot:profile.scopePattern())packages.add(new Package(slot.skillKey(),slot.skillVersion(),slot.packageSha(),slot.installId()));
            // Never manufacture a combined execution configuration from unrelated single-package profiles.
            if(!selected.equals(packages))continue;
            var candidate=profile.configuration();
            // Dispatch has an actual package set, not the quote planner's scope/priority. Ambiguity is not permission to guess.
            if(expected!=null && !expected.equals(candidate))return null;
            expected=candidate;
        }
        return expected==null?null:NativeCaptureEvidence.configurationProfile(expected);
    }
}
