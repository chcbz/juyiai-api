package cn.jia.economy.task;

import cn.jia.economy.pricing.TaskWorkload;
import cn.jia.economy.service.EconomyScope;
import java.util.Objects;

/** Internal economy boundary. Callers authorize the task and bind current requirement/material facts.
 * All calls require the caller's transaction and task root lock (including quote creation);
 * the business layer owns this lock so requirement/assignment changes and money commit together.
 * Mutations join that transaction; no Provider I/O, task-table reads or actual-cost input.
 */
public interface TaskBillingService {
    record Task(EconomyScope scope, String ownerJiacn, String taskId) {
        public Task { Objects.requireNonNull(scope); Objects.requireNonNull(ownerJiacn); Objects.requireNonNull(taskId); }
    }
    /** A new recommendation gets a new identity. Prices changing alone never invalidate a quote. */
    record Binding(long requirementRevision, String requirementSha256, String inputSnapshotDigest,
            String agentId, String recommendationId) {
        public Binding {
            if (requirementRevision < 1) throw new IllegalArgumentException("requirement revision must be positive");
            Objects.requireNonNull(requirementSha256); Objects.requireNonNull(inputSnapshotDigest);
            Objects.requireNonNull(agentId); Objects.requireNonNull(recommendationId);
        }
    }
    record Quote(String quoteId, long amountWen, String snapshotJson) { }
    enum State { HELD, CAPTURED, RELEASED, LEGACY_FREE }
    record Charge(String quoteId, long amountWen, State state, String reserveTransactionId,
            String terminalTransactionId) { }

    /** Immutable per recommendation+candidate binding. Replay returns stored prices without recalculating. */
    Quote quote(Task task, Binding binding, String payerUserId, TaskWorkload workload, String reportedModel);
    /** Only the authoritative candidate configuration; labels or the actual cost ledger are not grouping keys. */
    record MeasuredGroup(String executionConfigSha256, String routeVersion, String toolConfigSha256, String capturePolicyVersion) {
        public MeasuredGroup {
            for (String hash : java.util.List.of(executionConfigSha256, toolConfigSha256))
                if (!hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Missing configuration commitment");
            if (routeVersion.isBlank() || capturePolicyVersion.isBlank()) throw new IllegalArgumentException("Unknown group");
        }
    }
    /** Prepared outside the task lock. No pricing or encoding in the write transaction. */
    record MeasuredPreparation(cn.jia.economy.pricing.TaskEstimateFeatures features, MeasuredGroup group,
            String estimatorVersion, String calibrationVersion, String qualityStatus,
            cn.jia.economy.pricing.MeasuredNeighborEstimate.Estimate estimate) {
        public MeasuredPreparation {
            Objects.requireNonNull(features); Objects.requireNonNull(group);
            if (estimatorVersion.isBlank() || calibrationVersion.isBlank() || qualityStatus.isBlank() || estimate.contributions().isEmpty())
                throw new IllegalArgumentException("Missing measured quote evidence");
        }
    }
    /** Called once by the winning quote-batch writer, only after version revalidation. Never reserves funds. */
    Quote storeMeasured(Task task, Binding binding, String payerUserId, MeasuredPreparation prepared);
    /** Reserve exactly the selected quote, or replay the same reservation; never top up/reprice.
     * payerUserId comes from the caller's authenticated actor, never the request body or ownerJiacn.
     * Matching the quote payer is required even for zero-price quotes and reservation replays.
     */
    Charge reserve(Task task, String quoteId, Binding currentBinding, String payerUserId);
    /** Verify original assignment replay without re-reserving or consulting mutable requirements/inputs.
     * Checks the original quote, payer and recommendation; no mutable requirement reads.
     * A completed/released original remains readable, but never authorizes a new execution. */
    Charge reservation(Task task, String quoteId, String payerUserId, String recommendationId);
    /** Missing reservation is not free. Legacy-free requires an explicit cutover record. */
    Charge capture(Task task);
    /** Cancelling an unassigned task can have no reservation; an assigned task must have one. */
    Charge release(Task task, boolean reservationRequired);
}
