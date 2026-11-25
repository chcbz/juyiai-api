package cn.jia.economy.service.impl;

import cn.jia.economy.pricing.MeasuredNeighborEstimate;
import cn.jia.economy.pricing.TaskEstimateFeatures;
import cn.jia.economy.pricing.TaskNeighborSearch;
import cn.jia.economy.pricing.TaskReferencePricing;
import cn.jia.economy.task.TaskBillingService;
import cn.jia.economy.task.TaskCalibrationCatalog.Active;
import cn.jia.economy.task.TaskHistoryCatalog;
import cn.jia.economy.pricing.MeasuredTaskRepricing;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.*;
import org.springframework.stereotype.Service;

/** Internal estimation after calibration admission. Does not grant eligibility, authorize a user, commit a quote or change a balance. */
@Service
public class MeasuredHistoryEstimateEngine {
    static final class UncalibratedTariff extends RuntimeException {}
    public static final class StaleSample extends RuntimeException {}
    private final TaskHistoryCatalog history;private final TaskReferencePricing prices;
    private final ObservationRegistry observations;
    public MeasuredHistoryEstimateEngine(TaskHistoryCatalog history,TaskReferencePricing prices){this(history,prices,Optional.empty());}
    @org.springframework.beans.factory.annotation.Autowired
    public MeasuredHistoryEstimateEngine(TaskHistoryCatalog history,TaskReferencePricing prices,Optional<ObservationRegistry> registry){
        this.history=history;this.prices=prices;this.observations=registry.orElse(ObservationRegistry.NOOP);
    }
    // Reject real transactions without opening an empty Spring synchronization scope: history reads
    // must release their connection before pricing opens its own repeatable-read transaction.
    public MeasuredNeighborEstimate.Estimate estimate(TaskBillingService.Task task,TaskBillingService.MeasuredGroup execution,
            TaskEstimateFeatures query,Active active,Map<String,Map<String,TaskReferencePricing.Route>> routes){
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new org.springframework.transaction.IllegalTransactionStateException("Budget estimation must be outside a transaction");
        var span=Observation.start("cyf.task.quote.estimate",observations);String outcome="error";
        try{var result=calculate(task,execution,query,active,routes);outcome="ok";return result;}
        catch(StaleSample stale){outcome="stale";throw stale;}
        catch(UncalibratedTariff|MeasuredTaskRepricing.Unavailable|cn.jia.economy.pricing.TaskPricingService.Unavailable unavailable){outcome="unavailable";throw unavailable;}
        finally{span.lowCardinalityKeyValue("outcome",outcome).stop();}
    }
    private MeasuredNeighborEstimate.Estimate calculate(TaskBillingService.Task task,TaskBillingService.MeasuredGroup execution,
            TaskEstimateFeatures query,Active active,Map<String,Map<String,TaskReferencePricing.Route>> routes){
        var group=TaskHistoryCatalog.Group.of(execution,query);var search=new TaskNeighborSearch(query,active.parameters());String after="";
        while(true){var page=history.candidatePage(task,group,active.pin(),after);
            for(var row:page){var source=row.source();search.accept(source.sampleId(),source.revision(),row.features());after=source.sampleId();}
            if(page.size()<TaskHistoryCatalog.PAGE)break;
        }
        var neighbors=new ArrayList<MeasuredNeighborEstimate.Neighbor>();
        for(var hit:search.selected()){
            var facts=history.candidateQuantities(task,group,active.pin(),new TaskHistoryCatalog.Revision(hit.id(),hit.revision()));
            if(facts==null)throw new StaleSample(); // never silently substitute another neighbor after selection
            neighbors.add(new MeasuredNeighborEstimate.Neighbor(hit.id(),Long.toString(hit.revision()),hit.weight(),facts));
        }
        var book=prices.freeze(task.scope(),execution.routeVersion(),routes,routes.keySet());
        if(!TaskTariffBook.sha(TaskTariffBook.encode(book)).equals(active.referenceBookSha256()))throw new UncalibratedTariff();
        return MeasuredNeighborEstimate.aggregate(book,neighbors);
    }
}
