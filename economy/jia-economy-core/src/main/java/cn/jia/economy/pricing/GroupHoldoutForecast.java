package cn.jia.economy.pricing;

import java.math.BigDecimal;
import java.util.*;

/** Offline only. Prepare once per preregistered shared group. The caller verifies approval, group
 * membership, split and tariff artifact bytes; this calculator cannot grant or publish a baseline. */
public final class GroupHoldoutForecast {
    public record Domain(String tenant,String client,String groupSha){public Domain{
        if(tenant.isBlank()||client.isBlank()||!groupSha.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid study domain");
    }}
    public record Training(Domain domain,String sampleId,TaskEstimateEvaluation.Case source,long revision,TaskEstimateFeatures features,
                           List<MeasuredTaskRepricing.Fact> facts){public Training{facts=List.copyOf(facts);}}
    /** Null is abstention, not an offer of one wen. No holdout outcome is accepted here. */
    public record Pair(Long meanWen,Long neighborWen){}
    private final Domain domain;
    private record Member(long revision,TaskEstimateFeatures features,BigDecimal cost){}
    private final SortedMap<String,Member> members=new TreeMap<>();
    private final Set<String> tasks=new HashSet<>();
    private final Set<String> families=new HashSet<>();
    private long lastTruth=Long.MIN_VALUE;
    private final Long mean;
    private final TaskNeighborSearch.Parameters parameters;

    public GroupHoldoutForecast(Domain domain, MeasuredTaskRepricing.Reference book, List<Training> training,
                                int minimumMean, TaskEstimateCalibration.Trial frozenTrial){
        this.domain=Objects.requireNonNull(domain);
        if(minimumMean<1)throw new IllegalArgumentException("Missing group support policy");
        BigDecimal total=BigDecimal.ZERO;boolean allFeatures=true;
        for(var row:training){var c=row.source();
            if(!domain.equals(row.domain())||c.split()!=TaskEstimateEvaluation.Split.TRAIN||row.revision()<1||row.sampleId().isBlank()||!tasks.add(c.task())||members.containsKey(row.sampleId()))
                throw new IllegalArgumentException("Foreign, duplicate or non-training whole task");
            families.add(c.family());lastTruth=Math.max(lastTruth,c.truthAt());
            var cost=MeasuredTaskRepricing.reprice(book,row.facts()).yuan();members.put(row.sampleId(),new Member(row.revision(),row.features(),cost));total=total.add(cost);
            allFeatures &= row.features()!=null;
        }
        mean=members.size()<minimumMean?null:GroupMeanPricing.amountWen(total,members.size());
        parameters=frozenTrial==null||members.isEmpty()||!allFeatures?null:frozenTrial.fit(TaskNeighborSearch.trainingScales(members.values().stream().map(Member::features).toList()));
    }
    public Pair predict(Domain held,String task,String family,long featuresAt,TaskEstimateFeatures query){
        if(!domain.equals(held)||task.isBlank()||family.isBlank()||tasks.contains(task)||families.contains(family)||(!members.isEmpty()&&featuresAt<=lastTruth))
            throw new IllegalArgumentException("Holdout crosses domain, task, family or time boundary");
        if(parameters==null||query==null)return new Pair(mean,null);
        var search=new TaskNeighborSearch(query,parameters);
        for(var entry:members.entrySet()){var row=entry.getValue();var f=row.features();
            if(!query.embeddingIdentity().equals(f.embeddingIdentity())||!query.featureVersion().equals(f.featureVersion())||!query.templateVersion().equals(f.templateVersion()))return new Pair(mean,null);
            search.accept(entry.getKey(),row.revision(),f);
        }
        try{BigDecimal numerator=BigDecimal.ZERO,denominator=BigDecimal.ZERO;
            for(var hit:search.selected()){numerator=numerator.add(members.get(hit.id()).cost().multiply(hit.weight()));denominator=denominator.add(hit.weight());}
            return new Pair(mean,CnyAmounts.quoteWen(numerator,denominator));
        }catch(MeasuredTaskRepricing.Unavailable noNeighbors){return new Pair(mean,null);}
    }
}
