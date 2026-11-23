package cn.jia.economy.service.impl;

import cn.jia.economy.usage.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** No outer transaction: never hold original Runtime or capture locks while recording usage. */
@Service
public class UsageCaptureSettlement implements UsageCaptureJournal.Settlement {
    private final UsageCostService costs;
    private final UsageCaptureJournal journal;
    public UsageCaptureSettlement(UsageCostService costs,UsageCaptureJournal journal){this.costs=costs;this.journal=journal;}
    @Transactional(propagation=Propagation.NEVER)
    public UsageCaptureJournal.Result apply(UsageCaptureJournal.Work work){
        UsageCostService.Receipt receipt;
        try{receipt=costs.record(work.observation());}
        catch(UsageCostConflict conflict){return journal.finish(work,null,conflict.reason());}
        return journal.finish(work,receipt,null);
    }
}
