package cn.jia.economy.service.impl;

import cn.jia.economy.entity.EconomyModelPriceRow;
import cn.jia.economy.entity.EconomyTaskModelTariffRow;
import cn.jia.economy.entity.EconomyToolPriceRow;
import cn.jia.economy.mapper.EconomyPriceCatalogMapper;
import cn.jia.economy.pricing.*;
import cn.jia.economy.service.EconomyScope;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Map;
import static cn.jia.economy.pricing.TaskPricingService.Unavailable.Reason.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DatabaseTaskPricingServiceTest {
    private final EconomyScope scope = new EconomyScope("tenant", "client");
    private final EconomyPriceCatalogMapper mapper = mock(EconomyPriceCatalogMapper.class);
    private final DatabaseTaskPricingService service = new DatabaseTaskPricingService(mapper, new ObjectMapper());
    private final TaskWorkload work = new TaskWorkload("arbitrary-kind", 100, 0, 2, Map.of());
    private static final String DATA = """
            {"basis":"synthetic","yuanPerMillionTokens":{"INPUT":"0.668790123456789","OUTPUT":"3.343950"},
             "shortContextMaxInputTokens":100,"longContextYuanPerMillionTokens":{"INPUT":"1.337580","OUTPUT":"5.015925"}}
            """;
    private EconomyModelPriceRow price(String data) {
        var row = new EconomyModelPriceRow();
        row.setChannel("channel"); row.setModel("model"); row.setTier("standard"); row.setPriceVersion("v1"); row.setPriceDataJson(data);
        when(mapper.findCurrentModel("tenant", "client", "channel", "model", "standard")).thenReturn(row);
        return row;
    }
    private void tariff(String tools) {
        var row = new EconomyTaskModelTariffRow(); row.setChannel("channel"); row.setTier("standard"); row.setToolsJson(tools);
        when(mapper.findTaskTariff("tenant", "client", "model")).thenReturn(row);
    }
    private static String tools(String units) {
        return "[{\"channel\":\"tool-channel\",\"tool\":\"render\",\"unit\":\"image\",\"unitsPerDeliverable\":\"" + units + "\"}]";
    }
    @Test void offlineStudyJoinsTheEntirePinnedRosterAndRepricesOnlyFromRetainedBytes(){
        var json=new ObjectMapper();var db=mock(cn.jia.economy.mapper.ConfirmedSampleMapper.class);
        var obs=new TaskHistoryCatalogService(db,json);var study=new FrozenCalibrationStudy(json,obs);
        var features=new TaskEstimateFeatures(TaskEstimateFeatures.VERSION,"template","r","i","t",new TaskEstimateFeatures.Identity("fixture","test",1024),java.util.Collections.nCopies(1024,1f),java.util.List.of(),null);
        var execution=new cn.jia.economy.task.TaskBillingService.MeasuredGroup("v1","1".repeat(64),"2".repeat(64),"book","3".repeat(64),"capture");
        var group=cn.jia.economy.task.TaskHistoryCatalog.Group.of(execution,features);var owner=new TaskEstimateCalibration.PrivateGroup("tenant","client","owner",group.digest());
        var objects=new java.util.HashMap<String,String>();java.util.function.Function<FrozenCalibrationStudy.Artifact,String> keep=a->{objects.put(a.sha256(),a.json());return a.sha256();};
        var assignments=java.util.Arrays.stream(TaskEstimateEvaluation.Split.values()).map(s->new FrozenCalibrationStudy.Assignment(s.name(),"family-"+s.name(),s)).toList();
        var roster=keep.apply(study.freeze(new FrozenCalibrationStudy.Roster(owner,assignments)));
        var gate=new TaskEstimateCalibration.Gate(1,BigDecimal.ONE);var trial=new TaskEstimateCalibration.Trial("fixture",1,1,1,1,1,0.1,0.2,1,1,1,1);
        var policy=keep.apply(study.freeze(new FrozenCalibrationStudy.Policy(FrozenCalibrationStudy.VERSION,java.util.List.of(trial),gate,123L)));
        price(DATA);var ref=service.freeze(scope,"book",Map.of("text",Map.of("model",new TaskReferencePricing.TokenRoute("channel","model","standard","disjoint"))),java.util.Set.of("text"));
        var reference=keep.apply(study.freezeReference(ref));var members=new java.util.ArrayList<FrozenCalibrationStudy.Member>();
        long t=1;for(var a:assignments){var task=new cn.jia.economy.task.TaskBillingService.Task(scope,"owner",a.task());var revision=new cn.jia.economy.task.TaskHistoryCatalog.Revision("sid-"+a.task(),1);
            var op=new ProjectedTaskOperation(1,"capture",1,"record","source","f".repeat(64),new MeasuredTaskRepricing.Operation("gw",a.task()),"text","model","TOKEN",json.valueToTree(new MeasuredTaskRepricing.Tokens("disjoint",Map.of(ModelTokenPrice.Category.INPUT,1000000L))));
            when(db.selectedForCalibration(task,group.digest(),revision.sampleId(),1)).thenReturn(new cn.jia.economy.mapper.ConfirmedSampleMapper.CalibrationRow(t,"r","i",features.digest(),json.writeValueAsString(features),json.writeValueAsString(execution),json.writeValueAsString(java.util.List.of(op))));
            when(db.observedByMillis()).thenReturn(t+1);t+=2;
            var f=obs.freezeConfirmed(task,group,revision,a.family(),a.split());objects.put(f.sha256(),f.json());members.add(new FrozenCalibrationStudy.Member(a.task(),revision,f.sha256()));}
        var dataset=keep.apply(study.freeze(new FrozenCalibrationStudy.Dataset(owner,members)));var pins=new FrozenCalibrationStudy.Pins(roster,dataset,reference,policy);
        clearInvocations(db,mapper);var result=study.evaluate(owner,group,pins,objects::get);assertTrue(result.report().points().meetsPointCriteria());assertEquals(1,result.report().points().holdout().quoted());
        assertEquals(123L,result.report().uncertainty().seed());assertEquals(reference,result.receipt().referenceBookSha256());assertEquals(dataset,result.receipt().datasetSha256());
        assertEquals(result,study.evaluate(owner,group,pins,objects::get));verifyNoInteractions(db,mapper);
        var shortSet=keep.apply(study.freeze(new FrozenCalibrationStudy.Dataset(owner,members.subList(0,2))));
        assertThrows(IllegalArgumentException.class,()->study.evaluate(owner,group,new FrozenCalibrationStudy.Pins(roster,shortSet,reference,policy),objects::get));
        for(String sha:java.util.List.of(roster,dataset,reference,policy,members.getLast().observationSha256())){
            var bytes=objects.get(sha);objects.put(sha,bytes+" ");assertThrows(IllegalArgumentException.class,()->study.evaluate(owner,group,pins,objects::get));objects.put(sha,bytes);}
    }
    @Test void frozenMixedAndBenchmarkOnlyStudiesRejectReplacementApprovalsAndForeignOwners(){
        var json=new ObjectMapper();var db=mock(cn.jia.economy.mapper.ConfirmedSampleMapper.class);
        var observations=new TaskHistoryCatalogService(db,json);var study=new FrozenCalibrationStudy(json,observations);
        var objects=new java.util.HashMap<String,String>();java.util.function.Function<Object,String> keep=value->{var artifact=study.freeze(value);objects.put(artifact.sha256(),artifact.json());return artifact.sha256();};
        var features=new TaskEstimateFeatures(TaskEstimateFeatures.VERSION,"template","r","i","t",new TaskEstimateFeatures.Identity("fixture","test",1024),java.util.Collections.nCopies(1024,1f),java.util.List.of(),null);
        var execution=new cn.jia.economy.task.TaskBillingService.MeasuredGroup("v1","1".repeat(64),"2".repeat(64),"rates","3".repeat(64),"cp");
        var group=cn.jia.economy.task.TaskHistoryCatalog.Group.of(execution,features);var owner=new TaskEstimateCalibration.PrivateGroup("tenant","client","owner",group.digest());
        var gate=new TaskEstimateCalibration.Gate(1,BigDecimal.ONE);var policy=keep.apply(new FrozenCalibrationStudy.Policy(FrozenCalibrationStudy.VERSION,java.util.List.of(new TaskEstimateCalibration.Trial("fixed",1,1,1,1,1,0.1,0.2,1,1,1,1)),gate,71));
        var unit=new TaskQuoteCalculator.ToolKey("local","render","frame");var book=study.freezeReference(new MeasuredTaskRepricing.Reference("rates",Map.of("render",Map.of("frames",new MeasuredTaskRepricing.UnitRate(unit,new TaskQuoteCalculator.ToolPrice("p","reference",BigDecimal.ONE))))));objects.put(book.sha256(),book.json());
        // No result facts in a predeclared assignment. Fix both rosters and exact approvals first.
        var coldPlan=new java.util.ArrayList<FrozenCalibrationStudy.Assignment>();var mixedPlan=new java.util.ArrayList<FrozenCalibrationStudy.Assignment>();
        for(var split:TaskEstimateEvaluation.Split.values()){
            var task=new cn.jia.economy.task.TaskBillingService.Task(scope,"owner",split.name());var id="case-"+split;
            var m=new cn.jia.economy.task.TaskBenchmarkAdmission.Manifest("benchmark-case-v1",task,id,"agent","fixture-approval","inspect the complete artifact",execution,features);
            var a=new FrozenCalibrationStudy.Assignment(task.taskId(),"family-"+split,split,new FrozenCalibrationStudy.PlannedBenchmark(id,keep.apply(m)));coldPlan.add(a);
            mixedPlan.add(split==TaskEstimateEvaluation.Split.CALIBRATE?new FrozenCalibrationStudy.Assignment(a.task(),a.family(),split):a);
        }
        String coldRoster=keep.apply(new FrozenCalibrationStudy.Roster(owner,coldPlan)),mixedRoster=keep.apply(new FrozenCalibrationStudy.Roster(owner,mixedPlan));
        var cold=new java.util.ArrayList<FrozenCalibrationStudy.Member>();var mixed=new java.util.ArrayList<FrozenCalibrationStudy.Member>();long t=1;
        for(var a:coldPlan){var planned=a.benchmarkCase();String manifest=planned.manifestSha(),fact="fact-"+a.task();
            var rev=new cn.jia.economy.task.TaskHistoryCatalog.Revision(manifest,2);var when=new TaskEstimateEvaluation.Case(a.task(),a.family(),t,t+1,a.split());
            var review=new cn.jia.economy.usage.BenchmarkSampleEvidence.Review("benchmark-review-v1",planned.caseId(),manifest,fact,"7".repeat(64),"ACCEPTED","fixture reviewer","fixture output");String reviewSha=keep.apply(review);
            var pin=new cn.jia.economy.usage.BenchmarkSampleEvidence.Pin(planned.caseId(),manifest,fact,reviewSha);
            var op=new ProjectedTaskOperation(1,"capture",1,"record","source","f".repeat(64),new MeasuredTaskRepricing.Operation("render",a.task()),"render","frames","UNIT",json.valueToTree(Map.of("unit",unit,"decimalCount",a.split()==TaskEstimateEvaluation.Split.HOLDOUT?"10":"1")));
            var frozen=new FrozenBenchmarkObservation("benchmark-observation-v1",when,rev,objects.get(manifest),objects.get(reviewSha),java.util.List.of(op)).seal(json);
            objects.put(frozen.sha256(),frozen.json());var member=new FrozenCalibrationStudy.Member(a.task(),rev,frozen.sha256(),pin);cold.add(member);
            if(a.split()!=TaskEstimateEvaluation.Split.CALIBRATE)mixed.add(member);
            else{var task=new cn.jia.economy.task.TaskBillingService.Task(scope,"owner",a.task());var selected=new cn.jia.economy.task.TaskHistoryCatalog.Revision("confirmed",3);
                when(db.selectedForCalibration(task,group.digest(),selected.sampleId(),3)).thenReturn(new cn.jia.economy.mapper.ConfirmedSampleMapper.CalibrationRow(t,"r","i",features.digest(),json.writeValueAsString(features),json.writeValueAsString(execution),json.writeValueAsString(java.util.List.of(op))));
                when(db.observedByMillis()).thenReturn(t+1);var f=observations.freezeConfirmed(task,group,selected,a.family(),a.split());objects.put(f.sha256(),f.json());mixed.add(new FrozenCalibrationStudy.Member(a.task(),selected,f.sha256()));}
            t+=2;
        }
        clearInvocations(db,mapper);
        String coldData=keep.apply(new FrozenCalibrationStudy.Dataset(owner,cold)),mixedData=keep.apply(new FrozenCalibrationStudy.Dataset(owner,mixed));
        java.util.function.BiFunction<String,String,FrozenCalibrationStudy.Result> score=(r,d)->study.evaluate(owner,group,new FrozenCalibrationStudy.Pins(r,d,book.sha256(),policy),objects::get);
        var all=score.apply(coldRoster,coldData);var joined=score.apply(mixedRoster,mixedData);assertEquals(all.report(),joined.report());assertEquals(joined,score.apply(mixedRoster,mixedData));
        var exported=json.readValue(joined.trainingBenchmarks().json(),FrozenCalibrationStudy.TrainingBenchmarks.class);
        assertEquals(owner,exported.owner());assertEquals(mixedData,exported.inputs().dataset());
        assertEquals(java.util.List.of(mixed.stream().filter(m->m.task().equals("TRAIN")).findFirst().orElseThrow()),exported.members());
        assertEquals(study.freeze(exported).sha256(),joined.receipt().trainingBenchmarksSha256());
        assertNotEquals(all.receipt().trainingBenchmarksSha256(),joined.receipt().trainingBenchmarksSha256());
        assertEquals("fixed",joined.report().points().winner().trial());assertFalse(joined.report().points().meetsPointCriteria());assertEquals(1,joined.report().points().holdout().underestimates());
        assertEquals(0,joined.report().points().holdout().wape().compareTo(new TaskEstimateEvaluation.Ratio(BigDecimal.valueOf(9),BigDecimal.TEN)));
        assertThrows(IllegalArgumentException.class,()->score.apply(mixedRoster,coldData));assertThrows(IllegalArgumentException.class,()->score.apply(coldRoster,mixedData));
        var foreign=new TaskEstimateCalibration.PrivateGroup("tenant","client","other-owner",group.digest());
        var foreignPins=new FrozenCalibrationStudy.Pins(keep.apply(new FrozenCalibrationStudy.Roster(foreign,coldPlan)),keep.apply(new FrozenCalibrationStudy.Dataset(foreign,cold)),book.sha256(),policy);
        assertThrows(IllegalArgumentException.class,()->study.evaluate(foreign,group,foreignPins,objects::get));
        var substituted=new java.util.ArrayList<>(coldPlan);var head=coldPlan.getFirst();substituted.set(0,new FrozenCalibrationStudy.Assignment(head.task(),head.family(),head.split(),new FrozenCalibrationStudy.PlannedBenchmark(head.benchmarkCase().caseId(),"0".repeat(64))));
        assertThrows(IllegalArgumentException.class,()->score.apply(keep.apply(new FrozenCalibrationStudy.Roster(owner,substituted)),coldData));verifyNoInteractions(db,mapper);
    }
    @Test void measuredSamplesResolveEachRequiredReferenceRouteOnceWithoutEstimatedUnitsOrActualBills(){
        price(DATA);var key=new TaskQuoteCalculator.ToolKey("tool-channel","render","image");
        var row=new EconomyToolPriceRow();row.setPriceVersion("units-v1");row.setPriceDataJson("{\"basis\":\"reference\",\"yuanPerUnit\":\"0.15\"}");
        when(mapper.findCurrentTool("tenant","client","tool-channel","render","image")).thenReturn(row);
        var route=new TaskReferencePricing.TokenRoute("channel","model","standard","disjoint");
        Map<String,Map<String,TaskReferencePricing.Route>> rules=Map.of("text",Map.of("model",route),"combined",Map.of("model",route,"image",new TaskReferencePricing.UnitRoute(key)));
        var book=service.freeze(scope,"contract-v1",rules,rules.keySet());verify(mapper,times(1)).findCurrentModel("tenant","client","channel","model","standard");
        verify(mapper,never()).findTaskTariff(any(),any(),any());var op=new MeasuredTaskRepricing.Operation("gw","op");
        var priced=MeasuredTaskRepricing.reprice(book,java.util.List.of(new MeasuredTaskRepricing.Fact(op,"combined","model","a",new MeasuredTaskRepricing.Tokens("disjoint",Map.of(ModelTokenPrice.Category.INPUT,101L))),new MeasuredTaskRepricing.Fact(op,"combined","image","b",new MeasuredTaskRepricing.Units(key,new BigDecimal("2")))));
        assertEquals(0,new BigDecimal("0.30013509558").compareTo(priced.yuan()));
        assertEquals(MODEL_PRICE_MISSING,assertThrows(TaskPricingService.Unavailable.class,()->service.freeze(new EconomyScope("other","client"),"contract-v1",rules,java.util.Set.of("text"))).reason());
    }
    @Test void missingModelsAndRoutesAreNotImplicitPrices() {
        assertEquals(BILLING_ROUTE_MISSING, assertThrows(TaskPricingService.Unavailable.class, () -> service.prepare(scope, work, null)).reason());
        verifyNoInteractions(mapper);
        assertEquals(BILLING_ROUTE_MISSING, assertThrows(TaskPricingService.Unavailable.class, () -> service.prepare(scope, work, "model")).reason());
        tariff("[]");
        assertEquals(MODEL_PRICE_MISSING, assertThrows(TaskPricingService.Unavailable.class, () -> service.prepare(scope, work, "model")).reason());
    }
    @Test void exactDecimalsAndWholeTaskBaseRatesArePreserved() {
        tariff("[]"); price(DATA);
        var prepared = service.prepare(scope, new TaskWorkload("any-kind", 120, 20, 1, Map.of()), "model");
        assertEquals(new BigDecimal("0.668790123456789"), prepared.modelPrice().yuanPerMillionTokens().get(ModelTokenPrice.Category.INPUT));
        assertEquals(100L, prepared.modelPrice().shortContextMaxInputTokens());
        assertTrue(prepared.modelPrice().cost(prepared.estimate().tokens()).compareTo(prepared.modelPrice().estimateCost(prepared.estimate().tokens())) > 0);
        assertEquals(1, TaskQuoteCalculator.calculate(prepared.estimate(), prepared.modelPrice(), prepared.toolPrices()).amountWen());
    }
    @Test void costReadsNeverGuessScopeChannelTierOrModel() {
        price(DATA);
        assertTrue(service.find(scope,"other-channel","model","standard").isEmpty());
        assertTrue(service.find(scope,"channel","other-model","standard").isEmpty());
        assertTrue(service.find(scope,"channel","model","batch").isEmpty());
        assertTrue(service.find(new EconomyScope("other", "client"),"channel","model","standard").isEmpty());
        assertTrue(service.find(scope,"channel"," model","standard").isEmpty());
        assertTrue(service.find(scope,null,"model","standard").isEmpty());
        assertTrue(service.findVersion(scope,"channel","model","standard",null).isEmpty());
    }
    @Test void historicalVersionDoesNotRequireCurrentPointer() {
        var row = price(DATA); reset(mapper);
        when(mapper.findModelVersion("tenant","client","channel","model","standard","v1")).thenReturn(row);
        assertEquals("v1", service.findVersion(scope,"channel","model","standard","v1").orElseThrow().version());
        assertTrue(service.find(scope,"channel","model","standard").isEmpty());
    }
    @Test void missingTokenCategoryIsNotZeroButExplicitZeroIsValid() {
        tariff("[]"); price(DATA.replace("\"INPUT\":\"0.668790123456789\",", ""));
        assertEquals(TOKEN_PRICE_MISSING, assertThrows(TaskPricingService.Unavailable.class, () -> service.prepare(scope,work,"model")).reason());
        price(DATA.replace("0.668790123456789", "0"));
        var prepared = service.prepare(scope,work,"model");
        assertEquals(0, TaskQuoteCalculator.calculate(prepared.estimate(), prepared.modelPrice(), prepared.toolPrices()).amountWen());
    }
    @Test void tariffOwnsToolQuantitiesAndMultipliesDeliverablesOnce() {
        tariff(tools("0.25")); price(DATA);
        var row = new EconomyToolPriceRow(); row.setPriceVersion("tool-v1"); row.setPriceDataJson("{\"basis\":\"synthetic\",\"yuanPerUnit\":\"0.0123456789\"}");
        when(mapper.findCurrentTool("tenant","client","tool-channel","render","image")).thenReturn(row);
        var prepared = service.prepare(scope,work,"model");
        var key = new TaskQuoteCalculator.ToolKey("tool-channel","render","image");
        assertEquals(new BigDecimal("0.50"), prepared.workload().toolUnits().get(key));
        assertEquals(prepared.workload().toolUnits(), prepared.estimate().toolUnits());
        assertEquals(new BigDecimal("0.0123456789"), prepared.toolPrices().get(key).yuanPerUnit());
    }
    @Test void nonzeroToolNeedsPriceButZeroDoesNot() {
        tariff(tools("1")); price(DATA);
        assertEquals(TOOL_PRICE_MISSING, assertThrows(TaskPricingService.Unavailable.class, () -> service.prepare(scope,work,"model")).reason());
        clearInvocations(mapper); tariff(tools("0"));
        assertTrue(service.prepare(scope,work,"model").toolPrices().isEmpty());
        verify(mapper,never()).findCurrentTool(any(),any(),any(),any(),any());
    }
    @Test void duplicateOrInvalidToolsAreConfigurationErrors() {
        price(DATA); String one = tools("1"); tariff(one.substring(0,one.length()-1)+","+one.substring(1));
        assertThrows(IllegalArgumentException.class, () -> service.prepare(scope,work,"model"));
        tariff("null"); assertThrows(IllegalArgumentException.class, () -> service.prepare(scope,work,"model"));
        tariff(tools("-1")); assertThrows(IllegalArgumentException.class, () -> service.prepare(scope,work,"model"));
    }
    @Test void corruptPriceIsNotMisreportedAsUnavailable() {
        price("{broken");
        var failure = assertThrows(RuntimeException.class, () -> service.find(scope,"channel","model","standard"));
        assertFalse(failure instanceof TaskPricingService.Unavailable);
        price(DATA.replace("INPUT", "UNKNOWN"));
        assertThrows(IllegalArgumentException.class, () -> service.find(scope,"channel","model","standard"));
    }
    @Test void newCatalogReadsDoNotMutatePreparedSnapshot() {
        tariff("[]"); price(DATA); var first = service.prepare(scope,work,"model");
        price(DATA.replace("0.668790123456789", "10")); var second = service.prepare(scope,work,"model");
        assertNotEquals(first.modelPrice(), second.modelPrice());
        assertEquals(new BigDecimal("0.668790123456789"), first.modelPrice().yuanPerMillionTokens().get(ModelTokenPrice.Category.INPUT));
        assertThrows(UnsupportedOperationException.class, () -> first.modelPrice().yuanPerMillionTokens().clear());
    }
}
