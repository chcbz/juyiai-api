package cn.jia.economy.hosting;

import java.util.List;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** Platform product prices, not provider token costs. Existing immutable leases are not repriced. */
public final class HostingModelPriceBook {
    public static final long VERSION = 1L;
    public static final long PERIOD_SECONDS = 30L * 24 * 60 * 60;
    public static final String PRICE_BOOK_VERSION = "hero-hosting-wen-v1";
    public static final String COST_REFERENCE_DATE = "2026-11-12";
    // OpenAI Standard / short-context / uncached USD per million tokens, not our procurement invoice.
    // Comparable reference workload: 4 input : 1 output. Anchor gpt-6.1-terra at 500 WEN, HALF_UP to 10 WEN.
    private static final BigDecimal ANCHOR_COST = new BigDecimal("9");
    private static final List<Price> PRICES = List.of(
            new Price("gpt-6-astra", "10", "50"),
            new Price("gpt-6-sol", "2", "10"),
            new Price("gpt-6.1-sol", "2", "10"),
            new Price("gpt-6.1-terra", "1", "5"),
            new Price("gpt-6-luna", "0.1", "0.5"),
            new Price("gpt-5.6-sol", "4", "20"),
            new Price("gpt-5.6-terra", "2", "12"),
            new Price("gpt-5.6-luna", "0.2", "1.2"));

    private HostingModelPriceBook() { }

    public static List<Price> prices() { return PRICES; }

    public static Price forModel(String modelId) {
        return PRICES.stream().filter(price -> price.modelId().equals(modelId)).findFirst().orElse(null);
    }

    /** Exact allowlist: a lookalike or unknown plan must never become a managed plan by prefix alone. */
    public static Price forPlan(String planId) {
        return PRICES.stream().filter(price -> price.planId().equals(planId)).findFirst().orElse(null);
    }

    public record Price(String modelId, String inputUsdPerMillion, String outputUsdPerMillion) {
        public long amountWen() {
            BigDecimal cost = new BigDecimal(inputUsdPerMillion).multiply(BigDecimal.valueOf(4))
                    .add(new BigDecimal(outputUsdPerMillion));
            return cost.multiply(BigDecimal.valueOf(500))
                    .divide(ANCHOR_COST.multiply(BigDecimal.TEN), 0, RoundingMode.HALF_UP)
                    .longValueExact() * 10;
        }
        public String planId() { return "agent-hosting-model-" + modelId; }
    }
}
