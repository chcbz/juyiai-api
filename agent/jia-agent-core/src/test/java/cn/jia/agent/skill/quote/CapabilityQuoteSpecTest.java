package cn.jia.agent.skill.quote;

import cn.jia.agent.requirement.ConfirmedScope.*;
import cn.jia.agent.skill.quote.CapabilityQuoteSpec.*;
import cn.jia.core.util.CanonicalValueEncoding;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CapabilityQuoteSpecTest {
    static final String ITEMS="unit/items", BYTES="unit/bytes", WORDS="unit/words";
    static Scalar integer(String unit,String value) {return new Scalar(ValueType.INTEGER,unit,value);}
    static Field integerField(String unit,String min,String max) {return new Field(ValueType.INTEGER,unit,true,min,max,Set.of(),null);}
    static CostDimension deliverable(String key,String unit) {return new CostDimension(key,Source.DELIVERABLE,key,ValueType.INTEGER,unit,null,Set.of());}
    static Range range(String lo,String hi) {return new Range(lo,hi,Set.of());}
    static Bucket bucket(String id,String key,String lo,String hi) {return new Bucket(id,Map.of(key,range(lo,hi)));}
    static CapabilityQuoteSpec text() {
        return new CapabilityQuoteSpec(CapabilityQuoteSpec.SCHEMA,"text.summary","1",
                Map.of("deliverables.words",integerField(WORDS,"1","10000")),List.of(deliverable("words",WORDS)),
                List.of(bucket("short","words","1","1001"),bucket("long","words","1001","10001")));
    }
    static WorkItem item(String capability,Map<String,Scalar> parameters,Map<String,Scalar> deliverables,List<String> refs) {
        return new WorkItem("w1",capability,"1",parameters,deliverables,refs,List.of());
    }
    static WorkItem textItem(String words) {return item("text.summary",Map.of(),Map.of("words",integer(WORDS,words)),List.of());}
    static InputMetadata input(String ref,String mime,String version,Map<String,Scalar> values) {
        return new InputMetadata(ref,1,mime,"a".repeat(64),version,version==null?null:"parser-1",values);
    }
    static CapabilityQuoteSpec sumSpec(String capability,String unit,ValueType type) {
        var dimension=new CostDimension("size",Source.INPUT_SUM,"size",type,unit,"METADATA_V1",Set.of());
        return new CapabilityQuoteSpec(CapabilityQuoteSpec.SCHEMA,capability,"1",Map.of(),List.of(dimension),
                List.of(bucket("small","size","0","4096"),bucket("large","size","4096",null)));
    }

    @Test void exactClosedOpenBucketsIncludeBoundaryInOnlyOneBucket() {
        assertEquals("short",text().classify(textItem("1"),Map.of()).bucketId());
        assertEquals("short",text().classify(textItem("1000"),Map.of()).bucketId());
        assertEquals("long",text().classify(textItem("1001"),Map.of()).bucketId());
        assertEquals("long",text().classify(textItem("10000"),Map.of()).bucketId());
        assertThrows(IllegalArgumentException.class,()->text().classify(textItem("10001"),Map.of()));
    }
    @Test void sameMechanismSupportsTextVisualDocumentAndAudioWithoutCapabilityBranches() {
        assertEquals(MatchStatus.MATCHED,text().classify(textItem("500"),Map.of()).status());
        var visual=new CapabilityQuoteSpec(CapabilityQuoteSpec.SCHEMA,"visual.asset","1",
                Map.of("deliverables.count",integerField(ITEMS,"1","10")),List.of(deliverable("count",ITEMS)),List.of(bucket("pair","count","1","3")));
        assertEquals("pair",visual.classify(item("visual.asset",Map.of(),Map.of("count",integer(ITEMS,"2")),List.of()),Map.of()).bucketId());
        var document=sumSpec("document.extract",BYTES,ValueType.INTEGER);
        assertEquals("small",document.classify(item("document.extract",Map.of(),Map.of(),List.of("f@1")),
                Map.of("f@1",input("f@1","application/pdf","METADATA_V1",Map.of("size",integer(BYTES,"2000"))))).bucketId());
        var audio=sumSpec("audio.transcribe","unit/seconds",ValueType.DECIMAL);
        assertEquals("small",audio.classify(item("audio.transcribe",Map.of(),Map.of(),List.of("a@1")),
                Map.of("a@1",input("a@1","audio/wav","METADATA_V1",Map.of("size",new Scalar(ValueType.DECIMAL,"unit/seconds","60.125"))))).bucketId());
    }
    @Test void missingOrUnknownBusinessConstraintsAreNeverCostFallback() {
        assertThrows(IllegalArgumentException.class,()->text().classify(item("text.summary",Map.of(),Map.of(),List.of()),Map.of()));
        assertThrows(IllegalArgumentException.class,()->text().classify(item("text.summary",Map.of("undeclared",integer(ITEMS,"1")),Map.of("words",integer(WORDS,"500")),List.of()),Map.of()));
        assertThrows(IllegalArgumentException.class,()->text().classify(item("text.summary",Map.of(),Map.of("words",integer(ITEMS,"500")),List.of()),Map.of()));
        assertThrows(IllegalArgumentException.class,()->text().classify(item("other",Map.of(),Map.of("words",integer(WORDS,"500")),List.of()),Map.of()));
        assertThrows(IllegalArgumentException.class,()->text().validateWorkItem(new WorkItem("w1","text.summary","2",Map.of(),Map.of("words",integer(WORDS,"500")),List.of(),List.of())));
    }
    @Test void missingMetadataUnknownVersionAndMissingValueAreUnknownNotZero() {
        var spec=sumSpec("document.extract",BYTES,ValueType.INTEGER);var item=item("document.extract",Map.of(),Map.of(),List.of("f@1"));
        assertEquals(MatchStatus.COST_INPUT_MISSING,spec.classify(item,Map.of()).status());
        assertEquals(MatchStatus.COST_INPUT_MISSING,spec.classify(item,Map.of("f@1",input("f@1","application/pdf",null,Map.of()))).status());
        assertEquals(MatchStatus.COST_INPUT_MISSING,spec.classify(item,Map.of("f@1",input("f@1","application/pdf","METADATA_V2",Map.of("size",integer(BYTES,"0"))))).status());
        assertEquals(MatchStatus.COST_INPUT_MISSING,spec.classify(item,Map.of("f@1",input("f@1","application/pdf","METADATA_V1",Map.of()))).status());
    }
    @Test void emptyInputsAreZeroAndDuplicateReferencesCountOnce() {
        var sum=sumSpec("document.extract",BYTES,ValueType.INTEGER);
        assertEquals("0",sum.classify(item("document.extract",Map.of(),Map.of(),List.of()),Map.of()).dimensions().get("size").value());
        var count=new CostDimension("files",Source.INPUT_COUNT,null,ValueType.INTEGER,ITEMS,null,Set.of());
        var spec=new CapabilityQuoteSpec(CapabilityQuoteSpec.SCHEMA,"anything","1",Map.of(),List.of(count),List.of(bucket("any","files","0",null)));
        assertEquals("1",spec.classify(item("anything",Map.of(),Map.of(),List.of("f@1","f@1")),Map.of()).dimensions().get("files").value());
    }
    @Test void sumsEverySelectedInputAndFiltersOnlyDeclaredExactMime() {
        var dimension=new CostDimension("size",Source.INPUT_SUM,"size",ValueType.INTEGER,BYTES,"METADATA_V1",Set.of("application/pdf"));
        var spec=new CapabilityQuoteSpec(CapabilityQuoteSpec.SCHEMA,"document.extract","1",Map.of(),List.of(dimension),List.of(bucket("all","size","0",null)));
        var facts=Map.of("a@1",input("a@1","application/pdf","METADATA_V1",Map.of("size",integer(BYTES,"12"))),
                "b@1",input("b@1","application/pdf","METADATA_V1",Map.of("size",integer(BYTES,"8"))),
                "c@1",input("c@1","text/plain",null,Map.of()));
        assertEquals("20",spec.classify(item("document.extract",Map.of(),Map.of(),List.of("a@1","b@1","c@1")),facts).dimensions().get("size").value());
        var count=new CostDimension("files",Source.INPUT_COUNT,null,ValueType.INTEGER,ITEMS,null,Set.of("application/pdf"));
        var counts=new CapabilityQuoteSpec(CapabilityQuoteSpec.SCHEMA,"document.extract","1",Map.of(),List.of(count),List.of(bucket("all","files","0",null)));
        assertEquals("2",counts.classify(item("document.extract",Map.of(),Map.of(),List.of("a@1","b@1","c@1")),facts).dimensions().get("files").value());
        assertEquals(MatchStatus.COST_INPUT_MISSING,counts.classify(item("document.extract",Map.of(),Map.of(),List.of("absent@1")),facts).status());
    }
    @Test void invalidMetadataIsCorruptionEvenWhenAnotherInputIsMissing() {
        var spec=sumSpec("document.extract",BYTES,ValueType.INTEGER);var item=item("document.extract",Map.of(),Map.of(),List.of("absent@1","f@1"));
        for(Scalar invalid:List.of(integer(BYTES,"-1"),integer(ITEMS,"1"),new Scalar(ValueType.DECIMAL,BYTES,"1")))
            assertThrows(IllegalStateException.class,()->spec.classify(item,Map.of("f@1",input("f@1","application/pdf","METADATA_V1",Map.of("size",invalid)))));
        assertThrows(IllegalStateException.class,()->spec.classify(item,Map.of("f@1",input("other@1","application/pdf","METADATA_V1",Map.of("size",integer(BYTES,"1"))))));
        assertThrows(IllegalArgumentException.class,()->new InputMetadata("f@1",1,"application/pdf","a".repeat(64),null,"parser-1",Map.of()));
    }
    @Test void sumDoesNotRoundOrLosePrecisionAndRejectsDecimalOverflow() {
        var spec=sumSpec("audio.transcribe","unit/seconds",ValueType.DECIMAL);var item=item("audio.transcribe",Map.of(),Map.of(),List.of("a@1","b@1"));
        var tiny=new Scalar(ValueType.DECIMAL,"unit/seconds","0.000000000000000000000000000001");
        var facts=Map.of("a@1",input("a@1","audio/wav","METADATA_V1",Map.of("size",tiny)),"b@1",input("b@1","audio/wav","METADATA_V1",Map.of("size",tiny)));
        assertEquals("0.000000000000000000000000000002",spec.classify(item,facts).dimensions().get("size").value());
        var huge=new Scalar(ValueType.DECIMAL,"unit/seconds","9".repeat(35));
        assertThrows(IllegalStateException.class,()->spec.classify(item,Map.of("a@1",input("a@1","audio/wav","METADATA_V1",Map.of("size",huge)),
                "b@1",input("b@1","audio/wav","METADATA_V1",Map.of("size",huge)))));
    }
    @Test void outOfDistributionIsNotNearestBucketOrOmittedDimension() {
        var spec=new CapabilityQuoteSpec(CapabilityQuoteSpec.SCHEMA,"text.summary","1",text().fields(),text().costDimensions(),List.of(bucket("short","words","1","1001")));
        assertEquals(MatchStatus.OUT_OF_DISTRIBUTION,spec.classify(textItem("5000"),Map.of()).status());
        assertThrows(IllegalArgumentException.class,()->new CapabilityQuoteSpec(CapabilityQuoteSpec.SCHEMA,"text.summary","1",text().fields(),text().costDimensions(),List.of(new Bucket("bad",Map.of()))));
    }
    @Test void overlappingBucketsAreRejectedButDisjointAxisAllowsMultidimensionalRanges() {
        assertThrows(IllegalArgumentException.class,()->new CapabilityQuoteSpec(CapabilityQuoteSpec.SCHEMA,"text.summary","1",text().fields(),text().costDimensions(),
                List.of(bucket("a","words","1","1001"),bucket("b","words","1000",null))));
        var count=deliverable("count",ITEMS);var words=deliverable("words",WORDS);
        var fields=Map.of("deliverables.count",integerField(ITEMS,"1","10"),"deliverables.words",integerField(WORDS,"1","10000"));
        var a=new Bucket("a",Map.of("count",range("1","2"),"words",range("1","1001")));
        var b=new Bucket("b",Map.of("count",range("2","3"),"words",range("1","1001")));
        assertDoesNotThrow(()->new CapabilityQuoteSpec(CapabilityQuoteSpec.SCHEMA,"composite","1",fields,List.of(count,words),List.of(a,b)));
        var overlapping=new Bucket("bad",Map.of("count",range("1","3"),"words",range("500","1500")));
        assertThrows(IllegalArgumentException.class,()->new CapabilityQuoteSpec(CapabilityQuoteSpec.SCHEMA,"composite","1",fields,List.of(count,words),List.of(a,overlapping)));
    }
    @Test void enumsAndBooleansUseExactSetsAndRejectUnknownValues() {
        var field=new Field(ValueType.ENUM,"unit/code",true,null,null,Set.of("CSV","JSON"),null);
        var dimension=new CostDimension("format",Source.PARAMETER,"format",ValueType.ENUM,"unit/code",null,Set.of());
        var spec=new CapabilityQuoteSpec(CapabilityQuoteSpec.SCHEMA,"export","1",Map.of("parameters.format",field),List.of(dimension),
                List.of(new Bucket("csv",Map.of("format",new Range(null,null,Set.of("CSV")))),new Bucket("json",Map.of("format",new Range(null,null,Set.of("JSON"))))));
        assertEquals("csv",spec.classify(item("export",Map.of("format",new Scalar(ValueType.ENUM,"unit/code","CSV")),Map.of(),List.of()),Map.of()).bucketId());
        assertThrows(IllegalArgumentException.class,()->spec.validateWorkItem(item("export",Map.of("format",new Scalar(ValueType.ENUM,"unit/code","XML")),Map.of(),List.of())));
        assertThrows(IllegalArgumentException.class,()->new CapabilityQuoteSpec(CapabilityQuoteSpec.SCHEMA,"export","1",spec.fields(),spec.costDimensions(),
                List.of(new Bucket("unknown",Map.of("format",new Range(null,null,Set.of("XML")))))));
        var bool=new CostDimension("enabled",Source.PARAMETER,"enabled",ValueType.BOOLEAN,"unit/flag",null,Set.of());
        var booleanSpec=new CapabilityQuoteSpec(CapabilityQuoteSpec.SCHEMA,"flag","1",Map.of("parameters.enabled",new Field(ValueType.BOOLEAN,"unit/flag",true,null,null,Set.of(),null)),List.of(bool),
                List.of(new Bucket("yes",Map.of("enabled",new Range(null,null,Set.of("true")))),new Bucket("no",Map.of("enabled",new Range(null,null,Set.of("false"))))));
        assertEquals("no",booleanSpec.classify(item("flag",Map.of("enabled",new Scalar(ValueType.BOOLEAN,"unit/flag","false")),Map.of(),List.of()),Map.of()).bucketId());
    }
    @Test void optionalValuesRemainAbsentAndTextIsNeverACostNumber() {
        var fields=new HashMap<>(text().fields());fields.put("parameters.note",new Field(ValueType.TEXT,"unit/text",false,null,null,Set.of(),3));
        var spec=new CapabilityQuoteSpec(CapabilityQuoteSpec.SCHEMA,"text.summary","1",fields,text().costDimensions(),text().buckets());
        assertEquals(MatchStatus.MATCHED,spec.classify(textItem("500"),Map.of()).status());
        assertDoesNotThrow(()->spec.validateWorkItem(item("text.summary",Map.of("note",new Scalar(ValueType.TEXT,"unit/text","🚀🚀🚀")),Map.of("words",integer(WORDS,"500")),List.of())));
        assertThrows(IllegalArgumentException.class,()->spec.validateWorkItem(item("text.summary",Map.of("note",new Scalar(ValueType.TEXT,"unit/text","1234")),Map.of("words",integer(WORDS,"500")),List.of())));
        assertThrows(IllegalArgumentException.class,()->new CostDimension("note",Source.PARAMETER,"note",ValueType.TEXT,"unit/text",null,Set.of()));
    }
    @Test void optionalCostFieldDoesNotInventAValueOrSkipItsDimension() {
        var field=new Field(ValueType.INTEGER,ITEMS,false,"0","10",Set.of(),null);
        var dimension=new CostDimension("count",Source.PARAMETER,"count",ValueType.INTEGER,ITEMS,null,Set.of());
        var spec=new CapabilityQuoteSpec(CapabilityQuoteSpec.SCHEMA,"optional","1",Map.of("parameters.count",field),
                List.of(dimension),List.of(bucket("all","count","0",null)));
        assertEquals(MatchStatus.COST_INPUT_MISSING,spec.classify(item("optional",Map.of(),Map.of(),List.of()),Map.of()).status());
        var zero=spec.classify(item("optional",Map.of("count",integer(ITEMS,"0")),Map.of(),List.of()),Map.of());
        assertEquals(MatchStatus.MATCHED,zero.status());assertEquals("0",zero.dimensions().get("count").value());
    }

    @Test void semanticDigestIgnoresDeclarationOrderingButPinsUnitsBoundsAndVersions() {
        var reversed=new ArrayList<>(text().buckets());Collections.reverse(reversed);
        var equal=new CapabilityQuoteSpec(CapabilityQuoteSpec.SCHEMA,"text.summary","1",new TreeMap<>(text().fields()),text().costDimensions(),reversed);
        assertEquals(text().specSha(),equal.specSha());assertEquals(text().bucketDefinitionsSha(),equal.bucketDefinitionsSha());
        var changed=new CapabilityQuoteSpec(CapabilityQuoteSpec.SCHEMA,"text.summary","2",text().fields(),text().costDimensions(),text().buckets());
        assertNotEquals(text().specSha(),changed.specSha());
        var changedBounds=new CapabilityQuoteSpec(CapabilityQuoteSpec.SCHEMA,"text.summary","1",text().fields(),text().costDimensions(),List.of(bucket("short","words","1","999"),bucket("long","words","999","10001")));
        assertNotEquals(text().specSha(),changedBounds.specSha());assertNotEquals(text().bucketDefinitionsSha(),changedBounds.bucketDefinitionsSha());
    }
    @Test void immutableInputsAndResultsCannotChangePublishedSemantics() {
        var fields=new HashMap<>(text().fields());var buckets=new ArrayList<>(text().buckets());
        var spec=new CapabilityQuoteSpec(CapabilityQuoteSpec.SCHEMA,"text.summary","1",fields,text().costDimensions(),buckets);
        String sha=spec.specSha();fields.clear();buckets.clear();assertEquals(sha,spec.specSha());
        assertThrows(UnsupportedOperationException.class,()->spec.fields().clear());
        assertThrows(UnsupportedOperationException.class,()->spec.buckets().getFirst().ranges().clear());
        var result=spec.classify(textItem("500"),Map.of());assertThrows(UnsupportedOperationException.class,()->result.dimensions().clear());
        assertFalse(result.toString().contains("500"));
    }
    @Test void rejectsMismatchedDeclarationsRatherThanSelectingByNameOrGuessingUnits() {
        assertThrows(IllegalArgumentException.class,()->new CapabilityQuoteSpec("UNKNOWN","text.summary","1",text().fields(),text().costDimensions(),text().buckets()));
        assertThrows(IllegalArgumentException.class,()->new CapabilityQuoteSpec(CapabilityQuoteSpec.SCHEMA,"text.summary","1",text().fields(),List.of(deliverable("words",ITEMS)),text().buckets()));
        assertThrows(IllegalArgumentException.class,()->new CapabilityQuoteSpec(CapabilityQuoteSpec.SCHEMA,"text.summary","1",text().fields(),List.of(deliverable("words",WORDS),deliverable("words",WORDS)),text().buckets()));
        assertThrows(IllegalArgumentException.class,()->new CostDimension("size",Source.INPUT_SUM,"size",ValueType.INTEGER,BYTES,null,Set.of()));
        assertThrows(IllegalArgumentException.class,()->new CostDimension("count",Source.INPUT_COUNT,null,ValueType.INTEGER,BYTES,null,Set.of()));
        assertThrows(IllegalArgumentException.class,()->new CostDimension("size",Source.INPUT_SUM,"size",ValueType.INTEGER,BYTES,"META_V1",Set.of("image/*")));
        assertThrows(IllegalArgumentException.class,()->new CapabilityQuoteSpec(CapabilityQuoteSpec.SCHEMA,"x","1",Map.of("parameters.x",integerField(ITEMS,"-1","10")),
                List.of(new CostDimension("x",Source.PARAMETER,"x",ValueType.INTEGER,ITEMS,null,Set.of())),List.of(bucket("all","x","0",null))));
    }
    @Test void canonicalEncodingPreservesNullAndRejectsImplicitNumericOrUnicodeConversion() {
        var nullMap=new HashMap<String,Object>();nullMap.put("v",null);
        assertNotEquals(CanonicalValueEncoding.digest(nullMap),CanonicalValueEncoding.digest(Map.of("v","")));
        assertThrows(IllegalArgumentException.class,()->CanonicalValueEncoding.encode(Map.of("v",1)));
        assertThrows(IllegalArgumentException.class,()->CanonicalValueEncoding.encode(Map.of("v",true)));
        assertThrows(IllegalArgumentException.class,()->CanonicalValueEncoding.encode(Map.of(1,"v")));
        assertThrows(IllegalArgumentException.class,()->CanonicalValueEncoding.encode("bad\ud800"));
        var a=new LinkedHashMap<String,Object>();a.put("\uE000","a");a.put("\uD800\uDC00","b");
        var b=new LinkedHashMap<String,Object>();b.put("\uD800\uDC00","b");b.put("\uE000","a");
        assertEquals(CanonicalValueEncoding.digest(a),CanonicalValueEncoding.digest(b));
        assertTrue(CanonicalValueEncoding.compareUtf8("\uE000","\uD800\uDC00")<0);
    }
}
