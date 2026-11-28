package cn.jia.economy.pricing;

import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import static cn.jia.economy.pricing.MeasuredTaskRepricing.*;
import static cn.jia.economy.pricing.ModelTokenPrice.Category.INPUT;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic reference rates, never production tariffs or accuracy evidence. */
class ScopedMeasuredTaskRepricingTest {
    private CostRouteReference ref(String model,String component){return new CostRouteReference("MODEL_RESPONSE",component,"provider",model,"tokens");}
    private TokenRate price(String model,String rate){return new TokenRate("tokens",new ModelTokenPrice("channel",model,"standard","v1","fixture",Map.of(INPUT,new BigDecimal(rate)),null,Map.of()));}
    private ResourceFact fact(CostRouteReference ref,String call,long tokens){return new ResourceFact(ref,new Fact(new Operation("actual",call),ref.kind(),ref.component(),"a".repeat(64),new Tokens("tokens",Map.of(INPUT,tokens))));}
    @Test void sameKindAndComponentRetainTwoModelRatesAndIdenticalReplayDeduplicates(){
        var a=ref("a","INFERENCE");var b=ref("b","INFERENCE");var book=new ResourceReference("v",Map.of(a,price("a","10"),b,price("b","20")));
        var first=fact(a,"call-a",1000);var second=fact(b,"call-b",1000);var r=reprice(book,List.of(second,first,first));
        assertEquals(0,new BigDecimal("0.03").compareTo(r.yuan()));assertEquals(2,r.lines().size());
        assertEquals(r,reprice(book,List.of(first,second)));
        assertThrows(Unavailable.class,()->reprice(book,List.of(first,fact(b,"call-a",1000))));
    }
    @Test void tierBoundaryIsAppliedToEachActualCallNotCombinedOrMeanTokens(){
        var ref=ref("a","INFERENCE");var rate=new TokenRate("tokens",new ModelTokenPrice("channel","a","standard","v1","fixture",Map.of(INPUT,new BigDecimal("10")),1000L,Map.of(INPUT,new BigDecimal("20"))));
        var book=new ResourceReference("v",Map.of(ref,rate));
        assertEquals(0,new BigDecimal("0.012").compareTo(reprice(book,List.of(fact(ref,"a",600),fact(ref,"b",600))).yuan()));
        assertEquals(0,new BigDecimal("0.031").compareTo(reprice(book,List.of(fact(ref,"a",900),fact(ref,"b",1100))).yuan()));
    }
    @Test void completeIndependentComponentsAreRequiredAndConflictingHeadsAreRejected(){
        var a=ref("a","INFERENCE");var fee=ref("a","REQUEST");var book=new ResourceReference("v",Map.of(a,price("a","10"),fee,new ExplicitZero("included")));
        var tokens=fact(a,"one",1000);var zero=new ResourceFact(fee,new Fact(tokens.fact().operation(),fee.kind(),fee.component(),"b".repeat(64),Zero.EXPLICIT_ZERO));
        assertThrows(Unavailable.class,()->reprice(book,List.of(tokens)));
        assertEquals(0,new BigDecimal("0.01").compareTo(reprice(book,List.of(tokens,zero)).yuan()));
        assertThrows(Unavailable.class,()->reprice(book,List.of(tokens,zero,fact(a,"one",2000))));
        var ambiguous=new CostRouteReference(a.kind(),a.component(),a.namespace(),a.modelOrTool(),"other-basis");
        assertThrows(IllegalArgumentException.class,()->new ResourceReference("v",Map.of(a,price("a","10"),ambiguous,new ExplicitZero("included"))));
        assertThrows(Unavailable.class,()->reprice(book,List.of(fact(ref("foreign","INFERENCE"),"foreign",1))));
    }
    @Test void tariffCommitsToResourceIdentityAndActualRateWithStableOrdering(){
        var a=ref("a","INFERENCE");var b=ref("b","INFERENCE");var entries=new LinkedHashMap<CostRouteReference,Rate>();entries.put(a,price("a","10"));entries.put(b,price("b","20"));
        var first=GroupTariffSnapshot.capture(new ResourceReference("v",entries));entries.clear();entries.put(b,price("b","20.00"));entries.put(a,price("a","10.0"));
        var second=GroupTariffSnapshot.capture(new ResourceReference("v",entries));assertEquals(first.tariffSha(),second.tariffSha());assertEquals(first.canonicalBase64(),second.canonicalBase64());
        entries.put(a,price("a","11"));assertNotEquals(first.tariffSha(),GroupTariffSnapshot.capture(new ResourceReference("v",entries)).tariffSha());
        entries.remove(a);entries.put(new CostRouteReference(a.kind(),a.component(),"another-provider",a.modelOrTool(),a.normalizationBasis()),price("a","10"));
        assertNotEquals(first.tariffSha(),GroupTariffSnapshot.capture(new ResourceReference("v",entries)).tariffSha());
    }
    @Test void unitAndExplicitZeroUseExistingArithmeticWithoutTokenAssumptions(){
        var unit=new TaskQuoteCalculator.ToolKey("channel","transcribe","seconds");var ref=new CostRouteReference("TOOL","TRANSCRIBE","provider","transcribe","seconds-v1");
        var measured=new ResourceFact(ref,new Fact(new Operation("actual","one"),ref.kind(),ref.component(),"a".repeat(64),new Units(unit,new BigDecimal("1.25"))));
        var book=new ResourceReference("v",Map.of(ref,new UnitRate(unit,new TaskQuoteCalculator.ToolPrice("v","fixture",new BigDecimal("0.02")))));
        assertEquals(0,new BigDecimal("0.025").compareTo(reprice(book,List.of(measured)).yuan()));
        assertThrows(Unavailable.class,()->reprice(new ResourceReference("v",Map.of(ref,new ExplicitZero("included"))),List.of(measured)));
    }
}
