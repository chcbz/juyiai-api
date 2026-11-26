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
    @Test void matchRequiresAllPolicyDimensionsAndNeverGuessesOperation() {
        var files=GroupMeanPricing.Dimension.INPUT_FILE_COUNT;var bytes=GroupMeanPricing.Dimension.TOTAL_INPUT_BYTES;
        var b=new GroupMeanPricing.Bucket("small",java.util.Map.of(files,new GroupMeanPricing.Range(1,3),bytes,new GroupMeanPricing.Range(0,1048576)));
        var buckets=new GroupMeanPricing.Buckets("edit",List.of(b));
        var inside=java.util.Map.of(files,1L,bytes,1048575L);
        assertEquals("small",buckets.match("edit",inside).orElseThrow());
        assertTrue(buckets.match(null,inside).isEmpty());assertTrue(buckets.match("generate",inside).isEmpty());
        assertTrue(buckets.match("edit",java.util.Map.of(files,1L)).isEmpty());
        assertTrue(buckets.match("edit",java.util.Map.of(files,1L,bytes,1048576L)).isEmpty());
        assertThrows(IllegalArgumentException.class,()->new GroupMeanPricing.Buckets("edit",List.of(b,new GroupMeanPricing.Bucket("duplicate",b.ranges()))));
    }
    @Test void policyIdentityCommitsToBoundsAndAdmissionButNotDeclarationOrder(){
        var d=GroupMeanPricing.Dimension.INPUT_FILE_COUNT;
        var a=new GroupMeanPricing.Bucket("A",java.util.Map.of(d,new GroupMeanPricing.Range(0,2)));
        var b=new GroupMeanPricing.Bucket("B",java.util.Map.of(d,new GroupMeanPricing.Range(2,3)));
        var first=new GroupMeanPricing.Buckets("edit",List.of(a,b));var second=new GroupMeanPricing.Buckets("edit",List.of(b,a));
        var p=new GroupMeanPricing.Policy("f","r","c",first,3);
        assertEquals(p.sha(),new GroupMeanPricing.Policy("f","r","c",second,3).sha());assertNotEquals(p.sha(),new GroupMeanPricing.Policy("f","r","c",second,2).sha());
        assertNotEquals(p.sha(),new GroupMeanPricing.Policy("f","r","c",new GroupMeanPricing.Buckets("edit",List.of(b)),3).sha());
        var cfg=new GroupMeanPricing.Configuration("ctx","1","tool","cap",List.of(image,parent));assertTrue(cfg.supportsImage("edit"));assertFalse(cfg.supportsImage("generate"));
        assertEquals("B",p.classify("edit",java.util.Map.of(d,2L),cfg).orElseThrow().bucketId());assertTrue(p.classify(null,java.util.Map.of(d,2L),cfg).isEmpty());
    }
    @Test void specificationVectorMatchesIndependentEncoder(){
        var g=new GroupMeanPricing.Group("f1","edit","0".repeat(64),"B0","ctx","1","tool1","capture1",List.of(
            new GroupMeanPricing.Route("NATIVE_PROVIDER_CALL","edit","chan","image","base","openai-images-modalities-v1"),
            new GroupMeanPricing.Route("MODEL_RESPONSE","INFERENCE","chan","text","base","RESPONSES_TEXT_TOKENS_V1")));
        assertEquals("d7da1d2e639f6ef11e4ea2aa701e3b058af14ca20500b9558f2744e0575d6b15",GroupMeanPricing.key(g));
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
