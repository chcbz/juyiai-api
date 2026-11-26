package cn.jia.economy.service.impl;

import cn.jia.economy.mapper.GroupBaselineMaintenanceMapper;
import cn.jia.economy.pricing.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Test-only boundary: two synthetic resolved members and their book replace the upstream evidence readers.
 * The claim, cost arithmetic, domain/head locks, revision/evidence insert and commit are production code. */
public final class QuotePublicationLoadFixture {
    public static void rebuild(GroupBaselineMaintenanceMapper db, TransactionTemplate tx, String tenant,String client,
            String key,CountDownLatch claimed,CountDownLatch release) throws Exception {
        var w=db.dueFor(tenant,client).stream().filter(x->x.groupSha().equals(key)).findFirst().orElseThrow();
        var claim=UUID.randomUUID().toString();assertEquals(1,db.claim(w,claim,90));claimed.countDown();assertTrue(release.await(30,TimeUnit.SECONDS));
        var rates=new HashMap<String,Map<String,MeasuredTaskRepricing.Rate>>();var facts=new ArrayList<MeasuredTaskRepricing.Fact>();
        for(boolean image:List.of(false,true)){
            String kind=image?"NATIVE_PROVIDER_CALL":"MODEL_RESPONSE",component=image?"edit":"INFERENCE",basis=image?"openai-images-modalities-v1":"RESPONSES_TEXT_TOKENS_V1";
            var category=image?ModelTokenPrice.Category.IMAGE_OUTPUT:ModelTokenPrice.Category.INPUT;
            var rate=new ModelTokenPrice(image?"c":"chan",image?"image":"parent","base","fixture","per-million",Map.of(category,new BigDecimal(image?"0.20":"0.10")),null,Map.of());
            rates.put(kind,Map.of(component,new MeasuredTaskRepricing.TokenRate(basis,rate)));
            facts.add(new MeasuredTaskRepricing.Fact(new MeasuredTaskRepricing.Operation(kind,"op"),kind,component,key,new MeasuredTaskRepricing.Tokens(basis,Map.of(category,1_000_000L))));
        }
        var members=List.of("a".repeat(64),"b".repeat(64)).stream().map(id->new GroupBaselinePublisher.Eligible(id,1,id,id,id,id,facts)).toList();
        var pub=new GroupBaselinePublisher(db,new ObjectMapper());var book=GroupTariffSnapshot.capture(new MeasuredTaskRepricing.Reference("fixture",rates));
        var revision=pub.calculate(w,claim,book,members,2,key);assertEquals(0,new BigDecimal("0.60").compareTo(revision.total()));tx.executeWithoutResult(s->pub.publish(revision));
    }
    private QuotePublicationLoadFixture(){}
}
