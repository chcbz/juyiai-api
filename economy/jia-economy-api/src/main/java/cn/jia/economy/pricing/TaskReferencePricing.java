package cn.jia.economy.pricing;

import cn.jia.economy.service.EconomyScope;
import java.util.Map;
import java.util.Set;

/** Exact routes/components are selected by the server's compatible execution contract, never by a quote client. */
public interface TaskReferencePricing {
    sealed interface Route permits TokenRoute,UnitRoute,ZeroRoute {}
    record TokenRoute(String channel,String model,String tier,String normalizationBasis) implements Route {}
    record UnitRoute(TaskQuoteCalculator.ToolKey tool) implements Route {}
    record ZeroRoute(String contractVersion) implements Route {}
    /** One read-only catalog snapshot for all selected samples; no locks on task/wallet, provider calls or historical actual charges. */
    MeasuredTaskRepricing.Reference freeze(EconomyScope scope,String contractVersion,Map<String,Map<String,Route>> contract,Set<String> usedKinds);
}
