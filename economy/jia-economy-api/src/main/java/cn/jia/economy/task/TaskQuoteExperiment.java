package cn.jia.economy.task;

import cn.jia.economy.pricing.GroupMeanPricing;
/** Private, pre-execution experiment only. The scoped subject is not derived from the receipt. */
public interface TaskQuoteExperiment {
    record Subject(TaskBillingService.Task task,String version,long revision,String requirementSha,String inputSha){}
    record Approved(String operation,Integer deliverables,int outputContract,GroupMeanPricing.Policy policy){}
    Approved read(Subject subject,String sha);
    boolean current(Subject subject,String sha);
}
