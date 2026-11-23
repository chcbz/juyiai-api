package cn.jia.agent.service.usage;

import cn.jia.agent.dao.TaskExecutionClosureDao;
import cn.jia.core.security.FixedTenantAdmissionPolicy;
import cn.jia.economy.usage.TaskMembership;
import cn.jia.task.scheduling.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Recovery uses the indexed durable PENDING set even if scheduling was disabled when a task closed. */
@Service
@ConditionalOnProperty(name="cyf.task.scheduling.enabled",havingValue="true")
public class TaskMembershipSweep implements ScheduledJobHandler,ApplicationListener<ApplicationReadyEvent> {
    private static final String NAME="native.task-membership-sweep";
    private final TaskExecutionClosureDao rows;private final FixedTenantAdmissionPolicy scope;private final TransactionTemplate tx;
    private final ScheduleService schedules;private final CronScheduleService cron;private final CronSchedule definition;
    public TaskMembershipSweep(TaskExecutionClosureDao rows,FixedTenantAdmissionPolicy scope,PlatformTransactionManager manager,ScheduleService schedules,
            CronScheduleService cron,@Value("${jia.agent.task-membership.recovery-cron:0 * * * * *}") String expression){
        this.rows=rows;this.scope=scope;this.tx=new TransactionTemplate(manager);this.schedules=schedules;this.cron=cron;
        definition=new CronSchedule(scope.clientId()==null?"":scope.clientId(),NAME,"",expression,"UTC",CronSchedule.Misfire.COALESCE);
    }
    public String getName(){return NAME;}
    public void onApplicationEvent(ApplicationReadyEvent event){cron.register(definition);}
    public void executeScheduled(ScheduledInvocation invocation){
        var k=invocation.key();if(!scope.businessTenantId().equals(invocation.tenantId())||!definition.clientId().equals(k.clientId())||!NAME.equals(k.handler())||!k.targetNode().isEmpty())throw new IllegalArgumentException("Invalid membership discovery scope");
        String after=k.businessKey().startsWith("cron:")?"":k.businessKey();
        tx.executeWithoutResult(st->{var page=rows.pendingPage(invocation.tenantId(),scope.clientId(),after);long now=System.currentTimeMillis();
            for(var row:page){scope.requireAdmitted(invocation.tenantId(),row.client());schedules.signal(new ScheduleKey(row.client(),TaskMembershipDelivery.NAME,row.fact(),""),now);}
            if(page.size()==TaskMembership.PAGE)schedules.signal(new ScheduleKey(k.clientId(),NAME,page.getLast().fact(),""),now);
        });
    }
}
