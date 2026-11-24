package cn.jia.economy.task;

import cn.jia.economy.pricing.TaskEstimateCalibration;
import cn.jia.economy.pricing.TaskEstimateEvaluation;

/** Internal offline input, not an export API or an authorization grant. A trusted collector
 * pins the owner, original task, family, split and sample revision before observation. */
public interface TaskCalibrationObservations {
    final class NotEligible extends RuntimeException { }
    TaskEstimateCalibration.Sample observeConfirmed(TaskBillingService.Task original,TaskHistoryCatalog.Group group,
            TaskHistoryCatalog.Revision selected,String family,TaskEstimateEvaluation.Split partition);
}
