package cn.jia.economy.pricing;

import java.math.BigDecimal;
import java.util.*;
import static cn.jia.economy.pricing.TaskEstimateEvaluation.*;

/** Offline paired family bootstrap on a frozen group's holdout. Descriptive intervals, not an activation gate. */
public final class TaskEstimateUncertainty {
    private TaskEstimateUncertainty() { }
    public static final int DRAWS=1000;
    public record Interval(int defined,Ratio low,Ratio high) { }
    public record Report(String method,long seed,int families,int draws,Interval coverage,Interval underestimateRate,Interval wape,Interval p90Relative) { }
    private static Interval interval(List<Ratio> values){
        if(values.size()!=DRAWS)return new Interval(values.size(),null,null);
        values.sort(Comparator.naturalOrder());return new Interval(DRAWS,values.get(24),values.get(974));
    }
    static Report estimate(List<Case> manifest,List<Prediction> held,long seed){
        // Caller has already joined against the full roster and validated partition/family separation.
        var families=new TreeMap<String,List<Prediction>>();var index=new HashMap<String,String>();
        for(var c:manifest)if(c.split()==Split.HOLDOUT)index.put(c.task(),c.family());
        for(var row:held)families.computeIfAbsent(index.get(row.task()),unused->new ArrayList<>()).add(row);
        var groups=new ArrayList<>(families.values());for(var rows:groups)rows.sort(Comparator.comparing(Prediction::task));
        var random=new SplittableRandom(seed);int g=groups.size(),draws=g<2?0:DRAWS;
        List<Ratio> coverage=new ArrayList<>(),under=new ArrayList<>(),wape=new ArrayList<>(),p90=new ArrayList<>();
        int[] copies=new int[g];
        for(int b=0;b<draws;b++){
            Arrays.fill(copies,0);for(int j=0;j<g;j++)copies[random.nextInt(g)]++;
            var totals=new TaskEstimateEvaluation.Totals();
            for(int j=0;j<g;j++)if(copies[j]!=0)for(var row:groups.get(j))totals.add(row,copies[j]);
            var s=totals.scores();coverage.add(new Ratio(BigDecimal.valueOf(s.quoted()),BigDecimal.valueOf(s.eligible())));
            if(s.quoted()>0)under.add(new Ratio(BigDecimal.valueOf(s.underestimates()),BigDecimal.valueOf(s.quoted())));
            if(s.wape()!=null)wape.add(s.wape());if(s.p90Relative()!=null)p90.add(s.p90Relative());
        }
        return new Report("family-percentile-95-v1",seed,g,draws,interval(coverage),interval(under),interval(wape),interval(p90));
    }
}
