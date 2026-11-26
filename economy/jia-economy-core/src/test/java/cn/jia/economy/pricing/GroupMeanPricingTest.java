package cn.jia.economy.pricing;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GroupMeanPricingTest {
    private final GroupMeanPricing.Route parent = new GroupMeanPricing.Route("MODEL_RESPONSE","INFERENCE","c","m","t","b");
    private final GroupMeanPricing.Route image = new GroupMeanPricing.Route("NATIVE_PROVIDER_CALL","edit","c","画","t","b");
    private static String key(String ctx, GroupMeanPricing.Route... routes) {
        return GroupMeanPricing.key(new GroupMeanPricing.Group("v1","edit","a".repeat(64),"small","shared",ctx,"2","3",List.of(routes)));
    }
    @Test void canonicalKeyHasIndependentWireVector() {
        assertEquals("66cb342bcaaf40f85ad43fdd5ec534a688c489be1e4800baa8cf77f53a5fa005", key("1",image,parent));
        assertEquals(key("1",image,parent),key("1",parent,image));
        assertNotEquals(key("1",image,parent),key("2",image,parent));
        assertThrows(IllegalArgumentException.class, () -> key("1",image,new GroupMeanPricing.Route(image.kind(),image.component(),"other","m","t","b")));
        assertThrows(IllegalArgumentException.class, () -> key("1"));
    }
    @Test void roundsOnceInWenAndRejectsInvalidTotals() {
        assertEquals(30,GroupMeanPricing.amountWen(new BigDecimal("0.590"),2));
        assertEquals(2,GroupMeanPricing.amountWen(new BigDecimal("0.020000000001"),2));
        assertEquals(1,GroupMeanPricing.amountWen(new BigDecimal("0.020000000000"),2));
        assertEquals(0,GroupMeanPricing.amountWen(BigDecimal.ZERO,2));
        assertThrows(IllegalArgumentException.class, () -> GroupMeanPricing.amountWen(BigDecimal.ZERO,0));
        assertThrows(IllegalArgumentException.class, () -> GroupMeanPricing.amountWen(BigDecimal.ONE.negate(),1));
        assertThrows(ArithmeticException.class, () -> GroupMeanPricing.amountWen(BigDecimal.TEN.pow(30),1));
    }
}
