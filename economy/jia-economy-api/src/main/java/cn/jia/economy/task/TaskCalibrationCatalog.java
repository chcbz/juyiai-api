package cn.jia.economy.task;

import cn.jia.economy.pricing.TaskEstimateCalibration;
import cn.jia.economy.pricing.TaskNeighborSearch;

/** No HTTP writer. An offline publisher owns source admission, independently retained evidence and monotonic head generations. */
public interface TaskCalibrationCatalog {
    record Receipt(String estimatorVersion,String datasetSha256,String splitSha256,String referenceBookSha256,String policySha256,
                   TaskEstimateCalibration.Gate gate,TaskEstimateCalibration.Report report){}
    record Pin(String group,long generation,String receiptSha256){}
    record Active(Pin pin,TaskNeighborSearch.Parameters parameters){}
    /** Null=no active supported and qualified calibration for that owner and exact group. Storage failures are not absence. */
    Active resolve(TaskBillingService.Task task,TaskHistoryCatalog.Group group);
    /** Recheck only the head under the existing quote transaction. No deserialization or estimation under the lock. */
    boolean lockCurrent(TaskBillingService.Task task,Pin pin);
}
