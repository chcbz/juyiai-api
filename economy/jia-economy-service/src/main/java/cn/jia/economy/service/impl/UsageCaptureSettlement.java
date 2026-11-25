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
        try{receipt=costs.record(supplement(work));}
        catch(UsageCostConflict conflict){return journal.finish(work,null,conflict.reason());}
        return journal.finish(work,receipt,null);
    }
    /** The response adapter supplies evidence, not a replacement for independently reconciled billing facts.
     * Use the EXACT immutable ledger predecessor, including on redelivery after a later reprice. */
    private UsageCostService.Observation supplement(UsageCaptureJournal.Work work){
        var source=work.observation();if(source.expectedRevision()==0)return source;
        var previous=costs.observationAt(source.binding(),source.expectedRevision()).orElseThrow(()->new UsageCostConflict(UsageCostConflict.Reason.REVISION));
        var usage=previous.normalizedUsage();var price=previous.price();var f=work.facts();
        if(f!=null){
            if(f.channel()!=null){
                if(usage!=null&&(!f.channel().equals(usage.channel())||!f.model().equals(usage.model())||!f.tier().equals(usage.tier())))throw contradictory();
                if(price!=null&&(!f.channel().equals(price.channel())||!f.model().equals(price.model())||!f.tier().equals(price.tier())))throw contradictory();
            }
            if(usage!=null&&f.quantities()!=null){
                if(!usage.normalizationBasis().equals(f.normalizationBasis()))throw contradictory();
                for(var category:cn.jia.economy.pricing.ModelTokenPrice.Category.values()){
                    if(!Long.toString(usage.tokens().getOrDefault(category,0L)).equals(f.quantities().getOrDefault(category.name(),"0")))throw contradictory();
                }
            }
        }
        return new UsageCostService.Observation(source.binding(),source.receiptId(),source.expectedRevision(),source.rawUsageJson(),usage,price,source.observedAt());
    }
    private static UsageCostConflict contradictory(){return new UsageCostConflict(UsageCostConflict.Reason.EVIDENCE_CONFLICT);}
}
