package cn.jia.economy.pricing;

import cn.jia.economy.service.EconomyScope;
import java.util.List;
import java.util.Map;

/** Internal approved-study discovery. No policy defaults, user approval writer or pricing permission. */
public interface ScopedGroupPolicies {
    record Selected(String approvalSha, GroupMeanPricing.ScopedPolicy policy, GroupMeanPricing.ScopeGroup group) {
        @Override public String toString(){return "ScopedGroupPolicy[private]";}
    }
    /** Missing/withdrawn selections are absent; corrupt original evidence must fail, never become 1 WEN. */
    Map<GroupMeanPricing.ScopeInput,Selected> read(EconomyScope scope,List<GroupMeanPricing.ScopeInput> inputs,boolean lock);
    /** Original authorized maintenance transaction only. Selects an EXISTING approved original;
     * never issues approval. null expectedApprovalSha means no existing selector. */
    Selected select(EconomyScope scope,GroupMeanPricing.ScopeInput input,String approvalSha,String expectedApprovalSha);
    final class Corrupt extends IllegalStateException {public Corrupt(){super("SCOPED_GROUP_POLICY_CORRUPT");}}
    final class Busy extends RuntimeException {public Busy(){super("SCOPED_GROUP_POLICY_BUSY");}}
    final class Conflict extends RuntimeException {public Conflict(){super("SCOPED_GROUP_POLICY_CONFLICT");}}
}
