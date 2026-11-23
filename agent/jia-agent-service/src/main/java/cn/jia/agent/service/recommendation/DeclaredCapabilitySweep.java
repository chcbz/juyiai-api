package cn.jia.agent.service.recommendation;

import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.core.security.FixedTenantAdmissionPolicy;
import cn.jia.task.scheduling.CronSchedule;
import cn.jia.task.scheduling.CronScheduleService;
import cn.jia.task.scheduling.ScheduleKey;
import cn.jia.task.scheduling.ScheduleService;
import cn.jia.task.scheduling.ScheduledInvocation;
import cn.jia.task.scheduling.ScheduledJobHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** One indexed page and its durable follow-ups per transaction. Repair callbacks re-read authority. */
@Service
@ConditionalOnProperty(name="jia.agent.capability-index.enabled",havingValue="true")
final class DeclaredCapabilitySweep implements ScheduledJobHandler, ApplicationListener<ApplicationReadyEvent> {
    private static final String NAME="agent.capability-sweep";
    private final CronSchedule definition;
    private final CronScheduleService cron;
    private final ScheduleService schedules;
    private final AgentRuntimeDao rows;
    private final FixedTenantAdmissionPolicy scope;
    private final TransactionTemplate tx;
    DeclaredCapabilitySweep(CronScheduleService cron,ScheduleService schedules,AgentRuntimeDao rows,FixedTenantAdmissionPolicy scope,PlatformTransactionManager manager,@Value("${jia.agent.capability-index.repair-cron:0 17 * * * *}") String expression){
        this.cron=cron;this.schedules=schedules;this.rows=rows;this.scope=scope;this.tx=new TransactionTemplate(manager);
        this.definition=new CronSchedule(scope.clientId()==null?"":scope.clientId(),NAME,"",expression,"UTC",CronSchedule.Misfire.COALESCE);
    }
    public String getName(){return NAME;}
    public void onApplicationEvent(ApplicationReadyEvent event){cron.register(definition);}
    public void executeScheduled(ScheduledInvocation invocation){
        var key=invocation.key();if(!scope.businessTenantId().equals(invocation.tenantId())||!definition.clientId().equals(key.clientId())||!NAME.equals(key.handler())||!"".equals(key.targetNode()))throw new IllegalArgumentException("Unexpected capability sweep scope");
        String cursor=key.businessKey();long after=cursor.startsWith("cron:")?0:Long.parseLong(cursor);
        tx.executeWithoutResult(st->{
            var page=rows.capabilityRepairPage(scope.businessTenantId(),scope.clientId(),after);long now=System.currentTimeMillis();
            for(var row:page){scope.requireAdmitted(row.getTenantId(),row.getClientId());schedules.signal(new ScheduleKey(row.getClientId(),DeclaredCapabilityRepair.NAME,row.getId().toString(),""),now);}
            if(page.size()==AgentRuntimeDao.REPAIR_PAGE_SIZE)schedules.signal(new ScheduleKey(key.clientId(),NAME,page.getLast().getId().toString(),""),now);
        });
    }
}
