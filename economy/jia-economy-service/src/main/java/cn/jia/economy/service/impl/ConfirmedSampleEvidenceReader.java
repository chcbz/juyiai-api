package cn.jia.economy.service.impl;

import cn.jia.economy.mapper.ConfirmedSampleMapper;
import cn.jia.economy.task.TaskBillingService.Task;
import cn.jia.economy.usage.ConfirmedSampleEvidence;
import cn.jia.economy.usage.TaskMembership;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static cn.jia.economy.usage.ConfirmedSampleEvidence.Verdict.*;

/** Background read, one consistent MVCC snapshot, bounded keyset pages, no task-root lock or provider I/O. */
@Service
public class ConfirmedSampleEvidenceReader implements ConfirmedSampleEvidence {
    private final ConfirmedSampleMapper db;
    public ConfirmedSampleEvidenceReader(ConfirmedSampleMapper db){this.db=db;}
    @Transactional(readOnly=true,propagation=Propagation.REQUIRES_NEW,isolation=Isolation.REPEATABLE_READ)
    public Assessment inspect(Task task,String id){
        var h=db.confirmed(task,id);if(h==null)return null;
        var verdict=ACTIVE;long count=0;
        if("CANCELLED".equals(h.lifecycle()))verdict=CANCELLED_OUTCOME;
        else if("ACCEPTED".equals(h.lifecycle())){
            if(!Boolean.TRUE.equals(h.verified()))verdict=MEMBERSHIP_PENDING;
            else if(!h.lifecycle().equals(h.outcome()))verdict=EXECUTION_SET_CONFLICT;
            else if(h.groupVersion()==null||h.executionConfig()==null||h.localRoute()==null||h.tools()==null||h.referenceRoute()==null||h.policy()==null)verdict=CONFIG_UNKNOWN;
            else {
                boolean broken=false,mixed=false,missing=false,unknown=false;long after=0;
                while(true){var page=db.members(task,id,h.fact(),h.agent(),after);
                    for(var m:page){after=m.revision();count++;broken|=m.attached()==null;
                        boolean complete="COMPLETE".equals(m.coverage());missing|=!complete;
                        if(m.groupVersion()==null){unknown|=complete;continue;}
                        mixed|=!h.groupVersion().equals(m.groupVersion())||!h.executionConfig().equals(m.executionConfig())
                            || !h.localRoute().equals(m.route())||!h.tools().equals(m.tools())||!h.policy().equals(m.policy());
                    }
                    if(page.size()<TaskMembership.PAGE)break;
                }
                // Reference tariff version belongs to pricing, not the Runtime provider namespace.
                verdict=broken||count!=h.memberCount()?EXECUTION_SET_CONFLICT:mixed?MIXED_EXECUTION_CONFIG:
                        unknown?CONFIG_UNKNOWN:missing||count==0?USAGE_INCOMPLETE:MATCHED;
            }
        }
        return new Assessment(id,h.evidenceRevision(),h.fact(),count,verdict);
    }
}
