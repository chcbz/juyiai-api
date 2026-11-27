package cn.jia.economy.task;

import cn.jia.economy.pricing.CostPricingRoute;

import cn.jia.economy.pricing.TaskEstimateFeatures;
import cn.jia.economy.pricing.TaskReferencePricing;
import java.util.*;

/** Internal Agent/Economy boundary. Terms are from authorized execution configuration, not a client price request. */
public interface TaskBudgetEstimator {
    record Terms(TaskBillingService.MeasuredGroup execution,Map<String,Map<String,CostPricingRoute>> routes){public Terms{
        Objects.requireNonNull(execution);var copy=new HashMap<String,Map<String,CostPricingRoute>>();routes.forEach((k,v)->copy.put(k,Map.copyOf(v)));routes=Map.copyOf(copy);
    }}
    sealed interface Outcome permits Available,Unavailable{}
    record Available(TaskBillingService.MeasuredPreparation preparation,TaskCalibrationCatalog.Pin pin) implements Outcome{}
    record Unavailable(TaskQuoteBatches.Reason reason) implements Outcome{}
    final class Stale extends RuntimeException{}
    Outcome prepare(TaskBillingService.Task task,TaskEstimateFeatures features,Terms terms);
    /** Caller holds its task root and has already checked for the committed idempotency winner. */
    void lockSelected(TaskBillingService.Task task,List<Available> offers);
}
