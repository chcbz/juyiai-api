package cn.jia.economy.task;

import cn.jia.economy.pricing.TaskEstimateFeatures;
import java.util.Objects;

/** Internal benchmark enrollment, not permission to dispatch or spend. The caller owns the new benchmark task root
 * and must enroll before admitting any execution. No browser endpoint, inference or synthetic fixed quote. */
public interface TaskBenchmarkAdmission {
    /** Optional, operator-approved before execution; never inferred from text or output files. */
    record GroupInput(String operation,cn.jia.economy.pricing.GroupMeanPricing.Configuration configuration) {
        public GroupInput { if(configuration==null||!configuration.supportsImage(operation))throw new IllegalArgumentException("Invalid benchmark cost configuration"); }
    }
    record Manifest(String schema,TaskBillingService.Task task,String caseId,String agentId,String approvalRef,
            String completionCriterion,TaskBillingService.MeasuredGroup execution,TaskEstimateFeatures features,
            GroupInput groupInput){
        public Manifest(String schema,TaskBillingService.Task task,String caseId,String agentId,String approvalRef,String completionCriterion,TaskBillingService.MeasuredGroup execution,TaskEstimateFeatures features){this(schema,task,caseId,agentId,approvalRef,completionCriterion,execution,features,null);}
        public Manifest{if(!"benchmark-case-v1".equals(schema))throw new IllegalArgumentException("Unknown benchmark case schema");
            for(String v:java.util.List.of(caseId,agentId,approvalRef,completionCriterion))if(v.isBlank())throw new IllegalArgumentException("Incomplete case");
            Objects.requireNonNull(task);Objects.requireNonNull(execution);Objects.requireNonNull(features);}
        @Override public String toString(){return "Manifest[private]";}
    }
    final class NotAdmitted extends RuntimeException{}
    String enroll(TaskBillingService.Task task,String caseId,String expectedManifestSha,String agentId,TaskBillingService.MeasuredGroup actualGroup,TaskEstimateFeatures beforeDispatch);
    /** Imported by an authorized operator before execution; never written by the application.
     * Provider costs are sponsored, not a zero-WEN quote or an authorization to dispatch. */
    record SponsorshipAuthority(String schema, TaskBillingService.Task task, String caseId,
            String manifestSha256, String agentId, String actionId, String assignmentSha256,
            String initialIntentSha256, String approvalRef) {
        public SponsorshipAuthority {
            if (!"benchmark-sponsorship-v1".equals(schema)) throw new IllegalArgumentException("Unknown sponsorship schema");
            Objects.requireNonNull(task);
            for (String value : java.util.List.of(caseId, agentId, actionId, approvalRef))
                if (value.isBlank()) throw new IllegalArgumentException("Incomplete sponsorship binding");
            for (String hash : java.util.List.of(manifestSha256, assignmentSha256, initialIntentSha256))
                if (!hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid sponsorship commitment");
        }
        @Override public String toString() { return "SponsorshipAuthority[private]"; }
    }
    enum SponsorshipState { APPROVED, ACTIVE, ACCEPTED, CANCELLED }
    record Sponsorship(String authoritySha256, SponsorshipAuthority authority, SponsorshipState state) {
        @Override public String toString() { return "Sponsorship[private," + state + "]"; }
    }
    /** Caller holds the authorized new task root and validates its actual assignment and initial intent.
     * Enrollment and activation join that transaction. Does not emit a grant or start work. */
    Sponsorship sponsor(TaskBillingService.Task task, String caseId, String manifestSha256,
            String agentId, TaskBillingService.MeasuredGroup group, TaskEstimateFeatures features,
            String actionId, String assignmentSha256, String initialIntentSha256);
    /** Read original active/terminal funding under the caller's task lock. APPROVED is not funding.
     * Withdrawal does not erase past spending. This read never authorizes new execution. */
    Sponsorship sponsorship(TaskBillingService.Task task);
    /** True only for an activated sponsor; false preserves ordinary billing for all other tasks.
     * Acceptance/cancellation is not verified usage or sample readiness. */
    boolean settleSponsorship(TaskBillingService.Task task, SponsorshipState terminal);
}
