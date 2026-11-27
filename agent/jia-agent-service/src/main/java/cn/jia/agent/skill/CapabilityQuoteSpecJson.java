package cn.jia.agent.skill;

import cn.jia.agent.requirement.ConfirmedScope.ValueType;
import cn.jia.agent.skill.quote.CapabilityQuoteSpec;
import cn.jia.agent.skill.quote.CapabilityQuoteSpec.*;
import cn.jia.core.util.CanonicalValueEncoding;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.*;

/** Skill-owned declaration codec. Structural limits precede quadratic bucket validation.
 * Neither decoding nor a matching semantic digest grants publication or task confirmation authority.
 */
public final class CapabilityQuoteSpecJson {
    public static final int MAX_BYTES=262144;
    public static final int MAX_FIELDS=128;
    public static final int MAX_DIMENSIONS=32;
    public static final int MAX_BUCKETS=128;
    public static final int MAX_CHOICES=128;
    private static final JsonMapper JSON=JsonMapper.builder(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(12)
                    .maxStringLength(4096).maxNumberLength(10).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    private CapabilityQuoteSpecJson() { }

    public static CapabilityQuoteSpec read(String raw) {
        try {
            if(raw==null || raw.length()>MAX_BYTES || CanonicalValueEncoding.utf8(raw).length>MAX_BYTES)throw invalid();
            var root=JSON.readTree(raw);
            object(root,Set.of("schema","capabilityId","version","fields","costDimensions","buckets"),Set.of());
            var fieldsNode=root.get("fields");boundedObject(fieldsNode,MAX_FIELDS);
            var fields=new HashMap<String,Field>();
            fieldsNode.fields().forEachRemaining(e->fields.put(e.getKey(),field(e.getValue())));
            var dimensions=new ArrayList<CostDimension>();
            for(var node:array(root.get("costDimensions"),MAX_DIMENSIONS))dimensions.add(dimension(node));
            var buckets=new ArrayList<Bucket>();
            for(var node:array(root.get("buckets"),MAX_BUCKETS)) {
                object(node,Set.of("id","ranges"),Set.of());
                var rangesNode=node.get("ranges");boundedObject(rangesNode,MAX_DIMENSIONS);
                var ranges=new HashMap<String,Range>();
                rangesNode.fields().forEachRemaining(e->{
                    var range=e.getValue();object(range,Set.of(),Set.of("lo","hi","values"));
                    ranges.put(e.getKey(),new Range(optionalText(range,"lo"),optionalText(range,"hi"),choices(range,"values")));
                });
                buckets.add(new Bucket(text(node,"id"),ranges));
            }
            return new CapabilityQuoteSpec(text(root,"schema"),text(root,"capabilityId"),text(root,"version"),fields,dimensions,buckets);
        } catch(Exception failure) {
            // Do not echo submitted declaration content in public errors or logs.
            throw invalid();
        }
    }

    /** Used for private immutable storage; validate the exact same wire accepted on read. */
    public static String write(CapabilityQuoteSpec spec) {
        try {
            Objects.requireNonNull(spec);
            String raw=JSON.writeValueAsString(spec);
            var decoded=read(raw);
            if(!decoded.equals(spec))throw invalid();
            return raw;
        } catch(Exception failure) {throw invalid();}
    }

    /** Storage corruption must be distinguishable from a caller's bad declaration, never a cheap quote. */
    public static CapabilityQuoteSpec readStored(String raw,String expectedSha) {
        try {
            if(expectedSha==null || !expectedSha.matches("[0-9a-f]{64}"))throw invalid();
            var spec=read(raw);
            if(!expectedSha.equals(spec.specSha()))throw invalid();
            return spec;
        } catch(IllegalArgumentException failure) {
            throw new IllegalStateException("CAPABILITY_QUOTE_SPEC_CORRUPT");
        }
    }

    private static Field field(JsonNode node) {
        object(node,Set.of("type","unit","required"),Set.of("min","max","values","maxLength"));
        var required=node.get("required");if(!required.isBoolean())throw invalid();
        var length=node.get("maxLength");Integer maxLength=null;
        if(length!=null && !length.isNull()) {
            if(!length.isIntegralNumber() || !length.canConvertToInt())throw invalid();
            maxLength=length.intValue();
        }
        return new Field(ValueType.valueOf(text(node,"type")),text(node,"unit"),required.booleanValue(),
                optionalText(node,"min"),optionalText(node,"max"),choices(node,"values"),maxLength);
    }
    private static CostDimension dimension(JsonNode node) {
        object(node,Set.of("key","source","type","unit"),Set.of("selector","metadataSchemaVersion","mimeTypes"));
        return new CostDimension(text(node,"key"),Source.valueOf(text(node,"source")),optionalText(node,"selector"),
                ValueType.valueOf(text(node,"type")),text(node,"unit"),optionalText(node,"metadataSchemaVersion"),choices(node,"mimeTypes"));
    }
    private static String text(JsonNode node,String key) {
        var value=node.get(key);if(value==null || !value.isTextual())throw invalid();
        String result=value.textValue();CanonicalValueEncoding.utf8(result);return result;
    }
    private static String optionalText(JsonNode node,String key) {
        var value=node.get(key);return value==null || value.isNull()?null:text(node,key);
    }
    private static Set<String> choices(JsonNode node,String key) {
        var value=node.get(key);if(value==null)return Set.of();
        var result=new HashSet<String>();
        for(var choice:array(value,MAX_CHOICES)) {
            if(!choice.isTextual())throw invalid();
            String item=choice.textValue();CanonicalValueEncoding.utf8(item);
            if(!result.add(item))throw invalid();
        }
        return Set.copyOf(result);
    }
    private static JsonNode array(JsonNode node,int maximum) {
        if(node==null || !node.isArray() || node.size()>maximum)throw invalid();return node;
    }
    private static void boundedObject(JsonNode node,int maximum) {
        if(node==null || !node.isObject() || node.size()>maximum)throw invalid();
        node.fieldNames().forEachRemaining(CanonicalValueEncoding::utf8);
    }
    private static void object(JsonNode node,Set<String> required,Set<String> optional) {
        boundedObject(node,required.size()+optional.size());
        for(String key:required)if(!node.has(key) || node.get(key).isNull())throw invalid();
        node.fieldNames().forEachRemaining(key->{if(!required.contains(key) && !optional.contains(key))throw invalid();});
    }
    private static IllegalArgumentException invalid() {return new IllegalArgumentException("CAPABILITY_QUOTE_SPEC_INVALID");}
}
