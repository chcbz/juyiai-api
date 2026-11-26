package cn.jia.economy.service.impl;

import cn.jia.economy.mapper.GroupBaselineMapper;
import cn.jia.economy.pricing.GroupMeanPricing;
import cn.jia.economy.service.EconomyScope;
import cn.jia.economy.task.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GroupBudgetQuotesService implements GroupBudgetQuotes {
    private final GroupBaselineMapper mapper;
    public GroupBudgetQuotesService(GroupBaselineMapper mapper) { this.mapper = mapper; }
    private static final int PAGE=128;
    @Override public Map<Key,Decision> prepare(EconomyScope domain,Set<Key> keys) {
        var sorted=keys.stream().map(Key::groupSha).sorted().toList();
        var decisions=new HashMap<Key,Decision>();
        keys.forEach(k -> decisions.put(k,new Fixed(TaskQuoteBatches.Reason.NO_BASELINE)));
        for(int i=0;i<sorted.size();i+=PAGE) for(var r:mapper.read(domain,sorted.subList(i,Math.min(i+PAGE,sorted.size())))) {
            if(!"ACTIVE".equals(r.status())) continue;
            if(r.revision()==null || !r.revision().equals(r.activeRevision()) || r.revision()!=r.generation() ||
               !Objects.equals(r.revisionEpoch(),r.bookEpoch()) || r.tariffSha()==null || !r.tariffSha().equals(r.revisionTariff()) ||
               r.policySha()==null || !r.policySha().equals(r.revisionPolicy()) || r.sampleCount()==null || r.sampleCount()<=0 || r.sumCostYuan()==null)
                throw new IllegalStateException("Invalid published cost baseline");
            var k=new Key(r.groupSha()); var pin=new Pin(k,r.bookEpoch(),r.generation(),r.revision(),r.tariffSha(),r.policySha(),r.sampleCount(),r.sumCostYuan());
            decisions.put(k,new Mean(pin,GroupMeanPricing.amountWen(pin.sumYuan(),pin.n())));
        }
        return Map.copyOf(decisions);
    }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public void lockSelected(EconomyScope domain,Collection<Pin> pins) {
        var unique=new TreeMap<String,Pin>();
        for(var p:pins) {var old=unique.putIfAbsent(p.key().groupSha(),p); if(old!=null && !old.equals(p))throw new TaskBudgetEstimator.Stale();}
        var keys=List.copyOf(unique.keySet());
        for(int i=0;i<keys.size();i+=PAGE) {
            var page=keys.subList(i,Math.min(i+PAGE,keys.size())); List<GroupBaselineMapper.Head> rows;
            try { rows=mapper.lock(domain,page); }
            catch(org.springframework.dao.DataAccessException failure){
                // Do not queue behind a background reprice while holding the task root. Other DB errors remain unavailable.
                for(Throwable cause=failure;cause!=null;cause=cause.getCause())if(cause instanceof java.sql.SQLException sql && sql.getErrorCode()==3572)throw new TaskBudgetEstimator.Stale();
                throw failure;
            }
            if(rows.size()!=page.size())throw new TaskBudgetEstimator.Stale();
            for(var h:rows){var p=unique.get(h.groupSha());
                if(!"ACTIVE".equals(h.status()) || h.bookEpoch()!=p.bookEpoch() || h.generation()!=p.generation() ||
                   !Objects.equals(h.activeRevision(),p.revision()) || !Objects.equals(h.tariffSha(),p.tariffSha()) ||
                   !Objects.equals(h.policySha(),p.policySha())) throw new TaskBudgetEstimator.Stale();}
        }
    }
}
