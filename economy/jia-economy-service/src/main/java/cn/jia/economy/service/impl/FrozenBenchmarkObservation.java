package cn.jia.economy.service.impl;

import cn.jia.economy.pricing.TaskEstimateCalibration;
import cn.jia.economy.pricing.TaskEstimateEvaluation;
import cn.jia.economy.task.TaskBenchmarkAdmission;
import cn.jia.economy.task.TaskBillingService.Task;
import cn.jia.economy.task.TaskCalibrationObservations.Frozen;
import cn.jia.economy.task.TaskHistoryCatalog;
import cn.jia.economy.usage.BenchmarkSampleEvidence;
import cn.jia.economy.usage.ConfirmedSampleEvidence;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** Separate private artifact type; historical replay neither reads live evidence nor grants cross-owner use. */
record FrozenBenchmarkObservation(String schema,TaskEstimateEvaluation.Case assignment,TaskHistoryCatalog.Revision source,
        String manifest,String review,List<ProjectedTaskOperation> operations){
    @Override public String toString(){return "FrozenBenchmarkObservation[private]";}
    private static String sha(String s){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    Frozen seal(ObjectMapper json){String s=json.writeValueAsString(this);return new Frozen(sha(s),s);}
    static TaskEstimateCalibration.Sample replay(ObjectMapper json,Task t,TaskHistoryCatalog.Group group,TaskHistoryCatalog.Revision revision,
            BenchmarkSampleEvidence.Pin pin,String family,TaskEstimateEvaluation.Split split,String expected,String bytes){
        if(!sha(bytes).equals(expected))throw new IllegalArgumentException("Changed benchmark observation");
        return json.readValue(bytes,FrozenBenchmarkObservation.class).sample(json,t,group,revision,pin,family,split);
    }
    TaskEstimateCalibration.Sample sample(ObjectMapper json,Task t,TaskHistoryCatalog.Group group,TaskHistoryCatalog.Revision revision,
            BenchmarkSampleEvidence.Pin pin,String family,TaskEstimateEvaluation.Split split){
        if(!"benchmark-observation-v1".equals(schema)||!revision.equals(source)||!revision.sampleId().equals(pin.manifestSha())
                ||!sha(manifest).equals(pin.manifestSha())||!sha(review).equals(pin.reviewSha())||!assignment.task().equals(t.taskId())
                ||!family.equals(assignment.family())||split!=assignment.split()||operations.isEmpty())throw new IllegalArgumentException("Wrong benchmark study entry");
        var m=json.readValue(manifest,TaskBenchmarkAdmission.Manifest.class);var r=json.readValue(review,BenchmarkSampleEvidence.Review.class);
        if(!m.task().equals(t)||!m.caseId().equals(pin.caseId())||!pin.fact().equals(r.terminalFactId())||!m.caseId().equals(r.caseId())
                ||!"ACCEPTED".equals(r.decision())||!pin.manifestSha().equals(r.manifestSha())||!group.equals(TaskHistoryCatalog.Group.of(m.execution(),m.features())))
            throw new IllegalArgumentException("Wrong benchmark observation scope");
        return new TaskEstimateCalibration.Sample(group.privateCalibrationGroup(t),source.sampleId(),source.revision(),assignment,m.features(),operations.stream().map(o->o.fact(json)).toList());
    }
}
