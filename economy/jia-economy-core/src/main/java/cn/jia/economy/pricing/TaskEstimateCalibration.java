package cn.jia.economy.pricing;

import java.math.BigDecimal;
import java.util.*;
import cn.jia.economy.pricing.TaskEstimateEvaluation.Case;
import cn.jia.economy.pricing.TaskEstimateEvaluation.Prediction;
import cn.jia.economy.pricing.TaskEstimateEvaluation.Ratio;
import cn.jia.economy.pricing.TaskEstimateEvaluation.Scores;
import cn.jia.economy.pricing.TaskEstimateEvaluation.Split;

/** Offline, one admitted private group and one frozen reference book per run. No DB, HTTP or automatic activation. */
public final class TaskEstimateCalibration {
    private TaskEstimateCalibration(){}
    /** Technical compatibility alone is not permission to pool private tasks. This key stays in private artifacts. */
    public record PrivateGroup(String tenantId,String clientId,String ownerJiacn,String compatibilitySha256){
        public PrivateGroup{
            for(var value:List.of(tenantId,clientId,ownerJiacn))if(value.isBlank())throw new IllegalArgumentException("Missing private calibration scope");
            if(!compatibilitySha256.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Missing calibration group commitment");
        }
        @Override public String toString(){return "PrivateGroup[private]";}
    }
    /** Identity and whole-task eligibility come from the trusted source; never a user prediction or historical charge. */
    public record Sample(PrivateGroup group,String id,long revision,Case split,TaskEstimateFeatures features,List<MeasuredTaskRepricing.Fact> facts){
        public Sample{if(group==null||id==null||id.isBlank()||revision<1)throw new IllegalArgumentException("Missing measured sample identity");
            Objects.requireNonNull(split);Objects.requireNonNull(features);facts=List.copyOf(facts);}
    }
    public record Trial(String id,double text,double files,double bytes,double types,double deliverables,double bothMissing,double oneMissing,
                        int neighbors,int minimum,double maxDistance,double bandwidth){
        TaskNeighborSearch.Parameters fit(TaskNeighborSearch.Scales s){return new TaskNeighborSearch.Parameters(s,text,files,bytes,types,deliverables,bothMissing,oneMissing,neighbors,minimum,maxDistance,bandwidth);}
    }
    /** Sample floor and coverage are predeclared per group, never chosen from holdout outcomes. §6 error ceilings are not tunable. */
    public record Gate(int minimumPositiveQuoted,BigDecimal minimumCoverage){
        public Gate{if(minimumPositiveQuoted<1||minimumCoverage.signum()<=0||minimumCoverage.compareTo(BigDecimal.ONE)>0)throw new IllegalArgumentException("Invalid calibration gate");}
        public boolean passes(Scores s){return s.positiveQuoted()>=minimumPositiveQuoted&&s.wape()!=null&&s.p90Relative()!=null
                &&BigDecimal.valueOf(s.quoted()).compareTo(minimumCoverage.multiply(BigDecimal.valueOf(s.eligible())))>=0
                &&s.wape().compareTo(new Ratio(BigDecimal.ONE,BigDecimal.valueOf(4)))<=0&&s.p90Relative().compareTo(new Ratio(BigDecimal.ONE,BigDecimal.valueOf(2)))<=0;}
    }
    public record Fit(String trial,TaskNeighborSearch.Parameters parameters,Scores calibration){}
    public record Report(PrivateGroup group,String referenceVersion,Fit winner,Scores holdout,boolean meetsPointCriteria){}
    private static final Comparator<Fit> ORDER=Comparator.<Fit>comparingLong(f->f.calibration().quoted()).reversed()
            .thenComparing(f->f.calibration().wape()).thenComparing(f->f.calibration().p90Relative()).thenComparing(Fit::trial);
    public static Report evaluate(PrivateGroup group,MeasuredTaskRepricing.Reference reference,List<Sample> cases,List<Trial> trials,Gate gate){
        return evaluateOnce(group,reference,cases,trials,gate).report();
    }
    public record EvaluationReport(Report points, TaskEstimateUncertainty.Report uncertainty) { }
    private record Scored(Report report,List<Case> manifest,List<Prediction> holdout) { }
    /** Fixed seed belongs to the preregistered evaluation. Never use the interval to choose another trial. */
    public static EvaluationReport evaluateWithIntervals(PrivateGroup group,MeasuredTaskRepricing.Reference reference,List<Sample> cases,List<Trial> trials,Gate gate,long seed){
        var r=evaluateOnce(group,reference,cases,trials,gate);
        return new EvaluationReport(r.report(),TaskEstimateUncertainty.estimate(r.manifest(),r.holdout(),seed));
    }
    private static Scored evaluateOnce(PrivateGroup group,MeasuredTaskRepricing.Reference reference,List<Sample> cases,List<Trial> trials,Gate gate){
        var manifest=cases.stream().map(Sample::split).toList();TaskEstimateEvaluation.checkSplit(manifest);
        var generation=cases.getFirst().features();var train=new TreeMap<String,Sample>();var ids=new HashSet<String>();
        for(var s:cases){var f=s.features();if(!group.equals(s.group())||!ids.add(s.id())||!generation.featureVersion().equals(f.featureVersion())
                ||!generation.templateVersion().equals(f.templateVersion())||!generation.embeddingIdentity().equals(f.embeddingIdentity()))throw new IllegalArgumentException("Incompatible calibration dataset");
            if(s.split().split()==Split.TRAIN)train.put(s.id(),s);}
        var scales=TaskNeighborSearch.trainingScales(train.values().stream().map(Sample::features).toList());
        var trialIds=new HashSet<String>();Fit winner=null;
        for(var candidate:trials){if(candidate.id()==null||candidate.id().isBlank()||!trialIds.add(candidate.id()))throw new IllegalArgumentException("Unstable trial identity");
            var p=candidate.fit(scales);var scores=TaskEstimateEvaluation.score(manifest,predict(Split.CALIBRATE,cases,train,p,reference),Split.CALIBRATE);
            if(gate.passes(scores)){var fit=new Fit(candidate.id(),p,scores);if(winner==null||ORDER.compare(fit,winner)<0)winner=fit;}}
        // Freeze the winner before scoring holdout. No second choice if it misses the error or coverage gate.
        var predictions=predict(Split.HOLDOUT,cases,train,winner==null?null:winner.parameters(),reference);
        var held=TaskEstimateEvaluation.score(manifest,predictions);
        return new Scored(new Report(group,reference.version(),winner,held,winner!=null&&gate.passes(held)),manifest,predictions);
    }
    private static List<Prediction> predict(Split partition,List<Sample> cases,SortedMap<String,Sample> train,
                                 TaskNeighborSearch.Parameters p,MeasuredTaskRepricing.Reference reference){
        var predictions=new ArrayList<Prediction>();
        for(var row:cases){if(row.split().split()!=partition)continue;
            var truth=MeasuredTaskRepricing.reprice(reference,row.facts()).yuan();Long predicted=null;
            if(p!=null){var search=new TaskNeighborSearch(row.features(),p);for(var s:train.values())search.accept(s.id(),s.revision(),s.features());
                List<TaskNeighborSearch.Hit> hits;
                try{hits=search.selected();}catch(MeasuredTaskRepricing.Unavailable noSupport){hits=List.of();}
                if(!hits.isEmpty()){var neighbors=hits.stream().map(h->new MeasuredNeighborEstimate.Neighbor(h.id(),Long.toString(h.revision()),h.weight(),train.get(h.id()).facts())).toList();
                    predicted=MeasuredNeighborEstimate.aggregate(reference,neighbors).wen();}}
            predictions.add(new Prediction(row.split().task(),truth,predicted));}
        return predictions;
    }
}
