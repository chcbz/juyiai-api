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
    private String commitment(Scope scope,String task,String agent){return billing.heldInitialIntent(new TaskBillingService.Task(new EconomyScope(scope.tenantId(),scope.clientId()),scope.ownerJiacn(),task),agent);}
    public cn.jia.agent.service.NativeTaskPreparation.Output pending(Scope scope,String task,String agent){
        String sha=commitment(scope,task,agent);return sha==null?null:decisions.pendingOutput(scope,task,agent,sha);
    }
    public void admit(Scope scope,String task,Target target,String execution,String policy,java.util.List<Map<String,Object>> selections,Map<String,Object> outputs){
        String sha=commitment(scope,task,target.agentId());
        if(sha!=null)decisions.consumeFirst(scope,task,target,sha,execution,policy,selections,outputs);
    }
}
