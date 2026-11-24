package cn.jia.economy.pricing;

import java.math.BigDecimal;
import java.util.*;

/** Offline evaluator. Accept only version-bound, whole-task ground truths from the trusted dataset builder.
 * Does not produce a calibration grant or give synthetic cases production status. */
public final class TaskEstimateEvaluation {
    private TaskEstimateEvaluation(){}
    public enum Split{TRAIN,CALIBRATE,HOLDOUT}
    public record Case(String task,String family,long featuresAt,long truthAt,Split split){
        public Case{if(task==null||task.isBlank()||family==null||family.isBlank()||truthAt<featuresAt)throw new IllegalArgumentException("Invalid measured case");Objects.requireNonNull(split);}
    }
    /** A late completion cannot leak into training for an earlier evaluation prediction. */
    public static void checkSplit(Collection<Case> cases){
        var tasks=new HashSet<String>();var families=new HashMap<String,Split>();var starts=new EnumMap<Split,Long>(Split.class);var ends=new EnumMap<Split,Long>(Split.class);
        for(var c:cases){if(!tasks.add(c.task()))throw new IllegalArgumentException("Repeated whole task");var old=families.putIfAbsent(c.family(),c.split());
            if(old!=null&&old!=c.split())throw new IllegalArgumentException("Template family crosses splits");
            starts.merge(c.split(),c.featuresAt(),Math::min);ends.merge(c.split(),c.truthAt(),Math::max);}
        if(starts.size()!=3||ends.get(Split.TRAIN)>=starts.get(Split.CALIBRATE)||ends.get(Split.CALIBRATE)>=starts.get(Split.HOLDOUT))throw new IllegalArgumentException("Invalid temporal separation");
    }
    /** Null predictedWen is an explicit abstention; never a successful zero prediction. */
    public record Prediction(String task,BigDecimal truthYuan,Long predictedWen){public Prediction{
        if(task==null||task.isBlank()||predictedWen!=null&&predictedWen<0)throw new IllegalArgumentException("Invalid case prediction");CnyAmounts.nonNegative(truthYuan);
    }}
    public record Ratio(BigDecimal numerator,BigDecimal denominator) implements Comparable<Ratio>{
        public Ratio{if(numerator.signum()<0||denominator.signum()<=0)throw new IllegalArgumentException("Undefined ratio");}
        public int compareTo(Ratio other){return numerator.multiply(other.denominator).compareTo(other.numerator.multiply(denominator));}
    }
    public record Scores(long eligible,long quoted,long underestimates,long positiveQuoted,long zeroQuoted,BigDecimal zeroAbsoluteYuanSum,
                         Ratio wape,Ratio p90Relative){ }
    /** Join against the full eligible holdout roster. Missing cases cannot be silently omitted to raise coverage. */
    public static Scores score(Collection<Case> manifest,Collection<Prediction> predictions){
        return score(manifest,predictions,Split.HOLDOUT);
    }
    static Scores score(Collection<Case> manifest,Collection<Prediction> predictions,Split partition){
        if(partition==Split.TRAIN)throw new IllegalArgumentException("Training predictions are not validation evidence");
        checkSplit(manifest);var expected=new HashSet<String>();for(var c:manifest)if(c.split()==partition)expected.add(c.task());var totals=new Totals();
        for(var row:predictions){if(!expected.remove(row.task()))throw new IllegalArgumentException("Duplicate or non-holdout prediction");totals.add(row,1);}
        if(!expected.isEmpty())throw new IllegalArgumentException("Missing holdout prediction or explicit abstention");
        return totals.scores();
    }

    // A family drawn twice contributes each of its whole tasks twice; never materialize duplicate rows.
    static final class Totals {
        long eligible,quoted,under,zero;
        BigDecimal truth=BigDecimal.ZERO,error=BigDecimal.ZERO,zeroError=BigDecimal.ZERO;
        final NavigableMap<Ratio,Long> relative=new TreeMap<>();
        void add(Prediction row,int copies){
            if(copies==0)return;
            eligible+=copies;if(row.predictedWen()==null)return;
            quoted+=copies;var actual=row.truthYuan();var delta=BigDecimal.valueOf(row.predictedWen(),2).subtract(actual);
            if(delta.signum()<0)under+=copies;
            var weight=BigDecimal.valueOf(copies);var absolute=delta.abs().multiply(weight);
            truth=truth.add(actual.multiply(weight));error=error.add(absolute);
            if(actual.signum()==0){zero+=copies;zeroError=zeroError.add(absolute);}
            else relative.merge(new Ratio(delta.abs(),actual),(long)copies,Long::sum);
        }
        Scores scores(){long n=quoted-zero,rank=n-n/10;Ratio p90=null;
            for(var entry:relative.entrySet())if((rank-=entry.getValue())<=0){p90=entry.getKey();break;}
            return new Scores(eligible,quoted,under,n,zero,zeroError,truth.signum()==0?null:new Ratio(error,truth),p90);
        }
    }
}
