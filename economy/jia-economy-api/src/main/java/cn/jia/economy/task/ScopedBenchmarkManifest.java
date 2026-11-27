package cn.jia.economy.task;

import cn.jia.core.util.CanonicalValueEncoding;
import cn.jia.economy.pricing.GroupMeanPricing;
import java.util.*;

/** Immutable operator-approved whole-scope case. Not a quote, execution grant or publication approval. */
public record ScopedBenchmarkManifest(String schema,TaskBillingService.Task task,String caseId,String agentId,
        String approvalRef,String completionCriterion,Purpose purpose,ScopedTaskQuoteSnapshot.ScopePin scopePin,
        GroupMeanPricing.ScopedPolicy policy,List<GroupMeanPricing.ScopeSlot> scopeShape,List<String> bucketIds,
        Map<String,Object> compatibleConfig,String groupSha) {
    public static final String SCHEMA="SCOPED_BENCHMARK_V1";
    public enum Purpose { TRAIN,HOLDOUT }
    public ScopedBenchmarkManifest {
        if(!SCHEMA.equals(schema))throw new IllegalArgumentException("Unknown scoped benchmark schema");
        Objects.requireNonNull(task);Objects.requireNonNull(scopePin);Objects.requireNonNull(policy);Objects.requireNonNull(purpose);
        for(var value:List.of(caseId,agentId,approvalRef,completionCriterion))
            if(value.isBlank() || !value.equals(value.strip()))throw new IllegalArgumentException("Incomplete scoped benchmark identity");
        scopeShape=List.copyOf(scopeShape);bucketIds=List.copyOf(bucketIds);
        compatibleConfig=freezeMap(compatibleConfig);
        var group=new GroupMeanPricing.ScopeGroup(policy.featureVersion(),policy.sha(),scopeShape,bucketIds,compatibleConfig);
        policy.requireInput(group.input());
        if(!group.key().equals(groupSha))throw new IllegalArgumentException("Scoped benchmark group differs");
    }
    public GroupMeanPricing.ScopeGroup group(){return new GroupMeanPricing.ScopeGroup(policy.featureVersion(),policy.sha(),scopeShape,bucketIds,compatibleConfig);}
    private static Map<String,Object> freezeMap(Map<String,?> map){
        var result=new LinkedHashMap<String,Object>();
        map.forEach((key,value)->{CanonicalValueEncoding.utf8(key);result.put(key,freeze(value));});
        return Collections.unmodifiableMap(result);
    }
    private static Object freeze(Object value){
        if(value instanceof String text){CanonicalValueEncoding.utf8(text);return text;}
        if(value instanceof List<?> list)return list.stream().map(ScopedBenchmarkManifest::freeze).toList();
        if(value instanceof Map<?,?> map){
            var result=new LinkedHashMap<String,Object>();
            map.forEach((key,v)->{if(!(key instanceof String text))throw new IllegalArgumentException("Invalid configuration key");CanonicalValueEncoding.utf8(text);result.put(text,freeze(v));});
            return Collections.unmodifiableMap(result);
        }
        throw new IllegalArgumentException("Configuration values must be canonical strings, lists or maps");
    }
    @Override public String toString(){return "ScopedBenchmarkManifest[private]";}
}
