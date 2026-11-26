package cn.jia.economy.service.impl;

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
    record Selected(GroupMeanPricing.Policy policy,Map<String,Map<String,TaskReferencePricing.Route>> routes,String receiptSha){}
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
        var routes=new HashMap<String,Map<String,TaskReferencePricing.Route>>();
        for(var row:g.routes())routes.computeIfAbsent(row.kind(),k->new HashMap<>()).put(row.component(),new TaskReferencePricing.TokenRoute(row.channel(),row.model(),row.tier(),row.normalizationBasis()));
        require(Set.of("edit","generate").contains(g.operation())&&routes.keySet().equals(Set.of("MODEL_RESPONSE","NATIVE_PROVIDER_CALL"))&&routes.get("MODEL_RESPONSE").keySet().equals(Set.of("INFERENCE"))&&routes.get("NATIVE_PROVIDER_CALL").keySet().equals(Set.of(g.operation())));
        return new Selected(policy,Map.copyOf(routes),bytes.sha());
    }
    private static boolean sha(String v){return v!=null&&v.matches("[0-9a-f]{64}");}
    private static void require(boolean ok){if(!ok)throw new GroupBaselinePublisher.MoreInputRequired();}
}
