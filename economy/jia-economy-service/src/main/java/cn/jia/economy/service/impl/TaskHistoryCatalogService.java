package cn.jia.economy.service.impl;

import cn.jia.economy.mapper.ConfirmedSampleMapper;
import cn.jia.economy.pricing.TaskEstimateFeatures;
import cn.jia.economy.task.TaskBillingService.Task;
import cn.jia.economy.task.TaskHistoryCatalog;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class TaskHistoryCatalogService implements TaskHistoryCatalog,cn.jia.economy.task.TaskCalibrationObservations {
    private final ConfirmedSampleMapper db;
    private final ObjectMapper json;
    public TaskHistoryCatalogService(ConfirmedSampleMapper db,ObjectMapper json){this.db=db;this.json=json;}
    @Transactional(propagation=Propagation.NEVER)
    public List<Entry> page(Task query,Group group,String after){
        return db.history(query,group.digest(),after,PAGE).stream().map(r->{
            var features=json.readValue(r.featuresJson(),TaskEstimateFeatures.class);
            if(!features.digest().equals(r.featureSha())||!Group.of(group.execution(),features).equals(group))
                throw new IllegalStateException("History feature commitment mismatch");
            return new Entry(new Revision(r.id(),r.revision()),r.taskId(),features);
        }).toList();
    }
    @Transactional(propagation=Propagation.NEVER)
    public List<cn.jia.economy.pricing.MeasuredTaskRepricing.Fact> quantities(Task query,Group group,Revision selected){
        var payload=db.historyQuantities(query,group.digest(),selected.sampleId(),selected.revision());if(payload==null)return null;
        return operations(payload);
    }
    private List<cn.jia.economy.pricing.MeasuredTaskRepricing.Fact> operations(String payload){
        var rows=json.readValue(payload,ProjectedTaskOperation[].class);
        if(rows.length==0)throw new IllegalStateException("Empty READY operation projection");
        return java.util.Arrays.stream(rows).map(r->r.fact(json)).toList();
    }
    @Override @Transactional(readOnly=true,propagation=Propagation.REQUIRES_NEW,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public cn.jia.economy.pricing.TaskEstimateCalibration.Sample observeConfirmed(Task original,Group group,Revision selected,String family,
            cn.jia.economy.pricing.TaskEstimateEvaluation.Split partition){
        var digest=group.digest();var row=db.selectedForCalibration(original,digest,selected.sampleId(),selected.revision());
        if(row==null)throw new cn.jia.economy.task.TaskCalibrationObservations.NotEligible();
        var features=json.readValue(row.features(),TaskEstimateFeatures.class);
        var actual=json.readValue(row.execution(),cn.jia.economy.task.TaskBillingService.MeasuredGroup.class);
        if(!group.equals(Group.of(actual,features)) || !features.digest().equals(row.featureSha())
                || !features.requirementSha256().equals(row.requirementSha()) || !features.inputSnapshotSha256().equals(row.inputSha()))
            throw new IllegalStateException("Calibration source commitment mismatch");
        var facts=operations(row.operations());var split=new cn.jia.economy.pricing.TaskEstimateEvaluation.Case(
                original.taskId(),family,row.featuresAt(),db.observedByMillis(),partition);
        return new cn.jia.economy.pricing.TaskEstimateCalibration.Sample(group.privateCalibrationGroup(original),selected.sampleId(),selected.revision(),split,features,facts);
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public boolean lockCurrent(Task query,Group group,Revision selected){
        return db.lockHistory(query,group.digest(),selected.sampleId(),selected.revision())!=null;
    }
}
