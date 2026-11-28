package cn.jia.economy.service.impl;

import cn.jia.economy.pricing.CostPricingRoute;

import cn.jia.economy.pricing.*;
import cn.jia.economy.mapper.GroupBaselineMaintenanceMapper.*;
import java.math.BigDecimal;
import java.util.*;
import tools.jackson.databind.ObjectMapper;

/** Rehydrate the independently approved result. No fitting on the holdout, implicit defaults or app approval writer. */
final class GroupStudyPolicy {
    record Receipt(String purpose,String tenant,String client,String approvalRef,String protocolSha,String evaluationSha,
        String policySha,String groupSha,String pricingRule,String configRule,int minSupport,GroupMeanPricing.Buckets buckets,
        GroupMeanPricing.Group group,int holdoutCases,int estimatedCases,BigDecimal minimumCoverage,BigDecimal actualSum,
        BigDecimal absoluteErrorSum,BigDecimal positiveCostP90RelativeError){}
    record Selected(GroupMeanPricing.Policy policy,Map<String,Map<String,CostPricingRoute>> routes,String receiptSha){}
    static Selected read(Work w,StudyBytes bytes,ObjectMapper json){
        if(bytes==null)throw new GroupBaselinePublisher.MoreInputRequired();
        require(bytes.sha().equals(GroupPublicationEnrollment.digest(bytes.body())));
        var r=json.readValue(bytes.body(),Receipt.class);
        require("GROUP_HOLDOUT_RELEASE_A".equals(r.purpose())&&w.tenantId().equals(r.tenant())&&w.clientId().equals(r.client())&&w.groupSha().equals(r.groupSha())&&w.policySha().equals(r.policySha()));
        require(r.approvalRef()!=null&&!r.approvalRef().isBlank()&&sha(r.protocolSha())&&sha(r.evaluationSha())&&!r.protocolSha().equals(r.evaluationSha()));
        require(r.holdoutCases()>0&&r.estimatedCases()>0&&r.estimatedCases()<=r.holdoutCases()&&r.minimumCoverage()!=null&&r.minimumCoverage().signum()>0&&r.minimumCoverage().compareTo(BigDecimal.ONE)<=0);
        require(BigDecimal.valueOf(r.estimatedCases()).compareTo(r.minimumCoverage().multiply(BigDecimal.valueOf(r.holdoutCases())))>=0&&r.actualSum()!=null&&r.actualSum().signum()>0&&r.absoluteErrorSum()!=null&&r.absoluteErrorSum().signum()>=0&&r.absoluteErrorSum().multiply(BigDecimal.valueOf(4)).compareTo(r.actualSum())<=0&&r.positiveCostP90RelativeError()!=null&&r.positiveCostP90RelativeError().signum()>=0&&r.positiveCostP90RelativeError().compareTo(new BigDecimal("0.50"))<=0);
        var g=r.group();var policy=new GroupMeanPricing.Policy(g.featureVersion(),r.pricingRule(),r.configRule(),r.buckets(),r.minSupport());
        require(w.policySha().equals(policy.sha())&&w.policySha().equals(g.policySha())&&w.groupSha().equals(GroupMeanPricing.key(g))&&r.buckets().operation().equals(g.operation())&&r.buckets().buckets().stream().anyMatch(b->b.id().equals(g.bucketId())));
        var routes=new HashMap<String,Map<String,CostPricingRoute>>();
        for(var row:g.routes())routes.computeIfAbsent(row.kind(),k->new HashMap<>()).put(row.component(),new CostPricingRoute.TokenRoute(row.channel(),row.model(),row.tier(),row.normalizationBasis()));
        require(Set.of("edit","generate").contains(g.operation())&&routes.keySet().equals(Set.of("MODEL_RESPONSE","NATIVE_PROVIDER_CALL"))&&routes.get("MODEL_RESPONSE").keySet().equals(Set.of("INFERENCE"))&&routes.get("NATIVE_PROVIDER_CALL").keySet().equals(Set.of(g.operation())));
        return new Selected(policy,Map.copyOf(routes),bytes.sha());
    }
    record ScopedSelected(ScopedGroupPolicies.Selected approved,Map<CostRouteReference,CostPricingRoute> routes) {
        ScopedSelected {routes=Map.copyOf(routes);}
    }
    static boolean isScoped(StudyBytes bytes,ObjectMapper json){
        if(bytes==null)throw new GroupBaselinePublisher.MoreInputRequired();
        if(bytes.body()==null||cn.jia.core.util.CanonicalValueEncoding.utf8(bytes.body()).length>DatabaseScopedGroupPolicies.MAX_BYTES)throw new ScopedGroupPolicies.Corrupt();
        return DatabaseScopedGroupPolicies.PURPOSE.equals(json.readTree(bytes.body()).path("purpose").asText(null));
    }
    @SuppressWarnings("unchecked")
    static ScopedSelected readScoped(Work w,StudyBytes bytes){
        var row=new cn.jia.economy.mapper.ScopedGroupPolicyMapper.Row(w.tenantId(),w.clientId(),null,w.groupSha(),bytes.sha(),bytes.body(),(long)cn.jia.core.util.CanonicalValueEncoding.utf8(bytes.body()).length,false);
        var decoded=DatabaseScopedGroupPolicies.decodeOriginal(new cn.jia.economy.service.EconomyScope(w.tenantId(),w.clientId()),row);var approved=decoded.selected();
        require(approved.policy().sha().equals(w.policySha())&&approved.group().key().equals(w.groupSha()));
        return new ScopedSelected(approved,scopedRoutes(decoded.receipt().compatibleConfig()));
    }
    @SuppressWarnings("unchecked")
    static Map<CostRouteReference,CostPricingRoute> scopedRoutes(Map<String,Object> config){
        var routes=new LinkedHashMap<CostRouteReference,CostPricingRoute>();
        if(!config.keySet().equals(Set.of("measurement","routes"))||!(config.get("routes") instanceof List<?> list)||list.isEmpty()||list.size()>128
                ||!(config.get("measurement") instanceof Map<?,?> measurement)||!(measurement.get("routeRefs") instanceof List<?> measured))throw new ScopedGroupPolicies.Corrupt();
        try{
            for(var value:list){
                if(!(value instanceof Map<?,?> item)||!item.keySet().equals(Set.of("reference","pricing")))throw new IllegalArgumentException();
                var ref=CostRouteReference.fromCanonical((Map<String,Object>)item.get("reference"));var rate=CostPricingRoute.fromCanonical((Map<String,Object>)item.get("pricing"));
                if(routes.putIfAbsent(ref,rate)!=null||rate instanceof CostPricingRoute.TokenRoute token&&!ref.normalizationBasis().equals(token.normalizationBasis()))throw new IllegalArgumentException();
            }
            var actual=new HashSet<CostRouteReference>();for(var value:measured)if(!actual.add(CostRouteReference.fromCanonical((Map<String,Object>)value)))throw new IllegalArgumentException();
            if(!actual.equals(routes.keySet()))throw new IllegalArgumentException();
        }catch(RuntimeException invalid){throw new ScopedGroupPolicies.Corrupt();}
        return Map.copyOf(routes);
    }
    private static boolean sha(String v){return v!=null&&v.matches("[0-9a-f]{64}");}
    private static void require(boolean ok){if(!ok)throw new GroupBaselinePublisher.MoreInputRequired();}
}
