package cn.jia.agent.service.recommendation;

import cn.jia.agent.service.SkillCatalogIndexHints;
import cn.jia.agent.service.TaskFeatureEncoder;
import cn.jia.agent.skill.SkillManagementService;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The authenticated live skill directory belongs to its WebSocket node, unlike declared abilities. */
@Service
@ConditionalOnProperty(name="jia.agent.capability-index.enabled",havingValue="true")
final class SkillCapabilityRepair implements ScheduledJobHandler,ApplicationListener<ApplicationReadyEvent> {
    private static final String NAME="agent.capability-skills";
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(SkillCapabilityRepair.class);
    private final FixedTenantAdmissionPolicy scope;
    private final SkillCatalogIndexHints hints;
    private final SkillManagementService skills;
    private final TaskFeatureEncoder encoder;
    private final CapabilityIndexWriter writer;
    private final ScheduleService schedules;
    private final CronScheduleService cron;
    private final CronSchedule sweep;
    private final TransactionTemplate afterCommit;
    SkillCapabilityRepair(FixedTenantAdmissionPolicy scope,SkillCatalogIndexHints hints,SkillManagementService skills,TaskFeatureEncoder encoder,
            CapabilityIndexWriter writer,ScheduleService schedules,CronScheduleService cron,PlatformTransactionManager manager,
            @Value("${cyf.task.scheduling.node-id}") String node,@Value("${jia.agent.capability-index.repair-cron:0 17 * * * *}") String expression){
        this.scope=scope;this.hints=hints;this.skills=skills;this.encoder=encoder;this.writer=writer;this.schedules=schedules;this.cron=cron;
        if(node.isBlank())throw new IllegalArgumentException("Skill capability repair requires its session node");
        sweep=new CronSchedule(scope.clientId()==null?"":scope.clientId(),NAME,node,expression,"UTC",CronSchedule.Misfire.COALESCE);
        afterCommit=new TransactionTemplate(manager);afterCommit.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public String getName(){return NAME;}
    public void onApplicationEvent(ApplicationReadyEvent event){cron.register(sweep);}
    @TransactionalEventListener(fallbackExecution=true)
    public void changed(SkillManagementService.CapabilityChanged hint){
        // Catalog registration has no ambient transaction; managed state events run only after commit.
        try{afterCommit.executeWithoutResult(st->signal(hint.tenant(),hint.client(),hint.agent()));}
        catch(RuntimeException unavailable){LOG.warn("capability_skill_notification_deferred type={}",unavailable.getClass().getSimpleName());}
    }
    private void signal(String tenant,String client,String agent){
        scope.requireAdmitted(tenant,client);
        schedules.signal(new ScheduleKey(client,NAME,"agent:"+agent,sweep.targetNode()),System.currentTimeMillis());
    }
    public void executeScheduled(ScheduledInvocation invocation) throws Exception {
        var key=invocation.key();String tenant=invocation.tenantId();
        if(!scope.businessTenantId().equals(tenant)||!NAME.equals(key.handler())||!sweep.targetNode().equals(key.targetNode())||TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalArgumentException("Invalid skill repair context");
        if(key.businessKey().startsWith("cron:")&&sweep.clientId().equals(key.clientId())){
            for(var hint:hints.local(tenant,scope.clientId()))signal(hint.tenant(),hint.client(),hint.agent());
            return;
        }
        scope.requireAdmitted(tenant,key.clientId());if(!key.businessKey().startsWith("agent:"))throw new IllegalArgumentException("Unknown skill repair key");
        String agent=key.businessKey().substring(6);var rows=skills.citableSkillPurposes(tenant,key.clientId(),agent);
        if(!rows.isEmpty())writer.repair(CapabilityDocuments.purposes(tenant,key.clientId(),agent,rows,encoder.identity()),encoder);
    }
}
