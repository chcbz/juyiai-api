package cn.jia.economy.service.impl;

import cn.jia.economy.mapper.TaskBenchmarkMapper;
import cn.jia.economy.pricing.TaskEstimateFeatures;
import cn.jia.economy.task.TaskBenchmarkAdmission;
import cn.jia.economy.task.TaskBillingService;
import cn.jia.economy.task.TaskHistoryCatalog;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import tools.jackson.databind.ObjectMapper;

@Service
public class TaskBenchmarkAdmissionService implements TaskBenchmarkAdmission {
    private final TaskBenchmarkMapper db;private final ObjectMapper json;
    public TaskBenchmarkAdmissionService(TaskBenchmarkMapper db,ObjectMapper json){this.db=db;this.json=json;}
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public String enroll(TaskBillingService.Task task,String id,String sha,String agent,TaskBillingService.MeasuredGroup actual,TaskEstimateFeatures features){
        var raw=db.approved(task,id,sha);if(raw==null)throw new NotAdmitted();
        try{if(!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8))).equals(sha))throw new IllegalStateException("Damaged benchmark authority");}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
        var m=json.readValue(raw,Manifest.class);
        if(!task.equals(m.task())||!id.equals(m.caseId())||!agent.equals(m.agentId())||!m.execution().equals(actual)||!m.features().equals(features))throw new NotAdmitted();
        String existing=db.existing(task,id);if(existing!=null){if(!sha.equals(existing))throw new IllegalStateException("Benchmark identity changed");return existing;}
        if(db.seed(task,id,sha,features.requirementSha256(),features.inputSnapshotSha256(),features.digest(),TaskHistoryCatalog.Group.of(actual,features).digest())!=1)throw new NotAdmitted();
        return sha;
    }
}
