package cn.jia.agent.service.usage;

import cn.jia.core.security.FixedTenantAdmissionPolicy;
import cn.jia.economy.usage.ConfirmedSampleProjection;
import cn.jia.task.scheduling.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Service;

/** Discovery signals durable Task work; a single bad sample cannot abort a page of measurements. */
@Service
@ConditionalOnProperty(name="cyf.task.scheduling.enabled",havingValue="true")
public class ConfirmedSampleRecovery implements ScheduledJobHandler, ApplicationListener<ApplicationReadyEvent> {
    private static final String NAME="native.confirmed-sample";
    private final ConfirmedSampleProjection samples;
    private final NativeSampleResponseDecoder decoder;
    private final FixedTenantAdmissionPolicy scope;
    private final ScheduleService schedules;
    private final CronScheduleService cron;
    private final CronSchedule definition;

    public ConfirmedSampleRecovery(ConfirmedSampleProjection samples,NativeSampleResponseDecoder decoder,
            FixedTenantAdmissionPolicy scope,ScheduleService schedules,CronScheduleService cron,
            @Value("${jia.agent.confirmed-sample.recovery-cron:0 * * * * *}") String expression){
        this.samples=samples;this.decoder=decoder;this.scope=scope;this.schedules=schedules;this.cron=cron;
        definition=new CronSchedule(scope.clientId()==null?"":scope.clientId(),NAME,"",expression,"UTC",CronSchedule.Misfire.COALESCE);
    }
    public String getName(){return NAME;}
    public void onApplicationEvent(ApplicationReadyEvent event){cron.register(definition);}
    public void executeScheduled(ScheduledInvocation invocation){
        var k=invocation.key();String tenant=invocation.tenantId(),key=k.businessKey();
        if(!scope.businessTenantId().equals(tenant)||!NAME.equals(k.handler())||!k.targetNode().isEmpty())throw new IllegalArgumentException("Invalid sample work scope");
        if(key.matches("[a-f0-9]{64}")){
            scope.requireAdmitted(tenant,k.clientId());samples.refreshPending(tenant,k.clientId(),key,decoder);return;
        }
        if(!definition.clientId().equals(k.clientId())||!(key.startsWith("cron:")||key.matches("page:[a-f0-9]{64}")))throw new IllegalArgumentException("Invalid discovery key");
        var page=samples.pending(tenant,scope.clientId(),key.startsWith("page:")?key.substring(5):"");
        long now=System.currentTimeMillis();
        for(var row:page){scope.requireAdmitted(tenant,row.client());schedules.signal(new ScheduleKey(row.client(),NAME,row.sampleId(),""),now);}
        if(page.size()==ConfirmedSampleProjection.PAGE_SIZE)schedules.signal(new ScheduleKey(k.clientId(),NAME,"page:"+page.getLast().sampleId(),""),now);
    }
}
