package cn.jia.economy.hosting;

import cn.jia.economy.service.EconomyScope;
import cn.jia.economy.service.EconomyPrincipal;

/** Internal reconciliation, never a caller-controlled HTTP charge. Period identity is the paid-through fact. */
public interface HostingRentRenewalService {
    /** Operator cutover only: caller verifies the persisted owner/installation/binding and explicit plan,
     * holding those exact roots in the current transaction. No Runtime ADOPTED or online proof. Registers free terms and first due work; never posts money.
     * Stable lease/intent IDs and cutoverAt come from the fixed migration input, not retry time. */
    String registerTransition(Transition command);
    record Transition(EconomyScope scope, EconomyPrincipal principal, String leaseId, String intentId,
                      String personaCode, String agentId, String bindingId, String planId, long planVersion,
                      long cutoverAt, String installationId, String manifestSha256) { }

    /** Reconcile due/locked leases in expiry/key order; return the next authoritative user deadline. */
    Long reconcileUser(EconomyScope scope, String userId);
    Result reconcile(EconomyScope scope, String leaseId, long expectedPaidThrough);
    /** Stop future payments in the caller's removal transaction; retain the instance's live slot
     * and paid period until the Agent module completes platform relationship settlement. No current-period refund. */
    Result stopRenewal(EconomyScope scope, EconomyPrincipal owner, String leaseId);
    /** Caller holds task and binding roots in an active transaction. Lock the financial lease
     * until that transaction ends; only an irreversible, expired relationship may force settlement. */
    boolean lockExpiredFinishing(EconomyScope scope, String leaseId, String bindingId, String agentId);
    /** Read-only original INITIAL intent for lifecycle discovery; does not authorize release or a new provision. */
    cn.jia.economy.entity.EconomyHostingProvisioningIntentEntity initialHandoverIntent(EconomyScope scope,String leaseId);
    /** Read the latest immutable completed handover for the retained public instance.
     * Caller holds its persona/runtime admission roots; a stop fact alone is insufficient. */
    boolean completedHandover(EconomyScope scope, String agentId, String installationId);
    /** Operator control import: caller holds installation/binding/runtime roots in its transaction.
     * Locks the latest lease without changing its period, owner or money. Caller checks the exact relationship. */
    cn.jia.economy.entity.EconomyHostingLeaseEntity lockLatestLease(EconomyScope scope, String agentId);
    /** Lock original financial roots after the caller has locked installation and binding. No external I/O. */
    HandoverSnapshot lockHandover(EconomyScope scope, String initialIntentId, String leaseId);
    record HandoverSnapshot(cn.jia.economy.entity.EconomyHostingProvisioningIntentEntity intent,
                           cn.jia.economy.entity.EconomyHostingLeaseEntity lease) { }
    /** Internal lifecycle owner calls only after exact original Runtime QUIESCED, in the binding transaction.
     * A lease status alone is never stop evidence. Idempotent for the original handover only. */
    void recordExecutorStopped(EconomyScope scope, EconomyPrincipal owner, String leaseId,
                               String bindingId, String agentId, String handoverId);
    /** Agent lifecycle owner calls in its original binding/revocation transaction after platform settlement.
     * Managed asset reuse additionally requires cleanup; self-hosted release never asserts local cleanup.
     * Releases the financial live slot, not local files; no refund or new payment. */
    Result completeHandover(EconomyScope scope, EconomyPrincipal owner, String leaseId, String bindingId, String agentId);
    record Result(String status, Long paidThrough, Long lockedAt, String transactionId) { }
}
