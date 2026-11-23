package cn.jia.economy.service.impl;

import cn.jia.economy.pricing.MeasuredTaskRepricing;
import cn.jia.economy.pricing.TaskEstimateFeatures;
import cn.jia.economy.pricing.TaskNeighborSearch;
import cn.jia.economy.pricing.TaskPricingService;
import cn.jia.economy.task.TaskBillingService;
import cn.jia.economy.task.TaskBudgetEstimator;
import cn.jia.economy.task.TaskCalibrationCatalog;
import cn.jia.economy.task.TaskHistoryCatalog;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import static cn.jia.economy.task.TaskQuoteBatches.Reason.BILLING_ROUTE_MISSING;
import static cn.jia.economy.task.TaskQuoteBatches.Reason.NO_BASELINE;
import static cn.jia.economy.task.TaskQuoteBatches.Reason.OUT_OF_DISTRIBUTION;
import static cn.jia.economy.task.TaskQuoteBatches.Reason.PRICE_MISSING;
import static cn.jia.economy.task.TaskQuoteBatches.Reason.USAGE_INCOMPLETE;

@Service
public class TaskBudgetEstimatorService implements TaskBudgetEstimator {
    private final TaskCalibrationCatalog calibration;private final TaskHistoryCatalog history;private final MeasuredHistoryEstimateEngine engine;
    public TaskBudgetEstimatorService(TaskCalibrationCatalog calibration,TaskHistoryCatalog history,MeasuredHistoryEstimateEngine engine){
        this.calibration=calibration;this.history=history;this.engine=engine;}
    @Transactional(propagation=Propagation.NEVER)
    public Outcome prepare(TaskBillingService.Task task,TaskEstimateFeatures features,Terms terms){
        if(terms==null||terms.routes().isEmpty())return new Unavailable(BILLING_ROUTE_MISSING);
        var group=TaskHistoryCatalog.Group.of(terms.execution(),features);var active=calibration.resolve(task,group);
        if(active==null)return new Unavailable(NO_BASELINE);
        try{var estimate=engine.estimate(task,terms.execution(),features,active.parameters(),terms.routes());
            return new Available(new TaskBillingService.MeasuredPreparation(features,terms.execution(),TaskNeighborSearch.VERSION,active.pin().receiptSha256(),"CALIBRATED",estimate),active.pin());
        }catch(MeasuredHistoryEstimateEngine.StaleSample changed){throw new Stale();}
        catch(TaskPricingService.Unavailable missing){return switch(missing.reason()){
            case BILLING_ROUTE_MISSING -> new Unavailable(BILLING_ROUTE_MISSING);
            case MODEL_PRICE_MISSING,TOOL_PRICE_MISSING,TOKEN_PRICE_MISSING -> new Unavailable(PRICE_MISSING);
            default -> throw missing;};}
        catch(MeasuredTaskRepricing.Unavailable missing){return switch(missing.getMessage()){
            case "NO_BASELINE" -> new Unavailable(NO_BASELINE);
            case "OUT_OF_DISTRIBUTION" -> new Unavailable(OUT_OF_DISTRIBUTION);
            case "TOKEN_PRICE_MISSING" -> new Unavailable(PRICE_MISSING);
            case "BILLING_BASIS_MISMATCH","CHARGE_COMPONENT_MISMATCH" -> new Unavailable(BILLING_ROUTE_MISSING);
            case "OPERATION_EVIDENCE_MISSING","OPERATION_CONFLICT","CHARGE_COMPONENT_CONFLICT","OPERATION_QUANTITY_MISSING" -> new Unavailable(USAGE_INCOMPLETE);
            default -> throw missing;};}
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void lockSelected(TaskBillingService.Task task,List<Available> offers){
        var heads=new TreeMap<String,TaskCalibrationCatalog.Pin>();var rows=new TreeMap<String,Selected>();
        for(var offer:offers){var p=offer.preparation();var group=TaskHistoryCatalog.Group.of(p.group(),p.features());var pin=offer.pin();
            if(!group.digest().equals(pin.group())||!p.calibrationVersion().equals(pin.receiptSha256()))throw new IllegalStateException("Unbound estimate");
            var prev=heads.putIfAbsent(pin.group(),pin);if(prev!=null&&!prev.equals(pin))throw new Stale();
            for(var line:p.estimate().contributions()){var item=new Selected(group,new TaskHistoryCatalog.Revision(line.sampleId(),Long.parseLong(line.revision())));
                var old=rows.putIfAbsent(line.sampleId(),item);if(old!=null&&!old.equals(item))throw new Stale();}}
        for(var pin:heads.values())if(!calibration.lockCurrent(task,pin))throw new Stale();
        for(var sel:rows.values())if(!history.lockCurrent(task,sel.group(),sel.revision()))throw new Stale();
    }
    private record Selected(TaskHistoryCatalog.Group group,TaskHistoryCatalog.Revision revision){}
}
