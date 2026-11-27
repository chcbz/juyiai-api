package cn.jia.agent.skill.quote;

import cn.jia.core.util.CanonicalValueEncoding;
import cn.jia.economy.pricing.CostRouteReference;
import java.util.*;

/** Configuration semantics only: neither execution authority, completed usage nor a billing identity. */
public record CostConfigurationObservation(String executionStrategyVersion,String contextPolicyId,
        String contextPolicyVersion,String toolPolicyVersion,String capturePolicyVersion,List<CostRouteReference> routeRefs) {
    public CostConfigurationObservation {
        id(executionStrategyVersion,64);id(contextPolicyId,128);sha(contextPolicyVersion);sha(toolPolicyVersion);id(capturePolicyVersion,64);
        routeRefs=List.copyOf(Objects.requireNonNull(routeRefs));
        require(!routeRefs.isEmpty() && routeRefs.size()<=128,"Invalid configuration routes");
        require(new HashSet<>(routeRefs).size()==routeRefs.size(),"Duplicate configuration route");
        routeRefs=routeRefs.stream().sorted(CostConfigurationObservation::compareRoutes).toList();
    }
    public Map<String,Object> canonical() {
        return Map.of("executionStrategyVersion",executionStrategyVersion,"contextPolicyId",contextPolicyId,
                "contextPolicyVersion",contextPolicyVersion,"toolPolicyVersion",toolPolicyVersion,
                "capturePolicyVersion",capturePolicyVersion,"routeRefs",routeRefs.stream().map(CostRouteReference::canonical).toList());
    }
    public String sha256(){return CanonicalValueEncoding.digest(canonical());}
    @Override public String toString(){return "CostConfigurationObservation[private]";}
    private static int compareRoutes(CostRouteReference a,CostRouteReference b) {
        var left=List.of(a.kind(),a.component(),a.namespace(),a.modelOrTool(),a.normalizationBasis());
        var right=List.of(b.kind(),b.component(),b.namespace(),b.modelOrTool(),b.normalizationBasis());
        for(int i=0;i<left.size();i++){int c=CanonicalValueEncoding.compareUtf8(left.get(i),right.get(i));if(c!=0)return c;}return 0;
    }
    private static void id(String value,int max){require(value!=null && !value.isBlank() && value.equals(value.strip()) && value.codePointCount(0,value.length())<=max,"Invalid configuration identity");CanonicalValueEncoding.utf8(value);}
    private static void sha(String value){require(value!=null && value.matches("[0-9a-f]{64}"),"Invalid configuration digest");}
    private static void require(boolean valid,String message){if(!valid)throw new IllegalArgumentException(message);}
}
