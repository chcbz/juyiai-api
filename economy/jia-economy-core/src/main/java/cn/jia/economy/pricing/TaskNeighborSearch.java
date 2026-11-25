package cn.jia.economy.pricing;

import java.math.BigDecimal;
import java.util.*;

/** Exact streaming kNN for prefiltered whole-task samples. No IO, pricing, calibration defaults or quality grant. */
public final class TaskNeighborSearch {
    public static final String VERSION="task-neighbor-distance-v1";
    /** Training-only observed ranges. A constant feature is comparable at equality, not via an invented unit scale. */
    public record Scales(double fileCount,double logBytes,double deliverables){
        public Scales{for(double n:new double[]{fileCount,logBytes,deliverables})if(!Double.isFinite(n)||n<0)throw new IllegalArgumentException("Invalid training scale");}
    }
    /** Distances use cosine, scaled count/log-bytes, file purpose/MIME multiset and requested deliverables.
     * Both unknown and one unknown have explicit validation-selected penalties, neither implies count=0. */
    public record Parameters(Scales scales,double text,double files,double bytes,double types,double deliverables,
            double bothMissing,double oneMissing,int neighbors,int minimum,double maxDistance,double bandwidth){
        public Parameters{Objects.requireNonNull(scales);
            for(double w:new double[]{text,files,bytes,types,deliverables,bandwidth})if(!Double.isFinite(w)||w<=0)throw new IllegalArgumentException("Invalid distance parameter");
            if(!Double.isFinite(bothMissing)||bothMissing<=0||!Double.isFinite(oneMissing)||oneMissing<=0||!Double.isFinite(maxDistance)||maxDistance<0||minimum<1||neighbors<minimum)
                throw new IllegalArgumentException("Invalid calibrated support");
        }
    }
    public record Hit(String id,long revision,double distance,BigDecimal weight){}
    private record Kind(String purpose,String mime){}
    private record Shape(int count,double logBytes,Map<Kind,Integer> histogram,Integer deliverables){}
    private static Shape shape(TaskEstimateFeatures f){
        double bytes=0;var histogram=new HashMap<Kind,Integer>();
        for(var file:f.files()){if(file.byteLength()<0)throw new IllegalArgumentException("Negative input size");bytes+=file.byteLength();histogram.merge(new Kind(file.purpose(),file.contentMimeType()),1,Integer::sum);}
        return new Shape(f.files().size(),Math.log1p(bytes),histogram,f.requestedDeliverables());
    }
    public static Scales trainingScales(Iterable<TaskEstimateFeatures> training){
        var c=new DoubleSummaryStatistics();var b=new DoubleSummaryStatistics();var d=new DoubleSummaryStatistics();
        for(var item:training){var s=shape(item);c.accept(s.count);b.accept(s.logBytes);if(s.deliverables!=null)d.accept(s.deliverables);}
        if(c.getCount()==0)throw new IllegalArgumentException("No training samples");
        return new Scales(range(c),range(b),range(d));
    }
    private static double range(DoubleSummaryStatistics s){return s.getCount()==0?0:s.getMax()-s.getMin();}
    private static double scaled(double a,double b,double scale){return a==b?0:scale==0?Double.POSITIVE_INFINITY:Math.abs(a-b)/scale;}
    private final double[] queryComponents;
    private final double queryNorm;
    private final float[] sampleComponents; // owned only by this streaming search
    private double cosine(TaskEstimateFeatures sample){
        double bb=0,ab=0;
        sample.copyComponents(sampleComponents);
        for(int i=0;i<queryComponents.length;i++){double y=sampleComponents[i];bb+=y*y;ab+=queryComponents[i]*y;}
        return 1-Math.clamp(ab/(queryNorm*Math.sqrt(bb)),-1d,1d);
    }
    private record Ranked(String id,long revision,double distance) {}
    private static final Comparator<Ranked> ORDER=Comparator.comparingDouble(Ranked::distance).thenComparing(Ranked::id);
    private final TaskEstimateFeatures query;private final Shape q;private final Parameters p;
    private final PriorityQueue<Ranked> best=new PriorityQueue<>(ORDER.reversed());private String last="";private long considered;
    public TaskNeighborSearch(TaskEstimateFeatures query,Parameters p){
        this.query=query;this.q=shape(query);this.p=Objects.requireNonNull(p);
        queryComponents=new double[query.vector().size()];sampleComponents=new float[queryComponents.length];double aa=0;
        for(int i=0;i<queryComponents.length;i++){double x=query.component(i);queryComponents[i]=x;aa+=x*x;}
        queryNorm=Math.sqrt(aa);
    }
    /** Caller provides one current revision per sample in strictly increasing keyset order, across all pages. */
    public void accept(String id,long revision,TaskEstimateFeatures sample){
        if(last.compareTo(id)>=0||revision<1)throw new IllegalArgumentException("Unstable history cursor");last=id;
        if(!query.embeddingIdentity().equals(sample.embeddingIdentity())||!query.featureVersion().equals(sample.featureVersion())||!query.templateVersion().equals(sample.templateVersion()))
            throw new IllegalArgumentException("Different feature generation");
        considered++;var s=shape(sample);long overlap=0;for(var entry:q.histogram.entrySet())overlap+=Math.min(entry.getValue(),s.histogram.getOrDefault(entry.getKey(),0));
        long n=(long)q.count+s.count;double types=n==0?0:1-(2d*overlap/n);
        double deliver=q.deliverables==null&&s.deliverables==null?p.bothMissing:q.deliverables==null||s.deliverables==null?p.oneMissing:scaled(q.deliverables,s.deliverables,p.scales.deliverables);
        double files=p.files*scaled(q.count,s.count,p.scales.fileCount), bytes=p.bytes*scaled(q.logBytes,s.logBytes,p.scales.logBytes);
        double kinds=p.types*types, outputs=p.deliverables*deliver;
        // Each distance term is nonnegative. An individual term strictly outside the calibrated
        // radius cannot be rescued by cosine. Keep the exact original sum order for all other rows.
        if(files>p.maxDistance || bytes>p.maxDistance || kinds>p.maxDistance || outputs>p.maxDistance)return;
        double distance=p.text*cosine(sample)+files+bytes+kinds+outputs;
        if(distance>p.maxDistance)return;
        var hit=new Ranked(id,revision,distance);if(best.size()<p.neighbors)best.add(hit);else if(ORDER.compare(hit,best.element())<0){best.remove();best.add(hit);}
    }
    public List<Hit> selected(){
        if(considered<p.minimum)throw new MeasuredTaskRepricing.Unavailable("NO_BASELINE");
        if(best.size()<p.minimum)throw new MeasuredTaskRepricing.Unavailable("OUT_OF_DISTRIBUTION");
        // Weight never participates in ordering; preserve DECIMAL128 only for returned neighbors.
        var bandwidth=BigDecimal.valueOf(p.bandwidth);
        return best.stream().sorted(ORDER).map(r->new Hit(r.id,r.revision,r.distance,
                bandwidth.divide(bandwidth.add(BigDecimal.valueOf(r.distance)),java.math.MathContext.DECIMAL128))).toList();
    }
}
