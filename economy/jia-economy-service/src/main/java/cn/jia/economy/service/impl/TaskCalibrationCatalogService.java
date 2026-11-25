package cn.jia.economy.service.impl;

import cn.jia.economy.mapper.TaskCalibrationMapper;
import cn.jia.economy.pricing.TaskNeighborSearch;
import cn.jia.economy.task.TaskBillingService.Task;
import cn.jia.economy.task.TaskCalibrationCatalog;
import cn.jia.economy.task.TaskHistoryCatalog;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class TaskCalibrationCatalogService implements TaskCalibrationCatalog {
    private final TaskCalibrationMapper db;private final ObjectMapper json;
    public TaskCalibrationCatalogService(TaskCalibrationMapper db,ObjectMapper json){this.db=db;this.json=json;}
    @Transactional(propagation=Propagation.NEVER)
    public Active resolve(Task task,TaskHistoryCatalog.Group group){
        var digest=group.digest();var row=db.active(task,digest);if(row==null)return null;
        if(!sha(row.json()).equals(row.sha())||row.generation()<1)throw new IllegalStateException("Invalid calibration receipt commitment");
        var r=json.readValue(row.json(),Receipt.class);var report=r.report();
        if(!group.privateCalibrationGroup(task).equals(report.group())||!group.execution().routeVersion().equals(report.referenceVersion()))throw new IllegalStateException("Wrong calibration evidence scope");
        for(String commitment:java.util.List.of(r.datasetSha256(),r.splitSha256(),r.referenceBookSha256(),r.policySha256(),r.trainingBenchmarksSha256()))
            if(!commitment.matches("[0-9a-f]{64}"))throw new IllegalStateException("Missing calibration evidence identity");
        if(!TaskNeighborSearch.VERSION.equals(r.estimatorVersion())||report.winner()==null||!report.meetsPointCriteria()
                ||!r.gate().passes(report.winner().calibration())||!r.gate().passes(report.holdout()))return null;
        return new Active(new Pin(digest,row.generation(),row.sha()),report.winner().parameters(),r.referenceBookSha256());
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public boolean lockCurrent(Task task,Pin pin){return db.lockCurrent(task,pin.group(),pin.generation(),pin.receiptSha256())!=null;}
    private static String sha(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
}
