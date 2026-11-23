package cn.jia.agent.service.usage;

import cn.jia.core.security.FixedTenantAdmissionPolicy;
import cn.jia.economy.usage.UsageCaptureProjection;
import cn.jia.task.scheduling.ScheduleKey;
import cn.jia.task.scheduling.ScheduleService;
import cn.jia.task.scheduling.ScheduledInvocation;
import cn.jia.task.scheduling.ScheduledJobHandler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** One coalescing job per capture. No lookups against a current lease, model or price book. */
@Service
public final class NativeCaptureProjection implements ScheduledJobHandler {
    private static final String NAME="native.usage-coverage";
    private final UsageCaptureProjection store;private final ObjectProvider<ScheduleService> schedules;private final FixedTenantAdmissionPolicy scope;
    public NativeCaptureProjection(UsageCaptureProjection store,ObjectProvider<ScheduleService> schedules,FixedTenantAdmissionPolicy scope){this.store=store;this.schedules=schedules;this.scope=scope;}
    public String getName(){return NAME;}
    @EventListener public void changed(UsageCaptureProjection.Key k){
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Missing evidence transaction");
        scope.requireAdmitted(k.tenant(),k.client());schedules.getObject().signal(new ScheduleKey(k.client(),NAME,Long.toString(k.pk()),""),System.currentTimeMillis()+5000);
    }
    public void executeScheduled(ScheduledInvocation invocation){
        var key=invocation.key();scope.requireAdmitted(invocation.tenantId(),key.clientId());
        if(!NAME.equals(key.handler())||!key.targetNode().isEmpty()||TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Invalid projection context");
        var k=new UsageCaptureProjection.Key(invocation.tenantId(),key.clientId(),Long.parseLong(key.businessKey()));
        var snap=store.pending(k);if(snap==null)return;
        // No FINAL yet (including a durable but unassociated revision): one exact-key lookup, no page scan.
        // The same CAS protects this negative observation if a close arrives concurrently.
        String closeId=NativeCaptureEvidence.digest(java.util.List.of("USAGE_RECORD_V2",snap.captureId(),"CAPTURE_FINAL",java.util.List.of()));
        if(!store.hasAssociated(k,closeId)){store.publish(k,snap.inputRevision(),UsageCaptureProjection.Coverage.WAITING_EVIDENCE);return;}
        // No server-attested production execution profile yet. A client PLAN cannot elect a verified policy.
        var fold=new NativeCaptureCoverage(snap.captureId(),null);String after="";
        while(true){var page=store.page(k,after);if(page.isEmpty())break;for(var row:page)fold.accept(row);after=page.getLast().recordId();}
        // A newer input has its own transactional signal; never overwrite it or replay a provider call.
        store.publish(k,snap.inputRevision(),fold.finish().state());
    }
}
