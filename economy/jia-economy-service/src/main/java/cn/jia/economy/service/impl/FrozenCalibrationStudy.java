package cn.jia.economy.service.impl;

import cn.jia.economy.pricing.MeasuredTaskRepricing;
import cn.jia.economy.pricing.TaskEstimateCalibration;
import cn.jia.economy.pricing.TaskEstimateEvaluation;
import cn.jia.economy.pricing.TaskEstimateEvaluation.Split;
import cn.jia.economy.pricing.TaskNeighborSearch;
import cn.jia.economy.service.EconomyScope;
import cn.jia.economy.task.TaskBillingService.Task;
import cn.jia.economy.task.TaskCalibrationCatalog;
import cn.jia.economy.task.TaskCalibrationObservations;
import cn.jia.economy.task.TaskHistoryCatalog;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.function.Function;
import tools.jackson.databind.ObjectMapper;

/** Offline only. The caller retains the private artifacts and independently pins inputs. No activation, DB discovery or cost authorization. */
public final class FrozenCalibrationStudy {
    private final ObjectMapper json;
    private final TaskCalibrationObservations observations;
    public FrozenCalibrationStudy(ObjectMapper json,TaskCalibrationObservations observations){this.json=json;this.observations=observations;}
    /** The approval bytes, unlike the later review and terminal, already exist before observing the outcome. */
    public record PlannedBenchmark(String caseId,String manifestSha){public PlannedBenchmark{
        if(caseId==null||caseId.isBlank()||manifestSha==null||!manifestSha.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Incomplete planned benchmark");}}
    public record Assignment(String task,String family,Split split,PlannedBenchmark benchmarkCase){public Assignment(String task,String family,Split split){this(task,family,split,null);}}
    public record Member(String task,TaskHistoryCatalog.Revision source,String observationSha256,cn.jia.economy.usage.BenchmarkSampleEvidence.Pin benchmark){
        public Member(String task,TaskHistoryCatalog.Revision source,String observationSha256){this(task,source,observationSha256,null);}}
    public record Roster(TaskEstimateCalibration.PrivateGroup group,List<Assignment> assignments){}
    public record Dataset(TaskEstimateCalibration.PrivateGroup group,List<Member> members){}
    public record Policy(String evaluatorVersion,List<TaskEstimateCalibration.Trial> trials,TaskEstimateCalibration.Gate gate,long seed){}
    // Changing scoring, temporal split, trial selection, bootstrap or wire semantics requires a new study version.
    public static final String VERSION="private-scoped-study-v4";
    public record Artifact(String sha256,String json){@Override public String toString(){return "Artifact[private]";}}
    public record Pins(String split,String dataset,String reference,String policy){}
    /** Verified TRAIN observations only. A private commitment, not permission to quote. */
    public record TrainingBenchmarks(TaskEstimateCalibration.PrivateGroup owner,Pins inputs,List<Member> members){
        public TrainingBenchmarks{members=List.copyOf(members);}
    }
    public record Result(TaskCalibrationCatalog.Receipt receipt,TaskEstimateCalibration.EvaluationReport report,Artifact trainingBenchmarks){}
    private record Rate(String kind,String component,String type,String body){}
    private record Book(String version,List<Rate> rates){}
    public Artifact freeze(Object value){String body=json.writeValueAsString(value);return new Artifact(hash(body),body);}
    public Artifact freezeReference(MeasuredTaskRepricing.Reference book){
        var rows=new ArrayList<Rate>();new TreeMap<>(book.byKind()).forEach((k,components)->new TreeMap<>(components).forEach((c,r)->{
            String type=switch(r){case MeasuredTaskRepricing.TokenRate ignored->"token";case MeasuredTaskRepricing.UnitRate ignored->"unit";case MeasuredTaskRepricing.ExplicitZero ignored->"zero";};
            rows.add(new Rate(k,c,type,json.writeValueAsString(r)));}));return freeze(new Book(book.version(),rows));
    }
    /** Offline collector only: freeze every operation in the server-resolved contract in one catalog snapshot. */
    public Artifact freezeContractReference(Task original,cn.jia.economy.task.TaskBudgetEstimator.Terms terms,cn.jia.economy.pricing.TaskReferencePricing prices){
        if(terms.routes().isEmpty())throw new IllegalArgumentException("Missing evaluation price contract");
        return freezeReference(prices.freeze(original.scope(),terms.execution().routeVersion(),terms.routes(),terms.routes().keySet()));
    }
    // Read original resolved LONGTEXT, not MySQL JSON_EXTRACT (which can normalize decimals).
    private void verifyQuoteRates(String snapshot,MeasuredTaskRepricing.Reference book){
        if(snapshot==null)throw new IllegalArgumentException("Missing original measured quote");
        var exact=json.reader().with(tools.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        var root=exact.readTree(snapshot);var contributions=root.at("/preparation/estimate/contributions");
        if(!"MEASURED_TASK_QUOTE_V1".equals(root.path("schema").asString())||!contributions.isArray()||contributions.isEmpty())throw new IllegalArgumentException("Missing measured contributions");
        for(var contribution:contributions){
            var repricing=contribution.path("repricing");var lines=repricing.path("lines");
            if(!book.version().equals(repricing.path("referenceVersion").asString())||!lines.isArray()||lines.isEmpty())throw new IllegalArgumentException("Missing quoted price lines");
            for(var line:lines){
                var fact=line.path("fact");var rates=book.byKind().get(fact.path("kind").asString());
                var rate=rates==null?null:rates.get(fact.path("component").asString());
                if(rate==null||!sameRate(line.path("price"),exact.readTree(json.writeValueAsString(rate))))throw new IllegalArgumentException("Quoted rate differs from evaluation book");
            }
        }
    }
    /** Current sealed rates contain only objects, scalars and category maps. Compare decimal values, not double/scale or key order. */
    private static boolean sameRate(tools.jackson.databind.JsonNode a,tools.jackson.databind.JsonNode b){
        if(a.isNumber()&&b.isNumber())return a.decimalValue().compareTo(b.decimalValue())==0;
        if(a.isObject()&&b.isObject()){
            if(a.size()!=b.size())return false;
            for(var field:b.properties())if(!sameRate(a.path(field.getKey()),field.getValue()))return false;
            return true;
        }
        return a.equals(b);
    }
    /** Private input pinned before confirmation/outcome, from the published forecast and the evaluation book. */
    public record OfferedHoldout(Task original,String family,String groupSha,String featureSha,String quoteId,String agentId,long wen,String referenceSha){
        @Override public String toString(){return "OfferedHoldout[private]";}}
    /** Offline join, after transport cutoff and before opening outcomes. Replay is corroboration, never a replacement for an observed reply. */
    public Artifact publishedOffer(cn.jia.economy.task.TaskQuoteBatches.ObservationKey key,cn.jia.economy.task.TaskQuoteBatches.Intent intent,
            cn.jia.economy.task.TaskQuoteBatches.Result observed,String agent,String family,TaskHistoryCatalog.Group group,Artifact priceBook,
            cn.jia.economy.task.TaskQuoteBatches batches,cn.jia.economy.mapper.EconomyTaskBillingMapper quotes){
        if(observed==null||!observed.equals(batches.observed(key,intent)))throw new IllegalArgumentException("Observed forecast differs from original batch");
        var item=observed.quotes().stream().filter(i->i.agentId().equals(agent)).findFirst().orElseThrow();
        if(item.state()!=cn.jia.economy.task.TaskQuoteBatches.State.AVAILABLE)throw new IllegalArgumentException("No observed price");
        var t=key.task();var q=quotes.findQuote(t.scope().tenantId(),t.scope().clientId(),t.ownerJiacn(),t.taskId(),item.quoteId());
        if(q==null||!key.payer().equals(q.getPayerUserId())||q.getAmountWen()!=item.amount().minorUnits())throw new IllegalArgumentException("Wrong published quote");
        var root=json.readTree(q.getSnapshotJson());var feature=root.path("featureSha256").asString();
        if(feature==null||!feature.matches("[0-9a-f]{64}")||!group.digest().equals(root.path("historyGroupSha256").asString())
                ||!agent.equals(root.at("/binding/agentId").asString())||!item.recommendationId().equals(root.at("/binding/recommendationId").asString()))
            throw new IllegalArgumentException("Wrong published binding");
        var book=reference(read(k->priceBook.json(),priceBook.sha256(),Book.class));
        if(!group.execution().routeVersion().equals(book.version()))throw new IllegalArgumentException("Wrong reference version");
        verifyQuoteRates(q.getSnapshotJson(),book);
        return freeze(new OfferedHoldout(t,family,group.digest(),feature,q.getQuoteId(),agent,q.getAmountWen(),priceBook.sha256()));
    }
    public record ConfirmedPoint(TaskEstimateEvaluation.Prediction point,TaskCalibrationObservations.Frozen source) {
        @Override public String toString(){return "ConfirmedPoint[private]";}}
    /** Single compatible confirmed case, NOT the eligible population or a fallback for missing whole-task truth. */
    public ConfirmedPoint confirmedPoint(Task original,TaskHistoryCatalog.Group group,TaskHistoryCatalog.Revision revision,
            String forecastSha,Function<String,String> objects,cn.jia.economy.mapper.EconomyTaskBillingMapper quotes){
        var offer=read(objects,forecastSha,OfferedHoldout.class);
        if(!original.equals(offer.original())||!group.digest().equals(offer.groupSha()))throw new IllegalArgumentException("Wrong holdout task or group");
        var book=reference(read(objects,offer.referenceSha(),Book.class));
        if(!group.execution().routeVersion().equals(book.version()))throw new IllegalArgumentException("Wrong reference version");
        var bound=observations.freezeConfirmedQuote(original,group,revision,offer.family(),Split.HOLDOUT,offer.quoteId(),offer.agentId());
        if(bound.amountWen()!=offer.wen())throw new IllegalArgumentException("Forecast differs from immutable quote");
        var quote=quotes.findQuote(original.scope().tenantId(),original.scope().clientId(),original.ownerJiacn(),original.taskId(),bound.quoteId());
        if(quote==null)throw new IllegalArgumentException("Missing confirmed quote");
        verifyQuoteRates(quote.getSnapshotJson(),book);
        var frozen=bound.observation();var sample=observations.replay(original,group,revision,offer.family(),Split.HOLDOUT,frozen.sha256(),frozen.json());
        if(!sample.features().digest().equals(offer.featureSha()))throw new IllegalArgumentException("Wrong forecast features");
        var actual=MeasuredTaskRepricing.reprice(book,sample.facts());
        return new ConfirmedPoint(new TaskEstimateEvaluation.Prediction(original.taskId(),actual.yuan(),offer.wen()),frozen);
    }
    /** Evidence absent is distinct from a pricing error or an invalid study binding. Never score it as zero. */
    public static final class MissingOutcome extends RuntimeException {
        MissingOutcome(cn.jia.economy.usage.ConfirmedSampleEvidence.Verdict reason){super(reason.name());}
    }
    /** Independent of READY/history selection. Scope and original offer bind the outcome, not its realized path. */
    public ConfirmedPoint outcomePoint(Task original,TaskHistoryCatalog.Group group,String forecastSha,Function<String,String> objects,
            cn.jia.economy.mapper.EconomyTaskBillingMapper quotes,cn.jia.economy.usage.ConfirmedSampleEvidence evidence,
            cn.jia.economy.usage.ConfirmedSampleEvidence.Decoder decoder){
        var offer=read(objects,forecastSha,OfferedHoldout.class);
        if(!original.equals(offer.original())||!group.digest().equals(offer.groupSha()))throw new IllegalArgumentException("Wrong outcome scope");
        var quote=quotes.findQuote(original.scope().tenantId(),original.scope().clientId(),original.ownerJiacn(),original.taskId(),offer.quoteId());
        if(quote==null||quote.getAmountWen()!=offer.wen())throw new IllegalArgumentException("Wrong outcome quote");
        var book=reference(read(objects,offer.referenceSha(),Book.class));
        if(!book.version().equals(group.execution().routeVersion()))throw new IllegalArgumentException("Wrong reference version");
        verifyQuoteRates(quote.getSnapshotJson(),book);
        var measured=evidence.measureOutcome(original,offer.quoteId(),offer.agentId(),offer.groupSha(),offer.featureSha(),decoder);
        if(measured==null)throw new IllegalArgumentException("Unbound outcome");
        if(measured.assessment().verdict()!=cn.jia.economy.usage.ConfirmedSampleEvidence.Verdict.MATCHED)
            throw new MissingOutcome(measured.assessment().verdict());
        var actual=MeasuredTaskRepricing.reprice(book,measured.operations().stream().map(op->op.fact()).toList());
        var artifact=freeze(Map.of("schema","confirmed-holdout-outcome-v1","forecastSha256",forecastSha,"assessment",measured.assessment(),
                "operations",measured.operations().stream().map(op->ProjectedTaskOperation.from(op,json)).toList()));
        return new ConfirmedPoint(new TaskEstimateEvaluation.Prediction(original.taskId(),actual.yuan(),offer.wen()),
                new TaskCalibrationObservations.Frozen(artifact.sha256(),artifact.json()));
    }
    private MeasuredTaskRepricing.Reference reference(Book book){
        var rates=new HashMap<String,Map<String,MeasuredTaskRepricing.Rate>>();
        for(var r:book.rates()){
            MeasuredTaskRepricing.Rate parsed=switch(r.type()){
                case "token"->json.readValue(r.body(),MeasuredTaskRepricing.TokenRate.class);
                case "unit"->json.readValue(r.body(),MeasuredTaskRepricing.UnitRate.class);
                case "zero"->json.readValue(r.body(),MeasuredTaskRepricing.ExplicitZero.class);
                default->throw new IllegalArgumentException("Unknown study rate");};
            if(rates.computeIfAbsent(r.kind(),unused->new HashMap<>()).putIfAbsent(r.component(),parsed)!=null)throw new IllegalArgumentException("Duplicate study price");
        }return new MeasuredTaskRepricing.Reference(book.version(),rates);
    }
    private static String hash(String s){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private <T>T read(Function<String,String> objects,String pin,Class<T> type){var s=objects.apply(pin);
        if(s==null||!hash(s).equals(pin))throw new IllegalArgumentException("Missing or changed study artifact");return json.readValue(s,type);}
    /** Roster and policy are fixed before observing outcomes. A missing observation fails the study, never reduces its denominator. */
    public Result evaluate(TaskEstimateCalibration.PrivateGroup owner,TaskHistoryCatalog.Group group,Pins pins,Function<String,String> objects){
        var split=read(objects,pins.split(),Roster.class);var data=read(objects,pins.dataset(),Dataset.class);var policy=read(objects,pins.policy(),Policy.class);
        var book=reference(read(objects,pins.reference(),Book.class));
        if(!owner.equals(split.group())||!owner.equals(data.group())||!owner.compatibilitySha256().equals(group.digest())
                ||!group.execution().routeVersion().equals(book.version())||!VERSION.equals(policy.evaluatorVersion()))throw new IllegalArgumentException("Wrong study inputs");
        var byTask=new HashMap<String,Member>();for(var m:data.members())if(byTask.putIfAbsent(m.task(),m)!=null)throw new IllegalArgumentException("Duplicate dataset task");
        var samples=new ArrayList<TaskEstimateCalibration.Sample>();var scope=new EconomyScope(owner.tenantId(),owner.clientId());
        var training=new TreeMap<String,Member>();
        for(var a:split.assignments()){
            var m=byTask.remove(a.task());if(m==null)throw new IllegalArgumentException("Missing or repeated assigned task");
            var pin=m.benchmark();var planned=a.benchmarkCase();
            if(planned==null?pin!=null:pin==null||!planned.caseId().equals(pin.caseId())||!planned.manifestSha().equals(pin.manifestSha()))
                throw new IllegalArgumentException("Unplanned study source");
            String bytes=objects.apply(m.observationSha256());if(bytes==null)throw new IllegalArgumentException("Missing assigned observation");
            var task=new Task(scope,owner.ownerJiacn(),a.task());
            samples.add(m.benchmark()==null?observations.replay(task,group,m.source(),a.family(),a.split(),m.observationSha256(),bytes):
                    FrozenBenchmarkObservation.replay(json,task,group,m.source(),m.benchmark(),a.family(),a.split(),m.observationSha256(),bytes));
            if(pin!=null && a.split()==Split.TRAIN)training.put(a.task(),m);
        }if(!byTask.isEmpty())throw new IllegalArgumentException("Unassigned dataset member");
        var report=TaskEstimateCalibration.evaluateWithIntervals(owner,book,samples,policy.trials(),policy.gate(),policy.seed());
        var eligible=freeze(new TrainingBenchmarks(owner,pins,List.copyOf(training.values())));
        var receipt=new TaskCalibrationCatalog.Receipt(TaskNeighborSearch.VERSION,pins.dataset(),pins.split(),pins.reference(),pins.policy(),eligible.sha256(),policy.gate(),report.points());
        return new Result(receipt,report,eligible);
    }
}
