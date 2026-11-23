package cn.jia.economy.usage;

import cn.jia.economy.task.TaskBillingService.Task;

/** Business outcome and fixed quote are read-only. False = absent, already projected or changed since observation. */
public interface ConfirmedSampleProjection {
    boolean refresh(Task task,String sampleId,ConfirmedSampleEvidence.Decoder decoder);
}
