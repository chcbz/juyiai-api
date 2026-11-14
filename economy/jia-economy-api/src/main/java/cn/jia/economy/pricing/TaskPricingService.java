package cn.jia.economy.pricing;

import java.util.Map;
import cn.jia.economy.service.EconomyScope;

/** Server-side pricing only; no model/network calls, balance reads or wallet writes. */
public interface TaskPricingService {
    record PreparedQuote(TaskQuoteCalculator.Estimate estimate, ModelTokenPrice modelPrice,
            Map<TaskQuoteCalculator.ToolKey, TaskQuoteCalculator.ToolPrice> toolPrices,
            TaskWorkload workload) {
        public PreparedQuote { toolPrices = Map.copyOf(toolPrices); }
    }
    PreparedQuote prepare(EconomyScope scope, TaskWorkload workload, String reportedModel);

    /** Candidate-local failure, never a fallback to zero or another billing route. */
    final class Unavailable extends RuntimeException {
        public enum Reason { BILLING_ROUTE_MISSING, MODEL_PRICE_MISSING, TOOL_PRICE_MISSING, TOKEN_PRICE_MISSING, QUOTE_AMOUNT_OVERFLOW }
        private final Reason reason;
        public Unavailable(Reason reason) { super(reason.name()); this.reason = reason; }
        public Reason reason() { return reason; }
    }
}
