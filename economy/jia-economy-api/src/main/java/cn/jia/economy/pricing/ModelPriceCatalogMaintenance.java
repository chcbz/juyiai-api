package cn.jia.economy.pricing;

import cn.jia.economy.service.EconomyScope;
import java.util.Map;
import java.util.Optional;

/** Internal maintenance port. Caller must supply the trusted scope and own the authorization transaction. */
public interface ModelPriceCatalogMaintenance {
    record Price(String channel,String model,String tier,String version,Map<String,Object> data,String disposition) { }
    Optional<Price> find(EconomyScope scope,String channel,String model,String tier);
    Price create(EconomyScope scope,Price candidate);
    final class Conflict extends RuntimeException {
        public Conflict(){super("MODEL_PRICE_CONFLICT");}
    }
}
