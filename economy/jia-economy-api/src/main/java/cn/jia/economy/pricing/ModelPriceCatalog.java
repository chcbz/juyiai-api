package cn.jia.economy.pricing;

import java.util.Optional;
import cn.jia.economy.service.EconomyScope;

/** Immutable current manual-CNY prices. Callers retain the selected snapshot before invoking
 * a provider; missing routes remain pending, never a zero-price or default-model substitution.
 * Independent of task estimation and wallet admission.
 */
public interface ModelPriceCatalog {
    Optional<ModelTokenPrice> find(EconomyScope scope, String channel, String model, String tier);
    Optional<ModelTokenPrice> findVersion(EconomyScope scope, String channel, String model, String tier, String version);
}
