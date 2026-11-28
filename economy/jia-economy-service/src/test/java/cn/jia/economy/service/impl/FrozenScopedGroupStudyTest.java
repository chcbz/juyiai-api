package cn.jia.economy.service.impl;

import cn.jia.economy.pricing.*;
import cn.jia.economy.service.EconomyScope;
import cn.jia.economy.task.*;
import cn.jia.economy.usage.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic offline fixtures only: never production accuracy or actual provider usage evidence. */
class FrozenScopedGroupStudyTest {
    final ObjectMapper json=new ObjectMapper();
    final Map<String,String> objects=new HashMap<>();
    final EconomyScope domain=new EconomyScope("test","client");
    final String hash="a".repeat(64);
    final FrozenScopedGroupStudy study=new FrozenScopedGroupStudy();
    final CostRouteReference resource=new CostRouteReference("MODEL_RESPONSE","INFERENCE","provider","summary-model","basis");
    String keep(Object value){var f=FrozenScopedGroupStudy.freeze(value);objects.put(f.sha256(),f.json());return f.sha256();}
    String keep(TaskCalibrationObservations.Frozen f){objects.put(f.sha256(),f.json());return f.sha256();}
    record Source(String manifest,String observation) {}
    Source source(String task,ScopedBenchmarkManifest.Purpose purpose,long start,long end,String capability,int support,long tokens){
        var slots="summary-report".equals(capability)?List.of(new GroupMeanPricing.ScopeSlot(0,"text.summary","1",hash,List.of()),
                new GroupMeanPricing.ScopeSlot(1,"document.report","1","b".repeat(64),List.of(0))):List.of(new GroupMeanPricing.ScopeSlot(0,capability,"1",hash,List.of()));
        var buckets=slots.stream().map(slot->"small").toList();
        var input=new GroupMeanPricing.ScopeInput(slots,buckets,hash);
        var policy=new GroupMeanPricing.ScopedPolicy("features",input.specShas(),input.scopeShapeSha(),"strategy",GroupMeanPricing.ScopedPolicy.definitionsSha(input.specShas()),support);
        var config=Map.<String,Object>of("measurement",Map.of("routeRefs",List.of(resource.canonical())),"routes",List.of("synthetic-fixture-only"));
        var group=new GroupMeanPricing.ScopeGroup("features",policy.sha(),slots,buckets,config);
        var t=new TaskBillingService.Task(domain,"owner",task);
        var manifest=new ScopedBenchmarkManifest(ScopedBenchmarkManifest.SCHEMA,t,task,"agent","synthetic approval","synthetic completion",purpose,
                new ScopedTaskQuoteSnapshot.ScopePin(1,hash,hash,hash),policy,slots,buckets,config,group.key());
        String m=keep(manifest);var review=new BenchmarkSampleEvidence.Review("benchmark-review-v1",task,m,hash,hash,"ACCEPTED","synthetic reviewer","synthetic artifact");String r=keep(review);
        var fact=new MeasuredTaskRepricing.Fact(new MeasuredTaskRepricing.Operation("provider",task),resource.kind(),resource.component(),hash,
                new MeasuredTaskRepricing.Tokens("basis",Map.of(ModelTokenPrice.Category.INPUT,tokens)));
        var op=new ConfirmedSampleEvidence.Operation(1,hash,1,hash,"1",fact);
        String id="sample-"+task;var observation=new FrozenScopedBenchmarkObservation(FrozenScopedBenchmarkObservation.SCHEMA,
                new TaskHistoryCatalog.Revision(id,1),new BenchmarkSampleEvidence.Pin(task,m,hash,r),start,end,objects.get(m),objects.get(r),
                new ConfirmedSampleEvidence.Assessment(id,1,hash,1,ConfirmedSampleEvidence.Verdict.MATCHED),
                List.of(new FrozenScopedBenchmarkObservation.Row(resource,ProjectedTaskOperation.from(op,json))));
        return new Source(m,keep(observation));
    }
    record Fixture(String plan,Source train,Source held) {}
    Fixture fixture(String capability,String rate,long trainTokens,long heldTokens){
        var train=source("train",ScopedBenchmarkManifest.Purpose.TRAIN,10,20,capability,1,trainTokens);
        var held=source("held",ScopedBenchmarkManifest.Purpose.HOLDOUT,30,40,capability,1,heldTokens);
        var pricing=new ModelTokenPrice("channel","reference","standard","v1","basis",Map.of(ModelTokenPrice.Category.INPUT,new BigDecimal(rate)),null,Map.of());
        var book=new MeasuredTaskRepricing.ResourceReference("v1",Map.of(resource,new MeasuredTaskRepricing.TokenRate("basis",pricing)));
        String ref=keep(FrozenScopedGroupStudy.freezeReference(book));var m=TaskBenchmarkAdmissionService.decodeScoped(objects.get(train.manifest()),train.manifest());
        String plan=keep(new FrozenScopedGroupStudy.Plan("scoped-group-plan-v1",domain,"operator",ref,GroupTariffSnapshot.capture(book).tariffSha(),m.groupSha(),
                List.of(new FrozenScopedGroupStudy.Training(train.observation(),"train-template")),List.of(new FrozenScopedGroupStudy.Held(held.manifest(),"held-template",30))));
        return new Fixture(plan,train,held);
    }
    String forecast(Fixture f){return keep(study.forecast(domain,"operator",f.plan(),objects::get));}
    String outcomes(String forecast,List<FrozenScopedGroupStudy.Outcome> rows){return keep(new FrozenScopedGroupStudy.Outcomes("scoped-group-outcomes-v1",forecast,rows));}
    @Test void summaryEditAndCompositeCapabilityUseTheSameExactWholeTaskMean(){
        for(String skill:List.of("text.summary","document.edit","summary-report")){
            objects.clear();var f=fixture(skill,"1",1000000,2000000);String predicted=forecast(f);
            var prediction=json.readValue(objects.get(predicted),FrozenScopedGroupStudy.Forecast.class);assertEquals(100,prediction.predictions().getFirst().wen());
            String outcomes=outcomes(predicted,List.of(new FrozenScopedGroupStudy.Outcome(f.held().manifest(),f.held().observation())));
            var report=json.readValue(study.compare(domain,"operator",f.plan(),predicted,outcomes,objects::get).json(),FrozenScopedGroupStudy.Report.class);
            assertEquals(1,report.eligible());assertEquals(1,report.measured());assertEquals(0,new BigDecimal("2").compareTo(report.actualSum()));
            assertEquals(0,BigDecimal.ONE.compareTo(report.absoluteErrorSum()));assertEquals(1,report.errors().underestimates());
            assertEquals(0,report.errors().wape().compareTo(new TaskEstimateEvaluation.Ratio(BigDecimal.ONE,new BigDecimal("2"))));
        }
    }
    @Test void decimalTariffNeverRoundsThroughBinary64AndZeroHasNoFabricatedRatio(){
        var f=fixture("text.summary","0.0100000000000000000000000000001",1000000,1000000);
        assertEquals(2,json.readValue(objects.get(forecast(f)),FrozenScopedGroupStudy.Forecast.class).predictions().getFirst().wen());
        objects.clear();f=fixture("text.summary","0",1000000,1000000);String forecast=forecast(f);
        var report=json.readValue(study.compare(domain,"operator",f.plan(),forecast,outcomes(forecast,List.of(new FrozenScopedGroupStudy.Outcome(f.held().manifest(),f.held().observation()))),objects::get).json(),FrozenScopedGroupStudy.Report.class);
        assertNull(report.errors().wape());assertNull(report.errors().p90Relative());assertEquals(0,report.absoluteErrorSum().signum());
    }
    @Test void incompleteDuplicateChangedAndWrongDomainEvidenceIsNotAStudy(){
        var f=fixture("text.summary","1",1000000,2000000);String forecast=forecast(f);
        assertThrows(IllegalArgumentException.class,()->study.forecast(new EconomyScope("foreign","client"),"operator",f.plan(),objects::get));
        assertThrows(IllegalArgumentException.class,()->study.forecast(domain,"other",f.plan(),objects::get));
        assertThrows(IllegalArgumentException.class,()->study.compare(domain,"operator",f.plan(),forecast,outcomes(forecast,List.of()),objects::get));
        var row=new FrozenScopedGroupStudy.Outcome(f.held().manifest(),f.held().observation());
        assertThrows(IllegalArgumentException.class,()->study.compare(domain,"operator",f.plan(),forecast,outcomes(forecast,List.of(row,row)),objects::get));
        assertThrows(IllegalArgumentException.class,()->study.compare(domain,"operator",f.plan(),forecast,outcomes(forecast,List.of(new FrozenScopedGroupStudy.Outcome(f.held().manifest(),f.train().observation()))),objects::get));
        objects.put(f.train().observation(),"{}");assertThrows(IllegalArgumentException.class,()->study.forecast(domain,"operator",f.plan(),objects::get));
    }
    @Test void familyOrTemporalLeakAndTrainingDuplicationAreRejected(){
        var f=fixture("text.summary","1",1000000,2000000);var p=json.readValue(objects.get(f.plan()),FrozenScopedGroupStudy.Plan.class);
        for(var held:List.of(new FrozenScopedGroupStudy.Held(f.held().manifest(),"train-template",30),new FrozenScopedGroupStudy.Held(f.held().manifest(),"other",20))){
            String changed=keep(new FrozenScopedGroupStudy.Plan(p.schema(),p.scope(),p.operator(),p.referenceSha(),p.tariffSha(),p.groupSha(),p.train(),List.of(held)));
            assertThrows(IllegalArgumentException.class,()->study.forecast(domain,"operator",changed,objects::get));
        }
        String changed=keep(new FrozenScopedGroupStudy.Plan(p.schema(),p.scope(),p.operator(),p.referenceSha(),p.tariffSha(),p.groupSha(),List.of(p.train().getFirst(),p.train().getFirst()),p.held()));
        assertThrows(IllegalArgumentException.class,()->study.forecast(domain,"operator",changed,objects::get));
    }
    @Test void scopedBookRejectsDuplicateResourceAndPreservesExactUnitCounts(){
        var unit=new TaskQuoteCalculator.ToolKey("channel","tool","seconds");var price=new TaskQuoteCalculator.ToolPrice("v1","unit",BigDecimal.ONE);
        var ref=new CostRouteReference("TOOL","TIME","provider","tool","seconds");
        var book=new MeasuredTaskRepricing.ResourceReference("v1",Map.of(ref,new MeasuredTaskRepricing.UnitRate(unit,price)));
        var wire=json.readValue(TaskTariffBook.encodeScoped(book),TaskTariffBook.ScopedBook.class);
        assertEquals(book,TaskTariffBook.scoped(wire));
        assertThrows(IllegalArgumentException.class,()->TaskTariffBook.scoped(new TaskTariffBook.ScopedBook(wire.version(),List.of(wire.rates().getFirst(),wire.rates().getFirst()))));
        var quantity=new BigDecimal("123456789.123456789123456789");
        var fact=new MeasuredTaskRepricing.Fact(new MeasuredTaskRepricing.Operation("provider","call"),ref.kind(),ref.component(),hash,new MeasuredTaskRepricing.Units(unit,quantity));
        var projection=ProjectedTaskOperation.from(new ConfirmedSampleEvidence.Operation(1,hash,1,hash,"1",fact),json);
        assertEquals(fact,json.readValue(json.writeValueAsString(projection),ProjectedTaskOperation.class).fact(json));
    }
    @TempDir Path dir;
    @Test void originalOfflineCommandSupportsScopedForecastAndCompareWithoutDatabase() throws Exception {
        var f=fixture("text.summary","1",1000000,2000000);String forecast=forecast(f);
        String result=outcomes(forecast,List.of(new FrozenScopedGroupStudy.Outcome(f.held().manifest(),f.held().observation())));
        Files.setPosixFilePermissions(dir,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        for(var e:objects.entrySet())Files.writeString(dir.resolve(e.getKey()),e.getValue());
        assertEquals(forecast,GroupBudgetStudyCommand.run("scoped-forecast",dir.toString(),domain.tenantId(),domain.clientId(),"operator",f.plan()));
        String report=GroupBudgetStudyCommand.run("scoped-compare",dir.toString(),domain.tenantId(),domain.clientId(),"operator",f.plan(),forecast,result);
        assertEquals(study.compare(domain,"operator",f.plan(),forecast,result,objects::get).json(),Files.readString(dir.resolve(report)));
    }
}
