package cn.jia.economy.pricing;

import cn.jia.core.util.CanonicalValueEncoding;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Resolved reference pricing identity. Neither a measured resource reference nor actual billing evidence. */
public sealed interface CostPricingRoute permits CostPricingRoute.TokenRoute, CostPricingRoute.UnitRoute, CostPricingRoute.ZeroRoute {
    record TokenRoute(String channel, String model, String tier, String normalizationBasis) implements CostPricingRoute {
        public TokenRoute {identity(channel,100);identity(model,200);identity(tier,100);identity(normalizationBasis,128);}
    }
    record UnitRoute(TaskQuoteCalculator.ToolKey tool) implements CostPricingRoute {
        public UnitRoute {Objects.requireNonNull(tool);identity(tool.channel(),100);identity(tool.tool(),100);identity(tool.unit(),100);}
    }
    record ZeroRoute(String contractVersion) implements CostPricingRoute {
        public ZeroRoute {identity(contractVersion,128);}
    }

    /** Same canonical business fields for maintenance, immutable storage and resolved configuration hashes. */
    default Map<String,Object> canonical() {
        return switch(this) {
            case TokenRoute r -> Map.of("type","TOKEN","channel",r.channel(),"model",r.model(),"tier",r.tier(),"basis",r.normalizationBasis());
            case UnitRoute r -> Map.of("type","UNIT","channel",r.tool().channel(),"tool",r.tool().tool(),"unit",r.tool().unit());
            case ZeroRoute r -> Map.of("type","ZERO","contractVersion",r.contractVersion());
        };
    }

    /** Parses an already bounded, duplicate-key-checked object; transport/database decoding stays at the boundary. */
    static CostPricingRoute fromCanonical(Map<String,Object> value) {
        if(value==null)throw new IllegalArgumentException("Missing cost pricing route");
        return switch(text(value,"type")) {
            case "TOKEN" -> {keys(value,"type","channel","model","tier","basis");yield new TokenRoute(text(value,"channel"),text(value,"model"),text(value,"tier"),text(value,"basis"));}
            case "UNIT" -> {keys(value,"type","channel","tool","unit");yield new UnitRoute(new TaskQuoteCalculator.ToolKey(text(value,"channel"),text(value,"tool"),text(value,"unit")));}
            case "ZERO" -> {keys(value,"type","contractVersion");yield new ZeroRoute(text(value,"contractVersion"));}
            default -> throw new IllegalArgumentException("Unknown cost pricing mechanism");
        };
    }
    private static void identity(String value,int max) {
        if(value==null || value.isBlank() || !value.equals(value.strip()) || value.codePointCount(0,value.length())>max)
            throw new IllegalArgumentException("Invalid cost pricing identity");
        CanonicalValueEncoding.utf8(value);
    }
    private static String text(Map<String,Object> value,String key) {
        if(!(value.get(key) instanceof String text))throw new IllegalArgumentException("Invalid cost pricing field");
        return text;
    }
    private static void keys(Map<String,Object> value,String... names) {
        if(!value.keySet().equals(Set.of(names)))throw new IllegalArgumentException("Invalid cost pricing fields");
    }
}
