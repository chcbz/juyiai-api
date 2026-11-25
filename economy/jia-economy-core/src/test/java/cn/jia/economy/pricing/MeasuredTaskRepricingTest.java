package cn.jia.economy.pricing;

import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import static cn.jia.economy.pricing.MeasuredTaskRepricing.*;
import static cn.jia.economy.pricing.ModelTokenPrice.Category.INPUT;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic reference snapshots, not benchmark samples or provider prices. */
class MeasuredTaskRepricingTest {
    @Test void holdoutIntervalsResampleWholeFamiliesWithoutReplacingAbstentionsOrZeroTruth(){
        var held=TaskEstimateEvaluation.Split.HOLDOUT;
        var manifest=List.of(new TaskEstimateEvaluation.Case("a","x",1,2,held),new TaskEstimateEvaluation.Case("b","x",1,2,held),
                new TaskEstimateEvaluation.Case("c","y",1,2,held));
        var rows=List.of(new TaskEstimateEvaluation.Prediction("a",BigDecimal.ONE,100L),new TaskEstimateEvaluation.Prediction("b",BigDecimal.ONE,null),
                new TaskEstimateEvaluation.Prediction("c",BigDecimal.ZERO,1L));
        var result=TaskEstimateUncertainty.estimate(manifest,rows,17);
        assertEquals(result,TaskEstimateUncertainty.estimate(manifest.reversed(),rows.reversed(),17));
        var half=new TaskEstimateEvaluation.Ratio(BigDecimal.ONE,BigDecimal.valueOf(2));
        assertEquals(0,half.compareTo(result.coverage().low()));assertEquals(0,new TaskEstimateEvaluation.Ratio(BigDecimal.ONE,BigDecimal.ONE).compareTo(result.coverage().high()));
        assertTrue(result.wape().defined()>0 && result.wape().defined()<result.draws());assertNull(result.wape().low());assertNull(result.p90Relative().high());
        var complete=List.of(rows.getFirst(),new TaskEstimateEvaluation.Prediction("b",BigDecimal.ONE,100L),
                new TaskEstimateEvaluation.Prediction("c",BigDecimal.ONE,0L));
        var bounded=TaskEstimateUncertainty.estimate(manifest,complete,17);
        var paired=TaskEstimateUncertainty.paired(Map.of("a","x","b","x","c","y"),complete.reversed(),17);
        assertEquals(2,paired.families());assertEquals(1000,paired.draws());assertEquals(bounded.wape(),paired.wape());assertEquals(bounded.p90Relative(),paired.p90Relative());
        assertThrows(IllegalArgumentException.class,()->TaskEstimateUncertainty.paired(Map.of("a","x"),complete,17));
        for(var band:List.of(bounded.wape(),bounded.p90Relative(),bounded.underestimateRate())){assertEquals(bounded.draws(),band.defined());
            assertEquals(0,band.low().numerator().signum());assertEquals(0,band.high().numerator().compareTo(band.high().denominator()));}
        var one=TaskEstimateUncertainty.estimate(manifest.subList(0,2),rows.subList(0,2),17);assertEquals(0,one.draws());assertNull(one.coverage().high());
    }
    private final TokenRate tokens=new TokenRate("disjoint-v1",new ModelTokenPrice("route","model","tier","v1","synthetic",Map.of(INPUT,BigDecimal.ONE),100L,Map.of(INPUT,BigDecimal.TEN)));
    private final TaskQuoteCalculator.ToolKey image=new TaskQuoteCalculator.ToolKey("gateway","image","frame");
    private final UnitRate frames=new UnitRate(image,new TaskQuoteCalculator.ToolPrice("v1","synthetic",new BigDecimal("0.015")));
    private Fact fact(String op,String kind,String component,Quantity q){return new Fact(new Operation("gateway",op),kind,component,"fixture-proof",q);}
    @Test void requestTiersRemainIndependentAndDuplicateEvidenceIsNotAnExtraOperation(){
        var book=new Reference("ref-1",Map.of("text",Map.of("inference",tokens),"render",Map.of("frames",frames)));
        var small=fact("r1","text","inference",new Tokens("disjoint-v1",Map.of(INPUT,60L)));var second=fact("r2","text","inference",small.quantity());
        var large=fact("r3","text","inference",new Tokens("disjoint-v1",Map.of(INPUT,110L)));var draw=fact("i1","render","frames",new Units(image,new BigDecimal("2")));
        var result=reprice(book,List.of(small,second,large,draw,draw));assertEquals(4,result.lines().size());assertEquals(0,new BigDecimal("0.031220").compareTo(result.yuan()));
        assertEquals(4L,CnyAmounts.quoteWen(result.yuan()));assertEquals(frames,result.lines().getFirst().price());assertEquals(result,reprice(book,List.of(draw,large,second,small)));
    }
    @Test void normalizedWholeTaskWeightsRoundOnlyOnceEvenJustAboveAnIntegerWen(){
        var book=new Reference("same-current-book",Map.of("render",Map.of("frames",frames)));
        var cheap=new MeasuredNeighborEstimate.Neighbor("a","7",BigDecimal.ONE,List.of(fact("i","render","frames",new Units(image,BigDecimal.ZERO))));
        var costly=new MeasuredNeighborEstimate.Neighbor("b","1",BigDecimal.valueOf(2),List.of(fact("j","render","frames",new Units(image,BigDecimal.ONE))));
        var estimate=MeasuredNeighborEstimate.aggregate(book,List.of(cheap,costly));
        assertEquals(1,estimate.wen());assertEquals(0,new BigDecimal("0.030").compareTo(estimate.weightedYuan()));assertEquals(BigDecimal.valueOf(3),estimate.totalWeight());
        assertEquals("same-current-book",estimate.contributions().getLast().repricing().referenceVersion());
        var above=new MeasuredNeighborEstimate.Neighbor("b","2",BigDecimal.valueOf(2),List.of(fact("j","render","frames",new Units(image,BigDecimal.ONE.add(BigDecimal.ONE.scaleByPowerOfTen(-50))))));
        assertEquals(2,MeasuredNeighborEstimate.aggregate(book,List.of(cheap,above)).wen());
        assertThrows(IllegalArgumentException.class,()->MeasuredNeighborEstimate.aggregate(book,List.of(above,costly)));
        assertThrows(Unavailable.class,()->MeasuredNeighborEstimate.aggregate(book,List.of()));
    }
    private static TaskEstimateFeatures features(float component,int files,Integer deliverables){
        var vector=new ArrayList<>(Collections.nCopies(1024,0f));vector.set(0,component);vector.set(1,1f-Math.abs(component));
        return new TaskEstimateFeatures(TaskEstimateFeatures.VERSION,"template","r","i","text",new TaskEstimateFeatures.Identity("test","fixture",1024),vector,
                Collections.nCopies(files,new TaskEstimateFeatures.File("INPUT","text/plain",1024,1)),deliverables);
    }
    @Test void structuralRadiusRejectionStillCountsEvidenceAndKeepsExactBoundary(){
        var q=features(1,0,2);var params=new TaskNeighborSearch.Parameters(new TaskNeighborSearch.Scales(0,0,1),1,1,1,1,1,.1,.5,2,2,1,1);
        var search=new TaskNeighborSearch(q,params);search.accept("a",1,features(1,1,2));
        assertEquals("NO_BASELINE",assertThrows(Unavailable.class,search::selected).getMessage());
        search.accept("b",1,features(1,0,4)); // finite deliverable term 2 exceeds radius 1
        assertEquals("OUT_OF_DISTRIBUTION",assertThrows(Unavailable.class,search::selected).getMessage());
        search.accept("c",2,features(1,0,3));search.accept("d",3,q);
        assertEquals(List.of(new TaskNeighborSearch.Hit("d",3,0,BigDecimal.ONE),new TaskNeighborSearch.Hit("c",2,1,new BigDecimal("0.5"))),search.selected());
    }
    @Test void fullSortOracleMatchesDensePackedSearchAtEveryPageBoundaryIncludingWeights(){
        var random=new Random(1329);var q=features(-0.25f,0,2);
        var params=new TaskNeighborSearch.Parameters(new TaskNeighborSearch.Scales(0,0,0),1,1,1,1,1,.1,.5,5,1,4,.3);
        var search=new TaskNeighborSearch(q,params);var all=new ArrayList<TaskNeighborSearch.Hit>();
        for(int n=1;n<=193;n++){
            var bytes=java.nio.ByteBuffer.allocate(4096);double aa=0,bb=0,ab=0;
            for(int i=0;i<1024;i++){float f=n%13==0?q.vector().get(i):random.nextFloat()-.5f;bytes.putFloat(f);double x=q.vector().get(i),y=f;aa+=x*x;bb+=y*y;ab+=x*y;}
            var sample=new TaskEstimateFeatures(q.featureVersion(),q.templateVersion(),q.requirementSha256(),q.inputSnapshotSha256(),q.textSha256(),q.embeddingIdentity(),TaskEstimateFeatures.vectorFromBytes(bytes.array()),q.files(),2);
            var id=String.format(Locale.ROOT,"%04d",n);search.accept(id,n,sample);
            double d=1-Math.clamp(ab/(Math.sqrt(aa)*Math.sqrt(bb)),-1d,1d);var bw=BigDecimal.valueOf(.3);
            all.add(new TaskNeighborSearch.Hit(id,n,d,bw.divide(bw.add(BigDecimal.valueOf(d)),java.math.MathContext.DECIMAL128)));
            if(n<6 || n%64==0 || n==193)assertEquals(all.stream().sorted(Comparator.comparingDouble(TaskNeighborSearch.Hit::distance).thenComparing(TaskNeighborSearch.Hit::id)).limit(5).toList(),search.selected());
        }
    }
    @Test void streamingNeighborsUseTrainingRangesUnknownIsNotZeroAndTiesDoNotDependOnPages(){
        var query=features(1,0,null);var knownZero=features(1,0,0);var orthogonal=features(0,0,null);var large=features(1,2,4);
        var scales=TaskNeighborSearch.trainingScales(List.of(knownZero,large));assertEquals(2,scales.fileCount());assertEquals(4,scales.deliverables());assertEquals(Math.log1p(2048),scales.logBytes());
        var parameters=new TaskNeighborSearch.Parameters(scales,1,1,1,1,1,0.125,0.75,2,2,1,1);
        var search=new TaskNeighborSearch(query,parameters);search.accept("a",1,knownZero);
        assertEquals("NO_BASELINE",assertThrows(Unavailable.class,search::selected).getMessage());
        search.accept("b",3,orthogonal);assertEquals("OUT_OF_DISTRIBUTION",assertThrows(Unavailable.class,search::selected).getMessage());
        search.accept("c",4,query);var initial=search.selected();assertEquals(List.of("c","a"),initial.stream().map(TaskNeighborSearch.Hit::id).toList());
        assertTrue(initial.getFirst().weight().compareTo(initial.getLast().weight())>0);assertEquals(0.125,initial.getFirst().distance());assertEquals(0.75,initial.getLast().distance());
        search.accept("d",7,query);search.accept("e",9,query);search.accept("f",11,large);
        assertEquals(List.of("c","d"),search.selected().stream().map(TaskNeighborSearch.Hit::id).toList());assertThrows(IllegalArgumentException.class,()->search.accept("f",12,query));
        var constant=TaskNeighborSearch.trainingScales(List.of(query));var fixed=new TaskNeighborSearch(query,new TaskNeighborSearch.Parameters(constant,1,1,1,1,1,0.1,0.2,1,1,999,1));
        fixed.accept("out",1,large);assertEquals("OUT_OF_DISTRIBUTION",assertThrows(Unavailable.class,fixed::selected).getMessage());
    }
    @Test void evaluationRejectsTemporalFamilyAndRosterLeaksAndNeverTreatsAbstentionAsZero(){
        var train=new TaskEstimateEvaluation.Case("train","A",1,2,TaskEstimateEvaluation.Split.TRAIN);
        var tune=new TaskEstimateEvaluation.Case("tune","B",3,4,TaskEstimateEvaluation.Split.CALIBRATE);
        var h=TaskEstimateEvaluation.Split.HOLDOUT;var cases=List.of(train,tune,new TaskEstimateEvaluation.Case("p","C",5,6,h),new TaskEstimateEvaluation.Case("z","D",5,7,h),new TaskEstimateEvaluation.Case("abstain","D",5,8,h));
        var pos=new TaskEstimateEvaluation.Prediction("p",new BigDecimal("0.20"),10L);var zero=new TaskEstimateEvaluation.Prediction("z",BigDecimal.ZERO,1L);
        var absent=new TaskEstimateEvaluation.Prediction("abstain",BigDecimal.TEN,null);var scores=TaskEstimateEvaluation.score(cases,List.of(zero,pos,absent));
        assertEquals(3,scores.eligible());assertEquals(2,scores.quoted());assertEquals(1,scores.underestimates());assertEquals(new BigDecimal("0.01"),scores.zeroAbsoluteYuanSum());
        assertEquals(0,scores.wape().compareTo(new TaskEstimateEvaluation.Ratio(new BigDecimal("0.55"),BigDecimal.ONE)));
        assertEquals(0,scores.p90Relative().compareTo(new TaskEstimateEvaluation.Ratio(new BigDecimal("0.50"),BigDecimal.ONE)));
        assertThrows(IllegalArgumentException.class,()->TaskEstimateEvaluation.score(cases,List.of(pos,zero)));
        assertThrows(IllegalArgumentException.class,()->TaskEstimateEvaluation.checkSplit(List.of(train,tune,new TaskEstimateEvaluation.Case("p","A",5,7,h))));
        assertThrows(IllegalArgumentException.class,()->TaskEstimateEvaluation.checkSplit(List.of(train,tune,new TaskEstimateEvaluation.Case("p","C",4,7,h))));
        var onlyZero=TaskEstimateEvaluation.score(List.of(train,tune,cases.get(3)),List.of(zero));assertNull(onlyZero.wape());assertNull(onlyZero.p90Relative());
    }
    @Test void calibrationChoosesOnlyOnTuneThenScoresUntouchedHoldoutWithoutRetryOrCoverageInflation(){
        var book=new Reference("book",Map.of("render",Map.of("frames",new UnitRate(image,new TaskQuoteCalculator.ToolPrice("p","fixture",BigDecimal.ONE)))));
        var privateGroup=new TaskEstimateCalibration.PrivateGroup("0","client","owner","a".repeat(64));
        var train=new TaskEstimateEvaluation.Case("tr","a",1,2,TaskEstimateEvaluation.Split.TRAIN);
        var tune=new TaskEstimateEvaluation.Case("tu","b",3,4,TaskEstimateEvaluation.Split.CALIBRATE);
        var hold=new TaskEstimateEvaluation.Case("ho","c",5,6,TaskEstimateEvaluation.Split.HOLDOUT);
        java.util.function.BiFunction<TaskEstimateEvaluation.Case,Integer,TaskEstimateCalibration.Sample> row=(c,units)->new TaskEstimateCalibration.Sample(privateGroup,c.task(),1,c,features(1,0,null),List.of(fact(c.task(),"render","frames",new Units(image,BigDecimal.valueOf(units)))));
        var fits=new TaskEstimateCalibration.Trial("supported",1,1,1,1,1,0.1,0.2,1,1,0.1,1);
        var outside=new TaskEstimateCalibration.Trial("excluded",1,1,1,1,1,0.1,0.2,1,1,0.01,1);
        var gate=new TaskEstimateCalibration.Gate(1,BigDecimal.ONE);var grid=List.of(outside,fits);
        var good=TaskEstimateCalibration.evaluate(privateGroup,book,List.of(row.apply(hold,1),row.apply(tune,1),row.apply(train,1)),grid,gate);
        var detailed=TaskEstimateCalibration.evaluateWithIntervals(privateGroup,book,List.of(row.apply(hold,1),row.apply(tune,1),row.apply(train,1)),grid,gate,17);
        assertEquals(good,detailed.points());assertEquals(1,detailed.uncertainty().families());assertNull(detailed.uncertainty().wape().high());
        assertTrue(good.meetsPointCriteria());assertEquals("supported",good.winner().trial());
        var held=row.apply(hold,1);
        for(var other:List.of(new TaskEstimateCalibration.PrivateGroup("1","client","owner","a".repeat(64)),
                new TaskEstimateCalibration.PrivateGroup("0","Client","owner","a".repeat(64)),
                new TaskEstimateCalibration.PrivateGroup("0","client","Owner","a".repeat(64)))){
            var foreign=new TaskEstimateCalibration.Sample(other,held.id(),held.revision(),held.split(),held.features(),held.facts());
            assertThrows(IllegalArgumentException.class,()->TaskEstimateCalibration.evaluateWithIntervals(privateGroup,book,
                    List.of(row.apply(train,1),row.apply(tune,1),foreign),grid,gate,17));
        }
        var drift=TaskEstimateCalibration.evaluate(privateGroup,book,List.of(row.apply(train,1),row.apply(tune,1),row.apply(hold,10)),grid,gate);
        assertEquals(good.winner(),drift.winner());assertFalse(drift.meetsPointCriteria());assertEquals(1,drift.holdout().underestimates());
        var insufficient=TaskEstimateCalibration.evaluate(privateGroup,book,List.of(row.apply(train,1),row.apply(tune,1),row.apply(hold,1)),grid,new TaskEstimateCalibration.Gate(2,BigDecimal.ONE));
        assertNull(insufficient.winner());assertEquals(1,insufficient.holdout().eligible());assertEquals(0,insufficient.holdout().quoted());assertFalse(insufficient.meetsPointCriteria());
    }
    @Test void missingNeighborPriceCountsAsAbstentionButMissingTruthCannotBeScored(){
        var group=new TaskEstimateCalibration.PrivateGroup("tenant","client","owner","a".repeat(64));
        var book=new Reference("frozen",Map.of("text",Map.of("inference",tokens)));
        var rows=new ArrayList<TaskEstimateCalibration.Sample>();
        for(var split:List.of(TaskEstimateEvaluation.Split.TRAIN,TaskEstimateEvaluation.Split.CALIBRATE,TaskEstimateEvaluation.Split.HOLDOUT)){
            for(int side=0;side<2;side++){
                String id=split.name()+side;int start=1+rows.size()*2;
                var assignment=new TaskEstimateEvaluation.Case(id,id,start,start+1,split);
                var category=side==0 && split==TaskEstimateEvaluation.Split.TRAIN?ModelTokenPrice.Category.OUTPUT:INPUT;
                rows.add(new TaskEstimateCalibration.Sample(group,id,1,assignment,features(side,0,1),
                        List.of(fact(id,"text","inference",new Tokens("disjoint-v1",Map.of(category,1000000L))))));
            }
        }
        var trials=List.of(new TaskEstimateCalibration.Trial("nearest",1,1,1,1,1,.1,.2,1,1,1,1));
        var gate=new TaskEstimateCalibration.Gate(1,new BigDecimal("0.5"));
        var report=TaskEstimateCalibration.evaluate(group,book,rows,trials,gate);
        assertTrue(report.meetsPointCriteria());
        for(var score:List.of(report.winner().calibration(),report.holdout())){
            assertEquals(2,score.eligible());assertEquals(1,score.quoted());assertEquals(0,score.wape().numerator().signum());
        }
        var held=rows.getLast();rows.set(rows.size()-1,new TaskEstimateCalibration.Sample(group,held.id(),1,held.split(),held.features(),rows.getFirst().facts()));
        assertEquals("TOKEN_PRICE_MISSING",assertThrows(Unavailable.class,()->TaskEstimateCalibration.evaluate(group,book,rows,trials,gate)).getMessage());
    }
    @Test void noFallbackWhenEvidenceOrAnExclusiveBasisIsMissing(){
        var book=new Reference("ref",Map.of("image",Map.of("render",frames)));
        var good=fact("i","image","render",new Units(image,BigDecimal.ONE));var doubleCharge=fact("i","image","image-tokens",new Tokens("disjoint-v1",Map.of(INPUT,1L)));
        for(var invalid:List.of(List.<Fact>of(),List.of(good,doubleCharge),List.of(good,fact("i","image","render",new Units(image,BigDecimal.TEN))),List.of(fact("i","image","render",Missing.MISSING)),List.of(fact("i","image","render",Zero.EXPLICIT_ZERO))))assertThrows(Unavailable.class,()->reprice(book,invalid));
        var declared=new Reference("ref",Map.of("combined",Map.of("render",frames,"text",tokens),"free",Map.of("contract",new ExplicitZero("zero-v1"))));
        var render=fact("both","combined","render",good.quantity());assertThrows(Unavailable.class,()->reprice(declared,List.of(render)));
        assertEquals(3,reprice(declared,List.of(render,fact("both","combined","text",new Tokens("disjoint-v1",Map.of(INPUT,10L))),fact("z","free","contract",Zero.EXPLICIT_ZERO))).lines().size());
    }
}
