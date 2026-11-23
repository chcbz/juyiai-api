package cn.jia.agent.service.usage;

import cn.jia.agent.dao.TaskExecutionClosureDao;
import cn.jia.core.security.FixedTenantAdmissionPolicy;
import cn.jia.economy.usage.TaskMembershipReceipt;
import cn.jia.task.scheduling.ScheduleKey;
import cn.jia.task.scheduling.ScheduleService;
import cn.jia.task.scheduling.ScheduledInvocation;
import cn.jia.task.scheduling.ScheduledJobHandler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Root transaction writes the durable pending fact; scheduled discovery owns notification. A worker transfers at most one page per turn. */
@Service
public class TaskMembershipDelivery implements ScheduledJobHandler {
    static final String NAME="native.task-membership";
    private final TaskExecutionClosureDao source; private final TaskMembershipReceipt receiver;
    private final ObjectProvider<ScheduleService> schedules; private final FixedTenantAdmissionPolicy scope;
    public TaskMembershipDelivery(TaskExecutionClosureDao source,TaskMembershipReceipt receiver,ObjectProvider<ScheduleService> schedules,FixedTenantAdmissionPolicy scope){
        this.source=source;this.receiver=receiver;this.schedules=schedules;this.scope=scope;
    }
    public String getName(){return NAME;}
    public void executeScheduled(ScheduledInvocation invocation){
        var k=invocation.key();scope.requireAdmitted(invocation.tenantId(),k.clientId());
        if(!NAME.equals(k.handler())||!k.targetNode().isEmpty()||TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Invalid membership invocation");
        var terminal=source.pending(invocation.tenantId(),k.clientId(),k.businessKey());if(terminal==null)return;
        var cursor=receiver.open(terminal);
        if(!cursor.verified())cursor=receiver.accept(terminal,cursor.revision(),source.members(terminal,cursor.revision()));
        if(cursor.verified())source.delivered(terminal);else schedules.getObject().signal(k,System.currentTimeMillis());
    }
}
