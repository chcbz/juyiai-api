package cn.jia.economy.usage;

import cn.jia.economy.task.TaskBillingService.Task;

/** Business outcome and fixed quote are read-only. False = absent, already projected or changed since observation. */
public interface ConfirmedSampleProjection {
    int PAGE_SIZE=64;
    record Pending(String client,String sampleId) {}
    java.util.List<Pending> pending(String tenant,String client,String after);
    boolean refreshPending(String tenant,String client,String sampleId,ConfirmedSampleEvidence.Decoder decoder);
    boolean refresh(Task task,String sampleId,ConfirmedSampleEvidence.Decoder decoder);
}
