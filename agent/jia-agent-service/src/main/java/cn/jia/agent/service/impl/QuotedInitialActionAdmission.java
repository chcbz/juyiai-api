package cn.jia.agent.service.impl;

import cn.jia.agent.service.AgentTaskExecutionGrantService.Scope;
import cn.jia.agent.service.TaskAssigneeAuthorityService.Target;
import cn.jia.economy.service.EconomyScope;
import cn.jia.economy.task.TaskBillingService;
import java.util.Map;
import org.springframework.stereotype.Service;

/** An existing held quote may commit a first action. This hook neither authorizes the task nor rechecks its price. */
@Service
public class QuotedInitialActionAdmission implements cn.jia.agent.service.TaskInitialActionOutput {
    private final TaskBillingService billing;private final NativeTaskPreparationService decisions;
    public QuotedInitialActionAdmission(TaskBillingService billing,NativeTaskPreparationService decisions){this.billing=billing;this.decisions=decisions;}
    private TaskBillingService.InitialCommitment commitment(Scope scope,String task,String agent){return billing.heldInitialCommitment(new TaskBillingService.Task(new EconomyScope(scope.tenantId(),scope.clientId()),scope.ownerJiacn(),task),agent);}
    public cn.jia.agent.service.NativeTaskPreparation.Output pending(Scope scope,String task,String agent){
        var held=commitment(scope,task,agent);return held==null?null:decisions.pendingOutput(scope,task,agent,held.intentSha256());
    }
    public String admit(Scope scope,String task,Target target,String execution,String policy,java.util.List<Map<String,Object>> selections,Map<String,Object> outputs){
        var held=commitment(scope,task,target.agentId());
        if(held==null||!decisions.consumeFirst(scope,task,target,held.intentSha256(),execution,policy,selections,outputs))return null;
        var g=held.group();if(!"NATIVE_PINNED_IMAGE_V1".equals(g.capturePolicyVersion()))return null;
        if(selections.size()!=1||!cn.jia.agent.service.SkillExecutionService.NATIVE_SKILL_SCRIPT_POLICY.equals(policy))throw new IllegalStateException("Image commitment is not the authorized initial skill");
        return cn.jia.agent.service.usage.NativeCaptureEvidence.imageProfile(g.dispatchGroupVersion(),g.executionConfigSha256(),g.localRouteSha256(),g.toolConfigSha256());
    }
}
