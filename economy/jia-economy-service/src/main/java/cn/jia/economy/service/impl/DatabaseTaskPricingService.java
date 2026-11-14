package cn.jia.economy.service.impl;

import cn.jia.economy.entity.EconomyModelPriceRow;
import cn.jia.economy.mapper.EconomyPriceCatalogMapper;
import cn.jia.economy.pricing.ModelPriceCatalog;
import cn.jia.economy.pricing.ModelTokenPrice;
import cn.jia.economy.pricing.TaskPricingService;
import cn.jia.economy.pricing.TaskQuoteCalculator;
import cn.jia.economy.pricing.TaskWorkload;
import cn.jia.economy.service.EconomyScope;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static cn.jia.economy.pricing.TaskPricingService.Unavailable.Reason.*;

/** Reads only the selected catalog keys in the caller's existing transaction. No price cache. */
@Service
public final class DatabaseTaskPricingService implements TaskPricingService, ModelPriceCatalog {
    private final EconomyPriceCatalogMapper mapper;
    private final ObjectMapper json;

    public DatabaseTaskPricingService(EconomyPriceCatalogMapper mapper, ObjectMapper json) {
        this.mapper = mapper;
        this.json = json;
    }

    @Override
    public Optional<ModelTokenPrice> find(EconomyScope scope, String channel, String model, String tier) {
        if (missing(channel, model, tier)) return Optional.empty();
        return Optional.ofNullable(mapper.findCurrentModel(scope.tenantId(), scope.clientId(), channel, model, tier))
                .map(this::modelPrice);
    }

    @Override
    public Optional<ModelTokenPrice> findVersion(EconomyScope scope, String channel, String model,
            String tier, String version) {
        if (missing(channel, model, tier, version)) return Optional.empty();
        return Optional.ofNullable(mapper.findModelVersion(scope.tenantId(), scope.clientId(), channel, model, tier, version))
                .map(this::modelPrice);
    }

    @Override
    public PreparedQuote prepare(EconomyScope scope, TaskWorkload workload, String reportedModel) {
        if (missing(reportedModel)) throw new Unavailable(BILLING_ROUTE_MISSING);
        Objects.requireNonNull(workload, "workload");
        var tariff = mapper.findTaskTariff(scope.tenantId(), scope.clientId(), reportedModel);
        if (tariff == null) throw new Unavailable(BILLING_ROUTE_MISSING);
        var price = find(scope, tariff.getChannel(), reportedModel, tariff.getTier())
                .orElseThrow(() -> new Unavailable(MODEL_PRICE_MISSING));
        var units = new LinkedHashMap<TaskQuoteCalculator.ToolKey, BigDecimal>();
        var entries = json.readTree(tariff.getToolsJson());
        if (entries == null || !entries.isArray()) throw new IllegalArgumentException("Tariff tools must be an array");
        for (var entry : entries) {
            var key = new TaskQuoteCalculator.ToolKey(text(entry, "channel"), text(entry, "tool"), text(entry, "unit"));
            var perDeliverable = decimal(entry, "unitsPerDeliverable");
            if (perDeliverable.signum() < 0) throw new IllegalArgumentException("Negative tool quantity");
            if (units.putIfAbsent(key, perDeliverable.multiply(BigDecimal.valueOf(workload.deliverables()))) != null)
                throw new IllegalArgumentException("Duplicate tariff tool key");
        }
        var effective = new TaskWorkload(workload.kind(), workload.inputTokens(), workload.outputTokens(),
                workload.deliverables(), units);
        var estimate = effective.estimate();
        for (var entry : estimate.tokens().entrySet()) {
            if (entry.getValue() > 0 && !price.yuanPerMillionTokens().containsKey(entry.getKey()))
                throw new Unavailable(TOKEN_PRICE_MISSING);
        }
        var tools = new LinkedHashMap<TaskQuoteCalculator.ToolKey, TaskQuoteCalculator.ToolPrice>();
        for (var entry : units.entrySet()) {
            if (entry.getValue().signum() == 0) continue;
            var key = entry.getKey();
            var row = mapper.findCurrentTool(scope.tenantId(), scope.clientId(), key.channel(), key.tool(), key.unit());
            if (row == null) throw new Unavailable(TOOL_PRICE_MISSING);
            var data = json.readTree(row.getPriceDataJson());
            tools.put(key, new TaskQuoteCalculator.ToolPrice(row.getPriceVersion(), text(data, "basis"), decimal(data, "yuanPerUnit")));
        }
        return new PreparedQuote(estimate, price, tools, effective);
    }

    private ModelTokenPrice modelPrice(EconomyModelPriceRow row) {
        var data = json.readTree(row.getPriceDataJson());
        var threshold = data.get("shortContextMaxInputTokens");
        Long maxInput = null;
        if (threshold != null && !threshold.isNull()) {
            if (!threshold.isIntegralNumber() || !threshold.canConvertToLong())
                throw new IllegalArgumentException("Context threshold must be a long integer");
            maxInput = threshold.longValue();
        }
        return new ModelTokenPrice(row.getChannel(), row.getModel(), row.getTier(), row.getPriceVersion(),
                text(data, "basis"), rates(data.get("yuanPerMillionTokens")), maxInput,
                rates(data.get("longContextYuanPerMillionTokens")));
    }

    private Map<ModelTokenPrice.Category, BigDecimal> rates(JsonNode node) {
        if (node == null || !node.isObject()) throw new IllegalArgumentException("Price rates must be an object");
        var rates = new EnumMap<ModelTokenPrice.Category, BigDecimal>(ModelTokenPrice.Category.class);
        for (var entry : node.properties())
            rates.put(ModelTokenPrice.Category.valueOf(entry.getKey()), decimal(node, entry.getKey()));
        return rates;
    }

    private static String text(JsonNode node, String field) {
        var value = node == null ? null : node.get(field);
        if (value == null || !value.isString() || value.asString().isBlank())
            throw new IllegalArgumentException("Missing catalog string: " + field);
        return value.asString();
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        return new BigDecimal(text(node, field));
    }

    private static boolean missing(String... keys) {
        for (var key : keys) if (key == null || key.isBlank()) return true;
        return false;
    }
}
