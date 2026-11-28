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
    /** Boundary codecs already own duplicate-key and size checks. No lossy string coercion. */
    public static CostRouteReference fromCanonical(Map<String,Object> value){
        if(value==null || !value.keySet().equals(java.util.Set.of("kind","component","namespace","modelOrTool","normalizationBasis")))
            throw new IllegalArgumentException("Invalid measured resource fields");
        for(var v:value.values())if(!(v instanceof String))throw new IllegalArgumentException("Invalid measured resource value");
        return new CostRouteReference((String)value.get("kind"),(String)value.get("component"),(String)value.get("namespace"),
                (String)value.get("modelOrTool"),(String)value.get("normalizationBasis"));
    }
    public String sha(){return CanonicalValueEncoding.digest(canonical());}
    @Override public String toString(){return "CostRouteReference[private]";}
}
