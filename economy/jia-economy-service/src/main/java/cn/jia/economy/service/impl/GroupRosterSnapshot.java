package cn.jia.economy.service.impl;

import cn.jia.economy.mapper.GroupBaselineMaintenanceMapper;
import cn.jia.economy.mapper.GroupBaselineMaintenanceMapper.SourcePin;
import cn.jia.economy.mapper.GroupBaselineMaintenanceMapper.Work;
import cn.jia.economy.task.TaskBudgetEstimator;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Offline only. Freeze the whole roster, not a subset whose proofs happen to be readable today. */
@Service
public class GroupRosterSnapshot {
    private final GroupBaselineMaintenanceMapper db;
    public GroupRosterSnapshot(GroupBaselineMaintenanceMapper db){this.db=db;}

    @Transactional(readOnly=true,propagation=Propagation.REQUIRES_NEW,isolation=Isolation.REPEATABLE_READ)
    public List<SourcePin> freeze(Work work,String claim){
        if(!Objects.equals(work.generation(),db.observedClaim(work,claim)))throw new TaskBudgetEstimator.Stale();
        var pins=new ArrayList<SourcePin>();
        String after="";
        while(true){
            var page=db.sourcePage(work,after);
            pins.addAll(page);
            if(page.size()<128)return List.copyOf(pins);
            after=page.getLast().sampleId();
        }
    }
}
