package cn.jia.agent.service.impl;

import cn.jia.agent.service.*;
import cn.jia.agent.security.RegisteredPricingProfiles;
import cn.jia.agent.security.PreparedRuntimeEpochFence;
import cn.jia.agent.service.usage.NativeCaptureEvidence;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.stereotype.Service;

/** No authority to execute. Called only after verifying the grant, selected package and exact live installation. */
@Service
public class DispatchCaptureBinding {
    private final RegisteredPricingProfiles profiles;private final ObjectMapper json;
    public DispatchCaptureBinding(RegisteredPricingProfiles profiles,ObjectMapper json){this.profiles=profiles;this.json=json;}
    public String forAuthorizedExecution(TaskAssigneeAuthorityService.Target target,AgentManagedSessionLookup.SessionFence fence,String execution,
            List<SkillExecutionService.AuthorizedSkill> authorized,Map<String,Object> snapshot){
        if(fence==null || authorized.size()!=1 || !SkillExecutionService.NATIVE_SKILL_SCRIPT_POLICY.equals(snapshot.get("toolPolicyRef")) || snapshot.get("privateTools")!=null ||
            !(snapshot.get("outputs") instanceof Map<?,?> outputs) || outputs.get("dataSchema")!=null || !(outputs.get("mimeTypes") instanceof List<?> types) || !types.contains("image/png") ||
            !(outputs.get("artifactsRequired") instanceof Boolean required) || !(outputs.get("multiple") instanceof Boolean multiple))return null;
        var a=authorized.getFirst();var binding=a.executionBinding();var skill=a.skill();
        if(skill.manifestSha256()!=null || !execution.equals(binding.get("executionId")) || !target.agentId().equals(binding.get("agentId")) || !skill.key().equals(binding.get("skillKey")) || !skill.version().equals(binding.get("skillVersion")) || !skill.packageSha256().equals(binding.get("packageSha256")) || binding.get("manifestSha256")!=null)return null;
        RegisteredPricingProfiles.Observed observed;
        try{observed=profiles.atDispatch(target,fence);}catch(PreparedRuntimeEpochFence.Busy busy){return null;}
        if(observed==null)return null;
        for(var p:json.readTree(observed.canonical())){
            if(!skill.key().equals(p.path("skillKey").asText()) || !skill.packageSha256().equals(p.path("packageSha256").asText()) ||
                !p.path("skillInstallationId").asText().equals(binding.get("skillInstallationId")))continue;
            int i=(required?2:0)+(multiple?1:0);
            return NativeCaptureEvidence.imageProfile("NATIVE_DISPATCH_GROUP_V1",p.path("execution"+i).asText(),p.path("referenceRouteSha256").asText(),p.path("tools"+i).asText());
        }
        return null;
    }
}
