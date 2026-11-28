package cn.jia.economy.service.impl;

import cn.jia.economy.mapper.GroupBaselineMaintenanceMapper;
import cn.jia.economy.mapper.GroupBaselineMaintenanceMapper.Work;
import cn.jia.economy.pricing.*;
import cn.jia.economy.service.EconomyScope;
import cn.jia.economy.task.TaskBudgetEstimator;
import cn.jia.economy.usage.*;
import java.util.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Each mapper CAS outside preparation is a short autocommit; only publication needs a multi-write transaction. */
@Service
public class SharedGroupMaintenanceService implements SharedGroupMaintenance {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(SharedGroupMaintenanceService.class);
    private static final int LEASE=90;
    private final GroupBaselineMaintenanceMapper db;private final GroupRosterSnapshot roster;private final GroupTariffReader tariffs;
    private final GroupReviewedUsage usage;private final GroupBaselinePublisher publisher;private final ObjectMapper json;
    public SharedGroupMaintenanceService(GroupBaselineMaintenanceMapper db,GroupRosterSnapshot roster,GroupTariffReader tariffs,GroupReviewedUsage usage,GroupBaselinePublisher publisher,ObjectMapper json){
        this.db=db;this.roster=roster;this.tariffs=tariffs;this.usage=usage;this.publisher=publisher;this.json=json;
    }
    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.NEVER)
    public void runDue(String tenant,String client,ConfirmedSampleEvidence.Decoder decoder){
        if(tenant==null||tenant.isBlank())throw new IllegalArgumentException("Missing maintenance scope");
        for(var w:db.dueFor(tenant,client))run(w,decoder);
    }
    void run(Work w,ConfirmedSampleEvidence.Decoder decoder){
        if(w.attempt()>=5){if(db.exhaustExpired(w)==1)LOG.warn("Shared pricing expired five attempts group={} generation={}",w.groupSha(),w.generation());return;}
        String claim=UUID.randomUUID().toString();if(db.claim(w,claim,LEASE)!=1)return;
        try{
            var bytes=db.study(w);
            if(GroupStudyPolicy.isScoped(bytes,json)){runScoped(w,claim,GroupStudyPolicy.readScoped(w,bytes),decoder);return;}
            var policy=GroupStudyPolicy.read(w,bytes,json);var pins=roster.freeze(w,claim);
            if(pins.size()<policy.policy().minSupport())throw new GroupBaselinePublisher.MoreInputRequired();
            var book=tariffs.freezeGroupBook(new EconomyScope(w.tenantId(),w.clientId()),w.bookEpoch(),policy.receiptSha(),policy.routes());
            var rows=new ArrayList<GroupBaselinePublisher.Eligible>();long renewed=System.nanoTime();
            for(var pin:pins){if(rows.size()%128==0||System.nanoTime()-renewed>=30_000_000_000L){if(db.renew(w,claim,LEASE)!=1)return;renewed=System.nanoTime();}
                rows.add(usage.read(w,pin,policy.policy(),decoder));}
            publisher.publish(publisher.calculate(w,claim,book,rows,policy.policy().minSupport(),policy.receiptSha()));
        }catch(TaskBudgetEstimator.Stale lost){/* New generation/claim owns the head. */}
        catch(GroupPublicationEnrollment.NotApproved|GroupBaselinePublisher.MoreInputRequired|MeasuredTaskRepricing.Unavailable|TaskPricingService.Unavailable missing){db.waitForInput(w,claim);}
        catch(RuntimeException fault){int attempt=w.attempt()+1;LOG.warn("Shared pricing maintenance fault group={} generation={} attempt={} type={}",w.groupSha(),w.generation(),attempt,fault.getClass().getSimpleName());
            if(attempt>=5)exhaust(w,claim);else db.retry(w,claim,Math.min(1<<attempt,60));}
    }
    private void runScoped(Work w,String claim,GroupStudyPolicy.ScopedSelected policy,ConfirmedSampleEvidence.Decoder decoder){
        var pins=roster.freeze(w,claim);var selected=policy.approved();
        if(pins.size()<selected.policy().minSupport())throw new GroupBaselinePublisher.MoreInputRequired();
        var book=tariffs.freezeScopedBook(new EconomyScope(w.tenantId(),w.clientId()),w.bookEpoch(),selected.approvalSha(),policy.routes());
        var rows=new ArrayList<GroupBaselinePublisher.ScopedEligible>();long renewed=System.nanoTime();
        for(var pin:pins){
            if(rows.size()%128==0||System.nanoTime()-renewed>=30_000_000_000L){if(db.renew(w,claim,LEASE)!=1)return;renewed=System.nanoTime();}
            rows.add(usage.readScoped(w,pin,policy,decoder));
        }
        publisher.publish(publisher.calculateScoped(w,claim,book,rows,selected.policy().minSupport(),selected.approvalSha()));
    }
    private void exhaust(Work w,String claim){if(db.waitForInput(w,claim)==1)LOG.warn("Shared pricing maintenance exhausted group={} generation={}",w.groupSha(),w.generation());}
}
