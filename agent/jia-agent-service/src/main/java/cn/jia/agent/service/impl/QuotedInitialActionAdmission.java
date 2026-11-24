package cn.jia.agent.service.impl;

import cn.jia.agent.service.AgentTaskExecutionGrantService.Scope;
import cn.jia.agent.service.TaskAssigneeAuthorityService.Target;
import cn.jia.economy.service.EconomyScope;
import cn.jia.economy.task.TaskBillingService;
import java.util.Map;
import org.springframework.stereotype.Service;

/** An existing held quote may commit a first action. This hook neither authorizes the task nor rechecks its price. */
@Service
public class QuotedInitialActionAdmission {
    private final TaskBillingService billing;private final NativeTaskPreparationService decisions;
    public QuotedInitialActionAdmission(TaskBillingService billing,NativeTaskPreparationService decisions){this.billing=billing;this.decisions=decisions;}
    public void admit(Scope scope,String task,Target target,String execution,boolean localOnly,Map<String,Object> outputs){
        String sha=billing.heldInitialIntent(new TaskBillingService.Task(new EconomyScope(scope.tenantId(),scope.clientId()),scope.ownerJiacn(),task),target.agentId());
        if(sha!=null)decisions.consumeFirst(scope,task,target,sha,execution,localOnly,outputs);
    }
}
