package cn.jia.economy.task;

import cn.jia.economy.pricing.TaskEstimateCalibration;
import cn.jia.economy.pricing.TaskEstimateEvaluation;

/** Internal offline input, not an export API or an authorization grant. A trusted collector
 * pins the owner, original task, family, split and sample revision before observation. */
public interface TaskCalibrationObservations {
    final class NotEligible extends RuntimeException { }
    /** Private UTF-8 bytes for an admitted original task only. Keep the expected digest separately in the frozen roster.
     * Integrity and reproducing a historical observation are not authorization or present-day eligibility. */
    record Frozen(String sha256,String json){@Override public String toString(){return "Frozen[private]";}}
    TaskEstimateCalibration.Sample replay(TaskBillingService.Task original,TaskHistoryCatalog.Group group,
            TaskHistoryCatalog.Revision selected,String family,TaskEstimateEvaluation.Split partition,String expectedSha256,String json);
    Frozen freezeConfirmed(TaskBillingService.Task original,TaskHistoryCatalog.Group group,
            TaskHistoryCatalog.Revision selected,String family,TaskEstimateEvaluation.Split partition);
}
