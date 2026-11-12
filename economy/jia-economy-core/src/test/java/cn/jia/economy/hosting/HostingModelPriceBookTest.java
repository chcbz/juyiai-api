package cn.jia.economy.hosting;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class HostingModelPriceBookTest {
    @Test void allEightPricesUseTheSameCostProfileAndTenWenRounding() {
        var expected = Map.of("gpt-6-astra", 5000L, "gpt-6-sol", 1000L,
                "gpt-6.1-sol", 1000L, "gpt-6.1-terra", 500L, "gpt-6-luna", 50L,
                "gpt-5.6-sol", 2000L, "gpt-5.6-terra", 1110L, "gpt-5.6-luna", 110L);
        assertEquals(8, HostingModelPriceBook.prices().size());
        for (var price : HostingModelPriceBook.prices()) {
            assertEquals(expected.get(price.modelId()).longValue(), price.amountWen(), price.modelId());
            assertSame(price, HostingModelPriceBook.forModel(price.modelId()));
            assertSame(price, HostingModelPriceBook.forPlan(price.planId()));
        }
        assertEquals(2592000, HostingModelPriceBook.PERIOD_SECONDS);
        assertThrows(UnsupportedOperationException.class, () -> HostingModelPriceBook.prices().clear());
    }

    @Test void unknownModelsAndLookalikePlansHaveNoPriceRatherThanFreeOrDefaultPrice() {
        assertNull(HostingModelPriceBook.forModel(null));
        assertNull(HostingModelPriceBook.forModel("GPT-6.1-TERRA"));
        assertNull(HostingModelPriceBook.forModel("gpt-6.1-terra "));
        assertNull(HostingModelPriceBook.forPlan(null));
        assertNull(HostingModelPriceBook.forPlan("agent-hosting-model-unknown"));
        assertNull(HostingModelPriceBook.forPlan("agent-self-hosted"));
        assertNull(HostingModelPriceBook.forPlan("agent-hosting-preview"));
    }
}
