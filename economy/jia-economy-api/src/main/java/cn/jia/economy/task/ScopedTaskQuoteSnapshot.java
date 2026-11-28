package cn.jia.economy.task;

import cn.jia.economy.pricing.GroupMeanPricing;
import java.math.BigDecimal;
import java.util.*;

/** Private immutable fixed-price fact. Construction is neither task authorization nor baseline approval. */
public record ScopedTaskQuoteSnapshot(String schema,TaskBillingService.Binding binding,ScopePin scopePin,
        Basis basis,PricingProfilePin pricingProfilePin,String initialIntentSha256,
        TaskBillingService.MeasuredGroup executionBinding,long amountWen) {
    public static final String SCHEMA="SCOPED_FIXED_TASK_QUOTE_V1";
    public ScopedTaskQuoteSnapshot {
        require(SCHEMA.equals(schema),"Unknown scoped quote schema");Objects.requireNonNull(binding);Objects.requireNonNull(scopePin);Objects.requireNonNull(basis);
        require(binding.requirementRevision()==scopePin.requirementRevision()
                && binding.requirementSha256().equals(scopePin.requirementSha256())
                && binding.inputSnapshotDigest().equals(scopePin.inputSnapshotDigest()),"Scope does not match quote binding");
        require(amountWen==basis.amountWen(),"Scoped quote amount differs from basis");
        require((initialIntentSha256==null)==(executionBinding==null),"Incomplete initial execution commitment");
        if(initialIntentSha256!=null)sha(initialIntentSha256);
        if(basis.mean()!=null)require(pricingProfilePin!=null,"Mean quote needs measured configuration pin");
    }
    public record ScopePin(long requirementRevision,String requirementSha256,String scopeSha256,String inputSnapshotDigest){
        public ScopePin {require(requirementRevision>0,"Invalid requirement revision");sha(requirementSha256);sha(scopeSha256);sha(inputSnapshotDigest);}
    }
    public record PricingProfilePin(String installationId,String runtimeInstanceId,long sessionGeneration,long reportSequence,
            String reportSha,String profileId,long profileRevision,String measurementSha,String configSha){
        public PricingProfilePin {
            id(installationId);id(runtimeInstanceId);id(profileId);
            require(sessionGeneration>0&&reportSequence>0&&profileRevision>0,"Invalid measured profile revision");
            sha(reportSha);sha(measurementSha);sha(configSha);
        }
    }
    public record MeanEvidence(String scopeShapeSha,List<String> bucketIds,String configSemanticSha,List<String> specShas,
            String policySha,String groupSha,long bookEpoch,long generation,long revision,String tariffSha,int sampleCount,BigDecimal sumCostYuan){
        public MeanEvidence {
            sha(scopeShapeSha);sha(configSemanticSha);sha(policySha);sha(groupSha);sha(tariffSha);
            bucketIds=List.copyOf(bucketIds);specShas=List.copyOf(specShas);
            require(!bucketIds.isEmpty()&&bucketIds.size()==specShas.size()&&bucketIds.size()<=128,"Incomplete scoped group evidence");
            bucketIds.forEach(ScopedTaskQuoteSnapshot::id);specShas.forEach(ScopedTaskQuoteSnapshot::sha);
            require(bookEpoch>=0&&generation>0&&revision>0&&sampleCount>0,"Invalid published revision");
            Objects.requireNonNull(sumCostYuan);
            var canonical=sumCostYuan.stripTrailingZeros();
            require(Math.max(0,canonical.scale())<=30&&canonical.precision()-canonical.scale()<=35,"Cost exceeds DECIMAL(65,30)");
            GroupMeanPricing.amountWen(sumCostYuan,sampleCount);
        }
        public static MeanEvidence from(GroupMeanPricing.ScopeGroup group,GroupBudgetQuotes.Mean mean){
            var p=mean.pin();require(group.key().equals(p.key().groupSha())&&group.policySha().equals(p.policySha()),"Published group differs from scope");
            require(mean.amountWen()==GroupMeanPricing.amountWen(p.sumYuan(),p.n()),"Published amount differs from whole-task mean");
            return new MeanEvidence(group.scopeShapeSha(),group.bucketIds(),group.configSemanticSha(),
                    group.scopeShape().stream().map(GroupMeanPricing.ScopeSlot::specSha).toList(),p.policySha(),p.key().groupSha(),
                    p.bookEpoch(),p.generation(),p.revision(),p.tariffSha(),p.n(),p.sumYuan());
        }
    }
    /** Explicit union, not a nullable average or a zero-cost inference. */
    public record Basis(String method,MeanEvidence mean,TaskQuoteBatches.Reason reason){
        public Basis {
            if("GROUP_MEAN".equals(method))require(mean!=null&&reason==null,"Invalid mean basis");
            else if("FIXED_FALLBACK".equals(method))require(mean==null&&reason!=null&&Set.of(TaskQuoteBatches.Reason.NO_BASELINE,
                    TaskQuoteBatches.Reason.OUT_OF_DISTRIBUTION,TaskQuoteBatches.Reason.BILLING_ROUTE_MISSING,
                    TaskQuoteBatches.Reason.PRICE_MISSING,TaskQuoteBatches.Reason.COST_INPUT_MISSING).contains(reason),"Invalid fixed fallback basis");
            else throw new IllegalArgumentException("Unknown fixed quote basis");
        }
        public long amountWen(){return mean==null?1:GroupMeanPricing.amountWen(mean.sumCostYuan(),mean.sampleCount());}
    }
    public String featureSha(){return featureSha(scopePin);}
    public static String featureSha(ScopePin scopePin){Objects.requireNonNull(scopePin);return cn.jia.core.util.CanonicalValueEncoding.digest(Map.of("schema","SCOPED_QUOTE_FEATURE_V1",
            "requirementRevision",Long.toString(scopePin.requirementRevision()),"requirementSha256",scopePin.requirementSha256(),
            "scopeSha256",scopePin.scopeSha256(),"inputSnapshotDigest",scopePin.inputSnapshotDigest()));}
    @Override public String toString(){return "ScopedTaskQuoteSnapshot[private]";}
    private static void id(String value){require(value!=null&&!value.isBlank(),"Missing quote evidence identity");}
    private static void sha(String value){require(value!=null&&value.matches("[0-9a-f]{64}"),"Invalid quote evidence digest");}
    private static void require(boolean valid,String message){if(!valid)throw new IllegalArgumentException(message);}
}
