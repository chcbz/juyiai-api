package cn.jia.economy.service.impl;

import cn.jia.economy.pricing.CostPricingRoute;

import cn.jia.economy.mapper.GroupBaselineMaintenanceMapper;
import cn.jia.economy.pricing.*;
import cn.jia.economy.service.EconomyScope;
import cn.jia.economy.task.TaskBudgetEstimator;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Short consistent read before the offline sample loop. No catalog lock is held during repricing. */
@Service
public class GroupTariffReader {
    private final TaskReferencePricing prices;
    private final GroupBaselineMaintenanceMapper maintenance;
    public GroupTariffReader(TaskReferencePricing prices,GroupBaselineMaintenanceMapper maintenance){this.prices=prices;this.maintenance=maintenance;}
    @Transactional(readOnly=true,propagation=Propagation.REQUIRES_NEW,isolation=Isolation.REPEATABLE_READ)
    public GroupTariffSnapshot.Scoped freezeScopedBook(EconomyScope domain,long expectedEpoch,String version,Map<CostRouteReference,CostPricingRoute> routes){
        if(!Objects.equals(maintenance.readDomainEpoch(domain),expectedEpoch))throw new TaskBudgetEstimator.Stale();
        return GroupTariffSnapshot.capture(prices.freezeScoped(domain,version,routes));
    }
    @Transactional(readOnly=true,propagation=Propagation.REQUIRES_NEW,isolation=Isolation.REPEATABLE_READ)
    public GroupTariffSnapshot freezeGroupBook(EconomyScope domain,long expectedEpoch,String version,Map<String,Map<String,CostPricingRoute>> routes) {
        if(!Objects.equals(maintenance.readDomainEpoch(domain),expectedEpoch))throw new TaskBudgetEstimator.Stale();
        return GroupTariffSnapshot.capture(prices.freeze(domain,version,routes,routes.keySet()));
    }
}
