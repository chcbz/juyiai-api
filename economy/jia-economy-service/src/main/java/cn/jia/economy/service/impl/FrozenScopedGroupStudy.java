package cn.jia.economy.service.impl;

import cn.jia.economy.pricing.*;
import cn.jia.economy.service.EconomyScope;
import cn.jia.economy.task.*;
import cn.jia.economy.usage.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;

/** Offline whole-scope study. Uses a separately retained prospective forecast; never imports approval,
 * changes a policy, prices a live task, queries Runtime or turns synthetic fixtures into measured evidence. */
public final class FrozenScopedGroupStudy {
    private static final ObjectMapper JSON=JsonMapper.builder(tools.jackson.core.json.JsonFactory.builder()
            .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    public record Training(String observationSha,String family) {}
    public record Held(String manifestSha,String family,long featuresAt) {}
    public record Plan(String schema,EconomyScope scope,String operator,String referenceSha,String tariffSha,
            String groupSha,List<Training> train,List<Held> held) {}
    public record Prediction(String manifestSha,long wen) {}
    public record Forecast(String schema,String planSha,String referenceSha,String tariffSha,List<Prediction> predictions) {}
    public record Outcome(String manifestSha,String observationSha) {}
    public record Outcomes(String schema,String forecastSha,List<Outcome> members) {}
    /** Errors are descriptive. Retaining these bytes is not a study approval or permission to publish. */
    public record Report(String schema,String planSha,String forecastSha,String outcomesSha,long eligible,long measured,
            BigDecimal actualSum,BigDecimal absoluteErrorSum,TaskEstimateEvaluation.Scores errors) {}
    public static TaskCalibrationObservations.Frozen freeze(Object value){String raw=JSON.writeValueAsString(value);return new TaskCalibrationObservations.Frozen(TaskTariffBook.sha(raw),raw);}
    public static TaskCalibrationObservations.Frozen freezeReference(MeasuredTaskRepricing.ResourceReference book){
        String raw=TaskTariffBook.encodeScoped(book);return new TaskCalibrationObservations.Frozen(TaskTariffBook.sha(raw),raw);
    }
    private static <T>T read(Function<String,String> objects,String pin,Class<T> type){
        require(pin!=null&&pin.matches("[a-f0-9]{64}"));String raw=objects.apply(pin);
        require(raw!=null&&pin.equals(TaskTariffBook.sha(raw)));return JSON.readValue(raw,type);
    }
    private static void require(boolean valid){if(!valid)throw new IllegalArgumentException("Invalid scoped study evidence");}
    private static ScopedBenchmarkManifest manifest(Function<String,String> objects,String pin){
        String raw=objects.apply(pin);require(raw!=null);return TaskBenchmarkAdmissionService.decodeScoped(raw,pin);
    }
    private static void group(Plan p,ScopedBenchmarkManifest m){require(p.scope().equals(m.task().scope())&&p.groupSha().equals(m.groupSha()));}
    private static FrozenScopedBenchmarkObservation observation(Function<String,String> objects,String pin){
        var o=read(objects,pin,FrozenScopedBenchmarkObservation.class);
        require(FrozenScopedBenchmarkObservation.SCHEMA.equals(o.schema())&&o.featuresAt()>0&&o.observedBy()>=o.featuresAt());
        require(o.source().revision()>0&&o.source().revision()==o.assessment().evidenceRevision()
                &&o.source().sampleId().equals(o.assessment().sampleId())&&o.pin().fact().equals(o.assessment().terminalFactId())
                &&o.assessment().verdict()==ConfirmedSampleEvidence.Verdict.MATCHED&&o.assessment().members()>0&&!o.operations().isEmpty());
        var m=TaskBenchmarkAdmissionService.decodeScoped(o.manifest(),o.pin().manifestSha());
        require(m.caseId().equals(o.pin().caseId())&&TaskTariffBook.sha(o.review()).equals(o.pin().reviewSha()));
        var r=JSON.readValue(o.review(),BenchmarkSampleEvidence.Review.class);
        require("benchmark-review-v1".equals(r.schema())&&"ACCEPTED".equals(r.decision())&&r.caseId().equals(m.caseId())
                &&r.manifestSha().equals(o.pin().manifestSha())&&r.terminalFactId().equals(o.pin().fact()));
        var members=new HashSet<Long>();
        for(var row:o.operations()){
            var op=row.operation();require(op.memberRevision()>0&&op.captureRevision()>0);
            require(op.captureId()!=null&&op.recordId()!=null&&op.sourceRevision()!=null);
            new ConfirmedSampleEvidence.ScopedOperation(row.resource(),new ConfirmedSampleEvidence.Operation(op.memberRevision(),op.captureId(),
                    op.captureRevision(),op.recordId(),op.sourceRevision(),op.fact(JSON)));
            members.add(op.memberRevision());
        }
        require(members.size()==o.assessment().members());return o;
    }
    private static BigDecimal cost(FrozenScopedBenchmarkObservation o,MeasuredTaskRepricing.ResourceReference book){
        return MeasuredTaskRepricing.reprice(book,o.operations().stream().map(r->new MeasuredTaskRepricing.ResourceFact(r.resource(),r.operation().fact(JSON))).toList()).yuan();
    }
    private static Plan plan(EconomyScope scope,String operator,String pin,Function<String,String> objects){
        var p=read(objects,pin,Plan.class);require("scoped-group-plan-v1".equals(p.schema())&&scope.equals(p.scope())
                &&operator!=null&&!operator.isBlank()&&operator.equals(p.operator())&&!p.train().isEmpty()&&!p.held().isEmpty());return p;
    }
    private static MeasuredTaskRepricing.ResourceReference book(Plan p,Function<String,String> objects){
        var b=TaskTariffBook.scoped(read(objects,p.referenceSha(),TaskTariffBook.ScopedBook.class));
        require(GroupTariffSnapshot.capture(b).tariffSha().equals(p.tariffSha()));return b;
    }
    public TaskCalibrationObservations.Frozen forecast(EconomyScope scope,String operator,String planSha,Function<String,String> objects){
        var p=plan(scope,operator,planSha,objects);var b=book(p,objects);var tasks=new HashSet<TaskBillingService.Task>();
        var trainFamilies=new HashSet<String>();var sampleIds=new HashSet<String>();BigDecimal sum=BigDecimal.ZERO;long lastTraining=0;int support=0;
        for(var row:p.train()){
            require(row.family()!=null&&!row.family().isBlank());trainFamilies.add(row.family());
            var o=observation(objects,row.observationSha());var m=TaskBenchmarkAdmissionService.decodeScoped(o.manifest(),o.pin().manifestSha());group(p,m);
            require(m.purpose()==ScopedBenchmarkManifest.Purpose.TRAIN&&tasks.add(m.task())&&sampleIds.add(o.source().sampleId()));
            support=Math.max(support,m.policy().minSupport());lastTraining=Math.max(lastTraining,o.observedBy());sum=sum.add(cost(o,b));
        }
        require(p.train().size()>=support);long wen=GroupMeanPricing.amountWen(sum,p.train().size());var predictions=new ArrayList<Prediction>();
        var heldPins=new HashSet<String>();
        for(var row:p.held()){
            var m=manifest(objects,row.manifestSha());group(p,m);
            require(m.purpose()==ScopedBenchmarkManifest.Purpose.HOLDOUT&&tasks.add(m.task())&&heldPins.add(row.manifestSha())
                    &&row.family()!=null&&!row.family().isBlank()&&!trainFamilies.contains(row.family())&&row.featuresAt()>lastTraining);
            predictions.add(new Prediction(row.manifestSha(),wen));
        }
        predictions.sort(Comparator.comparing(Prediction::manifestSha));
        return freeze(new Forecast("scoped-group-forecast-v1",planSha,p.referenceSha(),p.tariffSha(),predictions));
    }
    public TaskCalibrationObservations.Frozen compare(EconomyScope scope,String operator,String planSha,String forecastSha,String outcomesSha,Function<String,String> objects){
        // Reproduction corroborates the separately retained prospective forecast; it does not prove when it was obtained.
        require(forecast(scope,operator,planSha,objects).sha256().equals(forecastSha));
        var prediction=read(objects,forecastSha,Forecast.class);var p=plan(scope,operator,planSha,objects);var b=book(p,objects);
        var outcomes=read(objects,outcomesSha,Outcomes.class);
        require("scoped-group-outcomes-v1".equals(outcomes.schema())&&forecastSha.equals(outcomes.forecastSha()));
        var expected=new HashMap<String,Held>();for(var h:p.held())expected.put(h.manifestSha(),h);
        var amounts=new HashMap<String,Long>();for(var q:prediction.predictions())amounts.put(q.manifestSha(),q.wen());
        var points=new ArrayList<TaskEstimateEvaluation.Prediction>();var samples=new HashSet<String>();BigDecimal actual=BigDecimal.ZERO,error=BigDecimal.ZERO;
        for(var row:outcomes.members()){
            var h=expected.remove(row.manifestSha());require(h!=null);var o=observation(objects,row.observationSha());
            require(row.manifestSha().equals(o.pin().manifestSha())&&h.featuresAt()==o.featuresAt()&&samples.add(o.source().sampleId()));
            var m=TaskBenchmarkAdmissionService.decodeScoped(o.manifest(),o.pin().manifestSha());group(p,m);require(m.purpose()==ScopedBenchmarkManifest.Purpose.HOLDOUT);
            var measured=cost(o,b);long wen=amounts.get(row.manifestSha());
            points.add(new TaskEstimateEvaluation.Prediction(row.manifestSha(),measured,wen));actual=actual.add(measured);error=error.add(BigDecimal.valueOf(wen,2).subtract(measured).abs());
        }
        require(expected.isEmpty());return freeze(new Report("scoped-group-report-v1",planSha,forecastSha,outcomesSha,p.held().size(),points.size(),actual,error,TaskEstimateEvaluation.pairedErrors(points)));
    }
}
