package cn.jia.economy.pricing;

import cn.jia.economy.service.EconomyScope;
import java.util.Map;
import java.util.Set;

/** Exact routes/components are selected by the server's compatible execution contract, never by a quote client. */
public interface TaskReferencePricing {
    /** One read-only catalog snapshot for all selected samples; no locks on task/wallet, provider calls or historical actual charges. */
    MeasuredTaskRepricing.ResourceReference freezeScoped(EconomyScope scope,String contractVersion,Map<CostRouteReference,CostPricingRoute> contract);
    MeasuredTaskRepricing.Reference freeze(EconomyScope scope,String contractVersion,Map<String,Map<String,CostPricingRoute>> contract,Set<String> usedKinds);
}
