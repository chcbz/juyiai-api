package cn.jia.economy.service.impl;

import cn.jia.economy.pricing.MeasuredTaskRepricing;
import cn.jia.economy.pricing.TaskEstimateCalibration;
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
    public record Assignment(String task,String family,Split split,String benchmarkCase){public Assignment(String task,String family,Split split){this(task,family,split,null);}}
    public record Member(String task,TaskHistoryCatalog.Revision source,String observationSha256,cn.jia.economy.usage.BenchmarkSampleEvidence.Pin benchmark){
        public Member(String task,TaskHistoryCatalog.Revision source,String observationSha256){this(task,source,observationSha256,null);}}
    public record Roster(TaskEstimateCalibration.PrivateGroup group,List<Assignment> assignments){}
    public record Dataset(TaskEstimateCalibration.PrivateGroup group,List<Member> members){}
    public record Policy(String evaluatorVersion,List<TaskEstimateCalibration.Trial> trials,TaskEstimateCalibration.Gate gate,long seed){}
    // Changing scoring, temporal split, trial selection, bootstrap or wire semantics requires a new study version.
    public static final String VERSION="private-scoped-study-v2";
    public record Artifact(String sha256,String json){@Override public String toString(){return "Artifact[private]";}}
    public record Pins(String split,String dataset,String reference,String policy){}
    public record Result(TaskCalibrationCatalog.Receipt receipt,TaskEstimateCalibration.EvaluationReport report){}
    private record Rate(String kind,String component,String type,String body){}
    private record Book(String version,List<Rate> rates){}
    public Artifact freeze(Object value){String body=json.writeValueAsString(value);return new Artifact(hash(body),body);}
    public Artifact freezeReference(MeasuredTaskRepricing.Reference book){
        var rows=new ArrayList<Rate>();new TreeMap<>(book.byKind()).forEach((k,components)->new TreeMap<>(components).forEach((c,r)->{
            String type=switch(r){case MeasuredTaskRepricing.TokenRate ignored->"token";case MeasuredTaskRepricing.UnitRate ignored->"unit";case MeasuredTaskRepricing.ExplicitZero ignored->"zero";};
            rows.add(new Rate(k,c,type,json.writeValueAsString(r)));}));return freeze(new Book(book.version(),rows));
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
        for(var a:split.assignments()){
            var m=byTask.remove(a.task());if(m==null)throw new IllegalArgumentException("Missing or repeated assigned task");
            String bytes=objects.apply(m.observationSha256());if(bytes==null)throw new IllegalArgumentException("Missing assigned observation");
            var task=new Task(scope,owner.ownerJiacn(),a.task());
            if(!Objects.equals(a.benchmarkCase(),m.benchmark()==null?null:m.benchmark().caseId()))throw new IllegalArgumentException("Unplanned study source");
            samples.add(m.benchmark()==null?observations.replay(task,group,m.source(),a.family(),a.split(),m.observationSha256(),bytes):
                    FrozenBenchmarkObservation.replay(json,task,group,m.source(),m.benchmark(),a.family(),a.split(),m.observationSha256(),bytes));
        }if(!byTask.isEmpty())throw new IllegalArgumentException("Unassigned dataset member");
        var report=TaskEstimateCalibration.evaluateWithIntervals(owner,book,samples,policy.trials(),policy.gate(),policy.seed());
        var receipt=new TaskCalibrationCatalog.Receipt(TaskNeighborSearch.VERSION,pins.dataset(),pins.split(),pins.reference(),pins.policy(),policy.gate(),report.points());
        return new Result(receipt,report);
    }
}
