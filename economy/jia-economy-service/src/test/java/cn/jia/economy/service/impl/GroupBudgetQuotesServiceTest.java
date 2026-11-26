package cn.jia.economy.service.impl;

import cn.jia.economy.mapper.GroupBaselineMapper;
import cn.jia.economy.service.EconomyScope;
import cn.jia.economy.task.*;
import org.junit.jupiter.api.Test;
import cn.jia.economy.mapper.GroupBaselineMaintenanceMapper.Member;
import cn.jia.economy.pricing.*;
import cn.jia.economy.usage.ConfirmedSampleEvidence.*;
import java.math.BigDecimal;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class GroupBudgetQuotesServiceTest {
    final EconomyScope domain=new EconomyScope("tenant","client");
    final GroupBaselineMapper mapper=mock(GroupBaselineMapper.class);
    final GroupBudgetQuotesService service=new GroupBudgetQuotesService(mapper);
    final String a="a".repeat(64), b="b".repeat(64), tariff="e".repeat(64), policy="f".repeat(64);
    final GroupBudgetQuotes.Key ka=new GroupBudgetQuotes.Key(a), kb=new GroupBudgetQuotes.Key(b);
    GroupBaselineMapper.Row row(Long revision,Long epoch) {
        return new GroupBaselineMapper.Row(a,3,4,"ACTIVE",4L,tariff,policy,revision,epoch,tariff,policy,2,new BigDecimal("0.590"));
    }
    @Test void reviewedImageUsageIncludesBothSidesPinsRevisionAndHasOrderIndependentDigest(){
        var m=new Member("tenant","client",a,policy,"owner","task","case",a,1,a,b,tariff);
        var group=new GroupMeanPricing.Group("v","edit",policy,"small","ctx","1","tool","capture",List.of(
            new GroupMeanPricing.Route("MODEL_RESPONSE","INFERENCE","local","text","t","basis"),new GroupMeanPricing.Route("NATIVE_PROVIDER_CALL","edit","remote","image","t","basis")));
        var ok=new Assessment(a,1,"accepted",1,Verdict.MATCHED);var q=new MeasuredTaskRepricing.Tokens("basis",Map.of());
        var p=new Operation(1,"capture",2,"parent","r1",new MeasuredTaskRepricing.Fact(new MeasuredTaskRepricing.Operation("text","a"),"MODEL_RESPONSE","INFERENCE",a,q));
        var c=new Operation(1,"capture",2,"child","r2",new MeasuredTaskRepricing.Fact(new MeasuredTaskRepricing.Operation("image","b"),"NATIVE_PROVIDER_CALL","edit",b,q));
        var row=GroupReviewedUsage.measured(m,"accepted",group,new Measured(ok,List.of(c,p)));
        assertEquals(2,row.facts().size());assertEquals(row,GroupReviewedUsage.measured(m,"accepted",group,new Measured(ok,List.of(p,c))));
        for(var bad:List.of(new Measured(ok,List.of(c)),new Measured(ok,List.of(p)),new Measured(new Assessment(a,2,"accepted",1,Verdict.MATCHED),List.of(p,c)),new Measured(new Assessment(a,1,"accepted",1,Verdict.USAGE_INCOMPLETE),List.of(p,c))))
            assertThrows(GroupBaselinePublisher.MoreInputRequired.class,()->GroupReviewedUsage.measured(m,"accepted",group,bad));
    }
    @Test void emptyInputDoesNotHitDatabase() {
        assertTrue(service.prepare(domain,Set.of()).isEmpty());service.lockSelected(domain,List.of());verifyNoInteractions(mapper);
    }
    @Test void mixedBatchSeparatesMissingFromMeasuredAndRechecksUsedHead() {
        when(mapper.read(domain,List.of(a,b))).thenReturn(List.of(row(4L,3L)));
        var batch=service.prepare(domain,Set.of(kb,ka));var estimate=(GroupBudgetQuotes.Mean)batch.get(ka);
        assertEquals(30L,estimate.amountWen());assertEquals(new GroupBudgetQuotes.Fixed(TaskQuoteBatches.Reason.NO_BASELINE),batch.get(kb));
        when(mapper.lock(domain,List.of(a))).thenReturn(List.of(new GroupBaselineMapper.Head(a,3,4,"ACTIVE",4L,tariff,policy)));
        service.lockSelected(domain,List.of(estimate.pin(),estimate.pin()));verify(mapper).lock(domain,List.of(a));
        when(mapper.lock(domain,List.of(a))).thenReturn(List.of(new GroupBaselineMapper.Head(a,4,5,"DIRTY",null,null,policy)));
        assertThrows(TaskBudgetEstimator.Stale.class,()->service.lockSelected(domain,List.of(estimate.pin())));
    }
    @Test void damagedPublishedRevisionIsNotAFixedPrice() {
        for(var broken:List.of(row(null,null),row(3L,3L),row(4L,2L))) {
            when(mapper.read(domain,List.of(a))).thenReturn(List.of(broken));
            assertThrows(IllegalStateException.class,()->service.prepare(domain,Set.of(ka)));
        }
    }
}
