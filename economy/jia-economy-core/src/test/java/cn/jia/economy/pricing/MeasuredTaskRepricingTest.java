package cn.jia.economy.pricing;

import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import static cn.jia.economy.pricing.MeasuredTaskRepricing.*;
import static cn.jia.economy.pricing.ModelTokenPrice.Category.INPUT;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic reference snapshots, not benchmark samples or provider prices. */
class MeasuredTaskRepricingTest {
    private final TokenRate tokens=new TokenRate("disjoint-v1",new ModelTokenPrice("route","model","tier","v1","synthetic",Map.of(INPUT,BigDecimal.ONE),100L,Map.of(INPUT,BigDecimal.TEN)));
    private final TaskQuoteCalculator.ToolKey image=new TaskQuoteCalculator.ToolKey("gateway","image","frame");
    private final UnitRate frames=new UnitRate(image,new TaskQuoteCalculator.ToolPrice("v1","synthetic",new BigDecimal("0.015")));
    private Fact fact(String op,String kind,String component,Quantity q){return new Fact(new Operation("gateway",op),kind,component,"fixture-proof",q);}
    @Test void requestTiersRemainIndependentAndDuplicateEvidenceIsNotAnExtraOperation(){
        var book=new Reference("ref-1",Map.of("text",Map.of("inference",tokens),"render",Map.of("frames",frames)));
        var small=fact("r1","text","inference",new Tokens("disjoint-v1",Map.of(INPUT,60L)));var second=fact("r2","text","inference",small.quantity());
        var large=fact("r3","text","inference",new Tokens("disjoint-v1",Map.of(INPUT,110L)));var draw=fact("i1","render","frames",new Units(image,new BigDecimal("2")));
        var result=reprice(book,List.of(small,second,large,draw,draw));assertEquals(4,result.lines().size());assertEquals(0,new BigDecimal("0.031220").compareTo(result.yuan()));
        assertEquals(4L,CnyAmounts.quoteWen(result.yuan()));assertEquals(frames,result.lines().getFirst().price());assertEquals(result,reprice(book,List.of(draw,large,second,small)));
    }
    @Test void noFallbackWhenEvidenceOrAnExclusiveBasisIsMissing(){
        var book=new Reference("ref",Map.of("image",Map.of("render",frames)));
        var good=fact("i","image","render",new Units(image,BigDecimal.ONE));var doubleCharge=fact("i","image","image-tokens",new Tokens("disjoint-v1",Map.of(INPUT,1L)));
        for(var invalid:List.of(List.<Fact>of(),List.of(good,doubleCharge),List.of(good,fact("i","image","render",new Units(image,BigDecimal.TEN))),List.of(fact("i","image","render",Missing.MISSING)),List.of(fact("i","image","render",Zero.EXPLICIT_ZERO))))assertThrows(Unavailable.class,()->reprice(book,invalid));
        var declared=new Reference("ref",Map.of("combined",Map.of("render",frames,"text",tokens),"free",Map.of("contract",new ExplicitZero("zero-v1"))));
        var render=fact("both","combined","render",good.quantity());assertThrows(Unavailable.class,()->reprice(declared,List.of(render)));
        assertEquals(3,reprice(declared,List.of(render,fact("both","combined","text",new Tokens("disjoint-v1",Map.of(INPUT,10L))),fact("z","free","contract",Zero.EXPLICIT_ZERO))).lines().size());
    }
}
