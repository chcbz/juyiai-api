package cn.jia.economy.service.impl;

import cn.jia.economy.mapper.ConfirmedSampleMapper;
import cn.jia.economy.task.TaskBillingService.Task;
import cn.jia.economy.usage.ConfirmedSampleEvidence;
import cn.jia.economy.usage.ConfirmedSampleProjection;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ConfirmedSampleProjectionService implements ConfirmedSampleProjection {
    private final ConfirmedSampleEvidence evidence;private final ConfirmedSampleMapper db;private final ObjectMapper json;private final TransactionTemplate tx;
    public ConfirmedSampleProjectionService(ConfirmedSampleEvidence evidence,ConfirmedSampleMapper db,ObjectMapper json,PlatformTransactionManager manager){
        this.evidence=evidence;this.db=db;this.json=json;this.tx=new TransactionTemplate(manager);
    }
    public java.util.List<Pending> pending(String tenant,String client,String after){return db.pending(tenant,client,after,PAGE_SIZE);}
    @Transactional(propagation=Propagation.NEVER)
    public boolean refreshPending(String tenant,String client,String id,ConfirmedSampleEvidence.Decoder decoder){
        var row=db.target(tenant,client,id);if(row==null)return false;
        return refresh(new Task(new cn.jia.economy.service.EconomyScope(tenant,client),row.owner(),row.taskId()),id,decoder);
    }
    @Transactional(propagation=Propagation.NEVER)
    public boolean refresh(Task task,String id,ConfirmedSampleEvidence.Decoder decoder){
        var measured=evidence.measure(task,id,decoder);if(measured==null)return false;
        var a=measured.assessment();String state=switch(a.verdict()){
            case MATCHED -> "READY";
            case CLOSED_USAGE_INCOMPLETE, CANCELLED_OUTCOME, EXECUTION_SET_CONFLICT, CONFIG_UNKNOWN, MIXED_EXECUTION_CONFIG -> "EXCLUDED";
            case ACTIVE, MEMBERSHIP_PENDING, USAGE_INCOMPLETE -> "WAITING_EVIDENCE";
        };
        if("READY".equals(state) && (a.members()==0 || measured.operations().isEmpty()))throw new IllegalStateException("Missing whole-task measurements");
        // No copied response bodies, vector, mutable current price or wallet observation in the revision.
        var refs=json.writeValueAsString(measured.operations().stream().map(o->ProjectedTaskOperation.from(o,json)).toList());
        return Boolean.TRUE.equals(tx.execute(st->{if(db.project(task,id,a.evidenceRevision(),state)!=1)return false;
            db.append(id,a.evidenceRevision(),a.terminalFactId(),state,
                    a.verdict()==ConfirmedSampleEvidence.Verdict.CLOSED_USAGE_INCOMPLETE?"USAGE_INCOMPLETE":a.verdict().name(),a.members(),refs);return true;}));
    }
}
