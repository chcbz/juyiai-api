package cn.jia.economy.service.impl;

import cn.jia.economy.mapper.EconomyUsageCaptureMapper;
import cn.jia.economy.usage.UsageCaptureProjection;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
@Transactional(propagation=Propagation.NEVER)
public class UsageCaptureProjectionStore implements UsageCaptureProjection {
    private final EconomyUsageCaptureMapper db;
    public UsageCaptureProjectionStore(EconomyUsageCaptureMapper db){this.db=db;}
    public Snapshot pending(Key key){return db.pendingProjection(key);}
    public boolean hasAssociated(Key key,String recordId){return db.hasProjectionHead(key,recordId);}
    public List<Row> page(Key key,String after){return db.projectionPage(key,after);}
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public boolean publish(Key key,long revision,Coverage state,DispatchGroup group){
        var binding=db.projectionBinding(key);if(binding==null)return false;
        // Same lock order as journal ingestion: registered samples before the capture, never the other way around.
        var members=binding.taskId()==null?List.<String>of():db.lockMembers(binding);
        if(db.publishProjection(key,revision,state.name())!=1)return false;
        if(group!=null)db.saveDispatchGroup(key,revision,group);
        for(String id:members)if(db.invalidateSample(id)!=1)throw new IllegalStateException("Sample revision not advanced");
        return true;
    }
    @Override @Transactional(propagation=Propagation.REQUIRES_NEW)
    public boolean publish(Key key,long revision,Coverage state){return publish(key,revision,state,null);}
}
