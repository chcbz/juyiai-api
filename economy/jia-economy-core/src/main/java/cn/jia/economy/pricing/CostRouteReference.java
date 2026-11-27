package cn.jia.economy.pricing;

import cn.jia.core.util.CanonicalValueEncoding;
import java.util.Map;

/** Measured resource identity, not a tariff key, provider credential or execution authority. */
public record CostRouteReference(String kind,String component,String namespace,String modelOrTool,String normalizationBasis) {
    public CostRouteReference {
        for(String value:new String[]{kind,component,namespace,modelOrTool,normalizationBasis}) {
            if(value==null || value.isBlank() || !value.equals(value.strip()) || value.codePointCount(0,value.length())>128)
                throw new IllegalArgumentException("Invalid cost route reference");
            CanonicalValueEncoding.utf8(value);
        }
    }
    public Map<String,Object> canonical(){return Map.of("kind",kind,"component",component,"namespace",namespace,"modelOrTool",modelOrTool,"normalizationBasis",normalizationBasis);}
    public String sha(){return CanonicalValueEncoding.digest(canonical());}
    @Override public String toString(){return "CostRouteReference[private]";}
}
