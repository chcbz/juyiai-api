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
public class TaskHistoryCatalogService implements TaskHistoryCatalog {
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
    @Transactional(propagation=Propagation.MANDATORY)
    public boolean lockCurrent(Task query,Group group,Revision selected){
        return db.lockHistory(query,group.digest(),selected.sampleId(),selected.revision())!=null;
    }
}
