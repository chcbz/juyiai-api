package cn.jia.economy.service.impl;

import cn.jia.economy.pricing.MeasuredNeighborEstimate;
import cn.jia.economy.pricing.TaskEstimateFeatures;
import cn.jia.economy.pricing.TaskNeighborSearch;
import cn.jia.economy.pricing.TaskReferencePricing;
import cn.jia.economy.task.TaskBillingService;
import cn.jia.economy.task.TaskHistoryCatalog;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Internal estimation after calibration admission. Does not grant eligibility, authorize a user, commit a quote or change a balance. */
@Service
public class MeasuredHistoryEstimateEngine {
    public static final class StaleSample extends RuntimeException {}
    private final TaskHistoryCatalog history;private final TaskReferencePricing prices;
    public MeasuredHistoryEstimateEngine(TaskHistoryCatalog history,TaskReferencePricing prices){this.history=history;this.prices=prices;}
    @Transactional(propagation=Propagation.NEVER)
    public MeasuredNeighborEstimate.Estimate estimate(TaskBillingService.Task task,TaskBillingService.MeasuredGroup execution,
            TaskEstimateFeatures query,cn.jia.economy.task.TaskCalibrationCatalog.Pin pin,TaskNeighborSearch.Parameters params,Map<String,Map<String,TaskReferencePricing.Route>> routes){
        var group=TaskHistoryCatalog.Group.of(execution,query);var search=new TaskNeighborSearch(query,params);String after="";
        while(true){var page=history.candidatePage(task,group,pin,after);
            for(var row:page){var source=row.source();search.accept(source.sampleId(),source.revision(),row.features());after=source.sampleId();}
            if(page.size()<TaskHistoryCatalog.PAGE)break;
        }
        var neighbors=new ArrayList<MeasuredNeighborEstimate.Neighbor>();var kinds=new TreeSet<String>();
        for(var hit:search.selected()){
            var facts=history.candidateQuantities(task,group,pin,new TaskHistoryCatalog.Revision(hit.id(),hit.revision()));
            if(facts==null)throw new StaleSample(); // never silently substitute another neighbor after selection
            facts.forEach(f->kinds.add(f.kind()));neighbors.add(new MeasuredNeighborEstimate.Neighbor(hit.id(),Long.toString(hit.revision()),hit.weight(),facts));
        }
        return MeasuredNeighborEstimate.aggregate(prices.freeze(task.scope(),execution.routeVersion(),routes,kinds),neighbors);
    }
}
