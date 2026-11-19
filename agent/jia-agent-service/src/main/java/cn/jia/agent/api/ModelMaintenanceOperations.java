package cn.jia.agent.api;

import cn.jia.agent.mapper.hosting.PersonaModelHostingStore;
import cn.jia.agent.position.PositionSkillException;
import cn.jia.agent.service.PositionAuthority.Scope;
import cn.jia.economy.pricing.ModelPriceCatalogMaintenance;
import cn.jia.economy.service.EconomyScope;
import org.springframework.stereotype.Service;
import java.util.Map;
import java.util.function.Function;
import static cn.jia.agent.position.PositionSkillException.require;

/** Model operations on an authorized scope; independent of conversation, tool names and wire format. */
@Service
final class ModelMaintenanceOperations {
    private static final String CHANNEL="public-api-reference", TIER="standard";
    private final PersonaModelHostingStore bindings;
    private final ModelPriceCatalogMaintenance prices;
    private final BaseLlmModelPriceSource source;

    ModelMaintenanceOperations(PersonaModelHostingStore bindings, ModelPriceCatalogMaintenance prices, BaseLlmModelPriceSource source) {
        this.bindings=bindings; this.prices=prices; this.source=source;
    }

    Map<String,Object> readPrice(Scope scope,String model) {
        return prices.find(economyScope(scope),CHANNEL,model,TIER).map(ModelMaintenanceOperations::priceReceipt)
                .orElseGet(()->Map.of("model",model,"configured",false));
    }

    // Fetch before the write transaction; the caller rechecks authority before applying the quote.
    Function<Scope,Map<String,Object>> prepareAddPrice(String model) {
        var quote=source.read(model);
        var price=new ModelPriceCatalogMaintenance.Price(CHANNEL,quote.model(),TIER,quote.version(),quote.priceData(),null);
        return scope->{
            try {return priceReceipt(prices.create(economyScope(scope),price));}
            catch(ModelPriceCatalogMaintenance.Conflict conflict){throw new PositionSkillException(409,"MODEL_PRICE_CONFLICT");}
        };
    }

    Map<String,Object> readBinding(Scope scope,String persona) {
        return Map.of("personaCode",persona,"configuredModel",configuredModel(scope,persona));
    }

    private String configuredModel(Scope scope,String persona) {
        var row=bindings.find(scope.tenantId(),scope.clientId(),persona);
        require(row!=null,404,"MODEL_HERO_NOT_FOUND");
        return row.modelId();
    }

    Map<String,Object> changeBinding(Scope scope,String persona,String expected,String model) {
        if(expected.equals(model)) {
            require(model.equals(configuredModel(scope,persona)),409,"MODEL_BINDING_CONFLICT");
            return Map.of("personaCode",persona,"configuredModel",model,"disposition","UNCHANGED");
        }
        require(bindings.changeModel(scope.tenantId(),scope.clientId(),persona,expected,model),409,"MODEL_BINDING_CONFLICT_OR_PLAN_MISSING");
        return Map.of("personaCode",persona,"previousConfiguredModel",expected,"configuredModel",model,"disposition","CHANGED");
    }

    private static EconomyScope economyScope(Scope scope) {return new EconomyScope(scope.tenantId(),scope.clientId());}
    private static Map<String,Object> priceReceipt(ModelPriceCatalogMaintenance.Price price) {
        return Map.of("model",price.model(),"configured",true,"channel",price.channel(),"tier",price.tier(),
                "priceVersion",price.version(),"price",price.data(),"disposition",price.disposition());
    }
}
