package cn.jia.agent.skill;

import cn.jia.agent.skill.quote.CapabilityQuoteSpec;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CapabilityQuoteSpecJsonTest {
    private static final JsonMapper JSON=new JsonMapper();
    private static final String SPEC="""
        {"schema":"CAPABILITY_QUOTE_SPEC_V1","capabilityId":"text.summary","version":"1",
         "fields":{"deliverables.words":{"type":"INTEGER","unit":"unit/words","required":true,"min":"1","max":"10000"}},
         "costDimensions":[{"key":"size","source":"DELIVERABLE","selector":"words","type":"INTEGER","unit":"unit/words"}],
         "buckets":[{"id":"short","ranges":{"size":{"lo":"1","hi":"1001"}}},
                    {"id":"long","ranges":{"size":{"lo":"1001","hi":"10001"}}}]}
        """;
    private ObjectNode tree() throws Exception {return (ObjectNode)JSON.readTree(SPEC);}
    private void invalid(String raw) {
        assertEquals("CAPABILITY_QUOTE_SPEC_INVALID",assertThrows(IllegalArgumentException.class,()->CapabilityQuoteSpecJson.read(raw)).getMessage());
    }
    @Test void documentedWireRoundTripsAndSemanticHashDoesNotDependOnStoredJsonOrdering() throws Exception {
        var spec=CapabilityQuoteSpecJson.read(SPEC);
        assertEquals(spec,CapabilityQuoteSpecJson.read(CapabilityQuoteSpecJson.write(spec)));
        assertEquals(spec,CapabilityQuoteSpecJson.readStored(SPEC,spec.specSha()));
        assertEquals(spec,CapabilityQuoteSpecJson.readStored(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(tree()),spec.specSha()));
        assertEquals(CapabilityQuoteSpec.SCHEMA,spec.schema());
    }
    @Test void numericBoundsRemainStringsAndBooleanIsNotCoerced() {
        invalid(SPEC.replace("\"min\":\"1\"","\"min\":1"));
        invalid(SPEC.replace("\"required\":true","\"required\":\"true\""));
        invalid(SPEC.replace("\"version\":\"1\"","\"version\":1"));
        invalid(SPEC.replace("\"max\":\"10000\"","\"max\":null"));
    }
    @Test void unknownAndDuplicateSemanticFieldsAreNotSilentlyDiscarded() {
        invalid(SPEC.replace("\"version\":\"1\"","\"version\":\"1\",\"version\":\"1\""));
        invalid(SPEC.replace("\"required\":true","\"required\":true,\"required\":true"));
        invalid(SPEC.replace("\"required\":true","\"required\":true,\"default\":\"100\""));
        invalid(SPEC.replace("\"key\":\"size\"","\"key\":\"size\",\"expression\":\"x*2\""));
        invalid(SPEC+" {}");invalid(null);invalid("[]");
    }
    @Test void storageHashMismatchIsCorruptionNotInputError() {
        String sha=CapabilityQuoteSpecJson.read(SPEC).specSha();
        assertThrows(IllegalStateException.class,()->CapabilityQuoteSpecJson.readStored(SPEC,"0".repeat(64)));
        assertThrows(IllegalStateException.class,()->CapabilityQuoteSpecJson.readStored(SPEC.replace("10000","9999"),sha));
        assertThrows(IllegalStateException.class,()->CapabilityQuoteSpecJson.readStored("{",sha));
        assertThrows(IllegalStateException.class,()->CapabilityQuoteSpecJson.readStored(SPEC,null));
    }
    @Test void boundsApplyBeforeDomainConstructionAndRejectOversizedDocuments() throws Exception {
        var root=tree();var dims=root.putArray("costDimensions");
        for(int i=0;i<=CapabilityQuoteSpecJson.MAX_DIMENSIONS;i++)dims.add(tree().get("costDimensions").get(0));
        invalid(root.toString());
        root=tree();var buckets=root.putArray("buckets");
        for(int i=0;i<=CapabilityQuoteSpecJson.MAX_BUCKETS;i++)buckets.add(tree().get("buckets").get(0));
        invalid(root.toString());
        root=tree();var fields=root.putObject("fields");
        for(int i=0;i<=CapabilityQuoteSpecJson.MAX_FIELDS;i++)fields.set("parameters.f"+i,tree().get("fields").get("deliverables.words"));
        invalid(root.toString());invalid(" ".repeat(CapabilityQuoteSpecJson.MAX_BYTES)+SPEC);
        invalid(SPEC.replace("text.summary","x".repeat(4097)));
        invalid("[".repeat(13)+"0"+"]".repeat(13));
    }
    @Test void duplicateEnumAndMimeEntriesAreRejectedRatherThanLosingWireEvidence() throws Exception {
        var root=tree();((ObjectNode)root.get("costDimensions").get(0)).putArray("mimeTypes").add("text/plain").add("text/plain");invalid(root.toString());
        root=tree();var field=(ObjectNode)root.get("fields").get("deliverables.words");field.putArray("values").add("one").add("one");invalid(root.toString());
        root=tree();field=(ObjectNode)root.get("fields").get("deliverables.words");field.putNull("values");invalid(root.toString());
    }
    @Test void textEnumBooleanAndUnboundedRangesRoundTripWithoutChangingSemantics() throws Exception {
        var root=tree();var fields=(ObjectNode)root.get("fields");
        fields.putObject("parameters.note").put("type","TEXT").put("unit","unit/text").put("required",false).put("maxLength",200);
        fields.putObject("parameters.format").put("type","ENUM").put("unit","unit/code").put("required",true).putArray("values").add("PDF").add("CSV");
        fields.putObject("parameters.flag").put("type","BOOLEAN").put("unit","unit/flag").put("required",false);
        ((ObjectNode)root.get("buckets").get(1).get("ranges").get("size")).putNull("hi");
        var spec=CapabilityQuoteSpecJson.read(root.toString());
        assertEquals(spec,CapabilityQuoteSpecJson.read(CapabilityQuoteSpecJson.write(spec)));
        ((ObjectNode)fields.get("parameters.note")).put("maxLength","200");invalid(root.toString());
        ((ObjectNode)fields.get("parameters.note")).put("maxLength",2147483648L);invalid(root.toString());
    }
    @Test void escapedInvalidUnicodeIsRejectedWithoutEchoingTheDeclaration() {
        invalid(SPEC.replace("text.summary","private-"+"\\uD800"));
        invalid(SPEC.replace("text.summary","private-"+'\uD800'));
    }
}
