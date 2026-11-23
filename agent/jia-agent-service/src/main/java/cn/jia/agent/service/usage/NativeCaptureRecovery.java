package cn.jia.agent.service.usage;

import cn.jia.core.security.FixedTenantAdmissionPolicy;
import cn.jia.economy.usage.UsageCaptureJournal;
import cn.jia.task.scheduling.ScheduleKey;
import cn.jia.task.scheduling.ScheduleService;
import cn.jia.task.scheduling.ScheduledInvocation;
import cn.jia.task.scheduling.ScheduledJobHandler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Resume evidence association, never the execution or a model call. */
@Service
public final class NativeCaptureRecovery implements ScheduledJobHandler {
    private static final String NAME="native.usage-association";
    private final UsageCaptureJournal journal;
    private final UsageCaptureJournal.Settlement settlement;
    private final FixedTenantAdmissionPolicy scope;
    private final ObjectProvider<ScheduleService> schedules;
    private final TransactionTemplate tx;
    public NativeCaptureRecovery(UsageCaptureJournal journal,UsageCaptureJournal.Settlement settlement,FixedTenantAdmissionPolicy scope,ObjectProvider<ScheduleService> schedules,PlatformTransactionManager manager){
        this.journal=journal;this.settlement=settlement;this.scope=scope;this.schedules=schedules;this.tx=new TransactionTemplate(manager);
    }
    public String getName(){return NAME;}
    public void signal(UsageCaptureJournal.Binding binding,String receipt,long pk){
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Recovery registration requires the evidence transaction");
        scope.requireAdmitted(binding.tenantId(),binding.clientId());
        var scheduler=schedules.getIfAvailable();
        if(scheduler==null)throw new DataAccessResourceFailureException("Usage recovery scheduler unavailable");
        scheduler.signal(new ScheduleKey(binding.clientId(),NAME,pk+":"+receipt,""),System.currentTimeMillis()+5000);
    }
    public void executeScheduled(ScheduledInvocation invocation){
        var key=invocation.key();scope.requireAdmitted(invocation.tenantId(),key.clientId());
        if(!NAME.equals(key.handler())||!"".equals(key.targetNode())||TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Invalid recovery context");
        String id=key.businessKey();int colon=id.indexOf(':');long pk=Long.parseLong(id.substring(0,colon));String receipt=id.substring(colon+1);
        var r=tx.execute(s->journal.recover(invocation.tenantId(),key.clientId(),pk,receipt,NativeSourceLineage::resolve,NativeResponseOrigin::resolve));
        if(r.work()!=null)r=settlement.apply(r.work()); // outside the short preparation transaction
        if(r.recoveryPk()!=0){ // unresolved predecessor: bounded backoff, no global scan
            long delay=5000L<<Math.min(9L,Math.max(0L,invocation.revision()));
            schedules.getObject().signal(key,System.currentTimeMillis()+delay);
        }
    }
}
