package cn.jia.economy.task;

import cn.jia.economy.pricing.TaskEstimateFeatures;
import java.util.Objects;

/** Internal benchmark enrollment, not permission to dispatch or spend. The caller owns the new benchmark task root
 * and must enroll before admitting any execution. No browser endpoint, inference or synthetic fixed quote. */
public interface TaskBenchmarkAdmission {
    record Manifest(String schema,TaskBillingService.Task task,String caseId,String agentId,String approvalRef,
            String completionCriterion,TaskBillingService.MeasuredGroup execution,TaskEstimateFeatures features){
        public Manifest{if(!"benchmark-case-v1".equals(schema))throw new IllegalArgumentException("Unknown benchmark case schema");
            for(String v:java.util.List.of(caseId,agentId,approvalRef,completionCriterion))if(v.isBlank())throw new IllegalArgumentException("Incomplete case");
            Objects.requireNonNull(task);Objects.requireNonNull(execution);Objects.requireNonNull(features);}
        @Override public String toString(){return "Manifest[private]";}
    }
    final class NotAdmitted extends RuntimeException{}
    String enroll(TaskBillingService.Task task,String caseId,String expectedManifestSha,String agentId,TaskBillingService.MeasuredGroup actualGroup,TaskEstimateFeatures beforeDispatch);
}
