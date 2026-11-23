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
    public List<Row> page(Key key,String after){return db.projectionPage(key,after);}
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public boolean publish(Key key,long revision,Coverage state){return db.publishProjection(key,revision,state.name())==1;}
}
