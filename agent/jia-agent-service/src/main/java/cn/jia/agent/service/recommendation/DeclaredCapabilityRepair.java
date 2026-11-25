package cn.jia.agent.service.recommendation;

import cn.jia.agent.service.TaskFeatureEncoder;
import cn.jia.core.security.FixedTenantAdmissionPolicy;
import cn.jia.task.scheduling.ScheduleKey;
import cn.jia.task.scheduling.ScheduleService;
import cn.jia.task.scheduling.ScheduledInvocation;
import cn.jia.task.scheduling.ScheduledJobHandler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Register only a local DB hint after the business commit. GET/encoding/write are all outside SQL locks. */
@Service
@ConditionalOnProperty(name="jia.agent.capability-index.enabled",havingValue="true")
public class DeclaredCapabilityRepair implements DeclaredCapabilityFacts.Changes,ScheduledJobHandler {
    static final String NAME="agent.capability-declared";
    private final ScheduleService schedules;
    private final FixedTenantAdmissionPolicy scope;
    private final DeclaredCapabilityFacts facts;
    private final TaskFeatureEncoder encoder;
    private final CapabilityIndexWriter writer;
    DeclaredCapabilityRepair(ScheduleService schedules,FixedTenantAdmissionPolicy scope,DeclaredCapabilityFacts facts,TaskFeatureEncoder encoder,CapabilityIndexWriter writer){
        this.schedules=schedules;this.scope=scope;this.facts=facts;this.encoder=encoder;this.writer=writer;
    }
    public String getName(){return NAME;}
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void changed(String tenant,String client,long runtimeId){
        scope.requireAdmitted(tenant,client);
        schedules.signal(new ScheduleKey(client,NAME,Long.toString(runtimeId),""),System.currentTimeMillis());
    }
    public void executeScheduled(ScheduledInvocation invocation) throws Exception {
        var key=invocation.key();scope.requireAdmitted(invocation.tenantId(),key.clientId());
        if(!NAME.equals(key.handler())||!"".equals(key.targetNode())||TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Invalid capability repair context");
        var current=facts.declaredCapabilities(invocation.tenantId(),key.clientId(),Long.parseLong(key.businessKey()));
        if(current.isEmpty())return;
        var s=current.get();var docs=CapabilityDocuments.declared(s.tenant(),s.client(),s.owner(),s.agent(),s.abilities(),encoder.identity());
        writer.repair(docs,encoder);
    }
}
