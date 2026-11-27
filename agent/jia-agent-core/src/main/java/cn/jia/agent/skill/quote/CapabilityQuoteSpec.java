package cn.jia.agent.skill.quote;

import cn.jia.agent.requirement.ConfirmedScope.Scalar;
import cn.jia.agent.requirement.ConfirmedScope.ValueType;
import cn.jia.agent.requirement.ConfirmedScope.WorkItem;
import cn.jia.core.util.CanonicalValueEncoding;
import java.math.BigDecimal;
import java.util.*;

/** Immutable capability declaration and pure cost classification. No authorization, price or resource parsing. */
public record CapabilityQuoteSpec(String schema,String capabilityId,String version,
        Map<String,Field> fields,List<CostDimension> costDimensions,List<Bucket> buckets) {
    public static final String SCHEMA="CAPABILITY_QUOTE_SPEC_V1";
    public enum Source { PARAMETER, DELIVERABLE, INPUT_COUNT, INPUT_SUM }
    public enum MatchStatus { MATCHED, COST_INPUT_MISSING, OUT_OF_DISTRIBUTION }

    public CapabilityQuoteSpec {
        require(SCHEMA.equals(schema),"Unknown capability specification schema");
        id(capabilityId,128);id(version,64);
        fields=Map.copyOf(Objects.requireNonNull(fields));
        for(String key:fields.keySet()) {
            require(key.startsWith("parameters.") || key.startsWith("deliverables."),"Unknown field namespace");
            id(key.substring(key.indexOf('.')+1),128);
        }
        costDimensions=List.copyOf(Objects.requireNonNull(costDimensions));
        buckets=List.copyOf(Objects.requireNonNull(buckets));
        require(!costDimensions.isEmpty() && !buckets.isEmpty(),"Cost dimensions and buckets required");
        var dimensions=new HashMap<String,CostDimension>();
        for(CostDimension dimension:costDimensions) {
            require(dimensions.put(dimension.key(),dimension)==null,"Duplicate cost dimension");
            if(dimension.source()==Source.PARAMETER || dimension.source()==Source.DELIVERABLE) {
                var field=fields.get((dimension.source()==Source.PARAMETER?"parameters.":"deliverables.")+dimension.selector());
                require(field!=null && field.type()==dimension.type() && field.unit().equals(dimension.unit()),"Cost field not declared with matching type and unit");
                require(!numeric(field.type()) || decimal(field.min()).signum()>=0,"Cost quantities cannot be negative");
            }
        }
        var bucketIds=new HashSet<String>();
        for(Bucket bucket:buckets) {
            require(bucketIds.add(bucket.id()),"Duplicate bucket");
            require(bucket.ranges().keySet().equals(dimensions.keySet()),"Every bucket must cover exactly all cost dimensions");
            for(var entry:bucket.ranges().entrySet()) {
                var dimension=dimensions.get(entry.getKey());var range=entry.getValue();
                range.validate(dimension.type(),dimension.unit());
                if(dimension.source()==Source.PARAMETER || dimension.source()==Source.DELIVERABLE) {
                    var field=fields.get((dimension.source()==Source.PARAMETER?"parameters.":"deliverables.")+dimension.selector());
                    if(dimension.type()==ValueType.ENUM) require(field.values().containsAll(range.values()),"Bucket includes undeclared enum");
                }
            }
        }
        // A multidimensional pair overlaps only if its intersection is nonempty in EVERY dimension.
        for(int i=0;i<buckets.size();i++) for(int j=i+1;j<buckets.size();j++) {
            var first=buckets.get(i);var second=buckets.get(j);
            require(dimensions.values().stream().anyMatch(d -> !first.ranges().get(d.key()).overlaps(second.ranges().get(d.key()),d.type())),
                    "Overlapping cost buckets");
        }
        costDimensions=costDimensions.stream().sorted((a,b)->CanonicalValueEncoding.compareUtf8(a.key(),b.key())).toList();
        buckets=buckets.stream().sorted((a,b)->CanonicalValueEncoding.compareUtf8(a.id(),b.id())).toList();
    }

    public record Field(ValueType type,String unit,boolean required,String min,String max,Set<String> values,Integer maxLength) {
        public Field {
            require(type!=null,"Field type required");id(unit,64);values=Set.copyOf(values==null?Set.of():values);
            if(numeric(type)) {
                require(min!=null && max!=null && values.isEmpty() && maxLength==null,"Numeric field needs finite min/max only");
                min=number(type,unit,min);max=number(type,unit,max);
                require(decimal(min).compareTo(decimal(max))<=0,"Invalid field bounds");
            } else {
                require(min==null && max==null,"Non-numeric field cannot have numeric bounds");
                if(type==ValueType.ENUM) {
                    require(!values.isEmpty() && maxLength==null,"Enum needs finite values");
                    for(String value:values)new Scalar(type,unit,value);
                } else if(type==ValueType.TEXT) require(values.isEmpty() && maxLength!=null && maxLength>0,"Text field needs length bound");
                else require(values.isEmpty() && maxLength==null,"Boolean has fixed semantics");
            }
        }
        void validate(Scalar value) {
            require(value.type()==type && unit.equals(value.unit()),"Scope field type or unit mismatch");
            if(numeric(type)) require(decimal(value.value()).compareTo(decimal(min))>=0 && decimal(value.value()).compareTo(decimal(max))<=0,"Scope field outside declared bounds");
            else if(type==ValueType.ENUM) require(values.contains(value.value()),"Scope enum not declared");
            else if(type==ValueType.TEXT) require(value.value().codePointCount(0,value.value().length())<=maxLength,"Scope text exceeds declared bound");
        }
        Map<String,Object> canonical() {
            var map=new HashMap<String,Object>();map.put("type",type.name());map.put("unit",unit);map.put("required",Boolean.toString(required));
            map.put("min",min);map.put("max",max);map.put("values",sorted(values));map.put("maxLength",maxLength==null?null:maxLength.toString());return map;
        }
    }

    public record CostDimension(String key,Source source,String selector,ValueType type,String unit,
            String metadataSchemaVersion,Set<String> mimeTypes) {
        public CostDimension {
            id(key,128);id(unit,64);require(source!=null && type!=null && type!=ValueType.TEXT,"Invalid cost dimension");
            mimeTypes=Set.copyOf(mimeTypes==null?Set.of():mimeTypes);
            for(String mime:mimeTypes) {
                id(mime,128);require(mime.matches("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+"),"Exact MIME required");
            }
            if(source==Source.PARAMETER || source==Source.DELIVERABLE) {
                id(selector,128);require(metadataSchemaVersion==null && mimeTypes.isEmpty(),"Business field cannot select metadata");
            } else if(source==Source.INPUT_COUNT) {
                require(selector==null && metadataSchemaVersion==null && type==ValueType.INTEGER && "unit/items".equals(unit),"Input count is an integer item count");
            } else {
                id(selector,128);id(metadataSchemaVersion,64);require(numeric(type),"Input sum requires numeric metadata");
            }
        }
        Map<String,Object> canonical() {
            var map=new HashMap<String,Object>();map.put("key",key);map.put("source",source.name());map.put("selector",selector);
            map.put("type",type.name());map.put("unit",unit);map.put("metadataSchemaVersion",metadataSchemaVersion);map.put("mimeTypes",sorted(mimeTypes));return map;
        }
    }

    public record Range(String lo,String hi,Set<String> values) {
        public Range {values=Set.copyOf(values==null?Set.of():values);}
        void validate(ValueType type,String unit) {
            if(numeric(type)) {
                require(lo!=null && values.isEmpty(),"Numeric bucket requires lower bound and no enum set");
                require(number(type,unit,lo).equals(lo) && decimal(lo).signum()>=0,"Bucket lower bound must be canonical and nonnegative");
                if(hi!=null)require(number(type,unit,hi).equals(hi) && decimal(hi).compareTo(decimal(lo))>0,"Invalid bucket upper bound");
            } else {
                require(lo==null && hi==null && !values.isEmpty(),"Enum bucket requires a nonempty set only");
                for(String value:values)new Scalar(type,unit,value);
            }
        }
        boolean contains(Scalar value) {
            if(numeric(value.type()))return decimal(value.value()).compareTo(decimal(lo))>=0 && (hi==null || decimal(value.value()).compareTo(decimal(hi))<0);
            return values.contains(value.value());
        }
        boolean overlaps(Range other,ValueType type) {
            if(numeric(type))return (hi==null || decimal(other.lo).compareTo(decimal(hi))<0)
                    && (other.hi==null || decimal(lo).compareTo(decimal(other.hi))<0);
            return values.stream().anyMatch(other.values::contains);
        }
        Map<String,Object> canonical() {var map=new HashMap<String,Object>();map.put("lo",lo);map.put("hi",hi);map.put("values",sorted(values));return map;}
    }

    public record Bucket(String id,Map<String,Range> ranges) {
        public Bucket {CapabilityQuoteSpec.id(id,128);ranges=Map.copyOf(Objects.requireNonNull(ranges));}
        Map<String,Object> canonical() {var map=new HashMap<String,Object>();ranges.forEach((k,v)->map.put(k,v.canonical()));return Map.of("id",id,"ranges",map);}
    }

    /** Fixed-version catalogue projection. Construction is not permission to read a file.
     * Null metadata version/parser with an empty map represents unparsed cost metadata, not zero.
     */
    public record InputMetadata(String reference,long fileVersion,String mimeType,String contentSha256,
            String metadataSchemaVersion,String parserVersion,Map<String,Scalar> values) {
        public InputMetadata {
            require(reference!=null && !reference.isBlank() && fileVersion>0,"Fixed input reference required");
            CanonicalValueEncoding.utf8(reference);id(mimeType,128);
            require(contentSha256!=null && contentSha256.matches("[0-9a-f]{64}"),"Input content digest required");
            values=Map.copyOf(Objects.requireNonNull(values));
            values.keySet().forEach(key->id(key,128));
            if(metadataSchemaVersion==null || parserVersion==null)
                require(metadataSchemaVersion==null && parserVersion==null && values.isEmpty(),"Partial metadata evidence");
            else {id(metadataSchemaVersion,64);id(parserVersion,64);}
        }
        @Override public String toString() {return "InputMetadata[private]";}
    }

    public record Match(MatchStatus status,String bucketId,Map<String,Scalar> dimensions) {
        public Match {
            Objects.requireNonNull(status);dimensions=Map.copyOf(dimensions);
            require((status==MatchStatus.MATCHED)==(bucketId!=null),"Inconsistent bucket result");
        }
        @Override public String toString() {return "CostBucketMatch["+status+"]";}
    }

    /** Validates the user's confirmed constraints without inventing omitted defaults. */
    public void validateWorkItem(WorkItem item) {
        require(item!=null && capabilityId.equals(item.capabilityId()) && version.equals(item.capabilityVersion()),"Scope capability version mismatch");
        validateFields("parameters.",item.parameters());validateFields("deliverables.",item.deliverables());
    }
    private void validateFields(String prefix,Map<String,Scalar> values) {
        for(var entry:values.entrySet()) {
            var field=fields.get(prefix+entry.getKey());require(field!=null,"Unknown scope field");field.validate(entry.getValue());
        }
        for(var entry:fields.entrySet())if(entry.getKey().startsWith(prefix) && entry.getValue().required())
            require(values.containsKey(entry.getKey().substring(prefix.length())),"Required scope field missing");
    }

    public Match classify(WorkItem item,Map<String,InputMetadata> inputs) {
        validateWorkItem(item);Objects.requireNonNull(inputs);
        var dimensions=new HashMap<String,Scalar>();boolean missing=false;
        for(CostDimension dimension:costDimensions) {
            Scalar value=extract(dimension,item,inputs);
            if(value==null) missing=true;
            else {
                if(numeric(value.type()) && decimal(value.value()).signum()<0)throw corruptMetadata();
                dimensions.put(dimension.key(),value);
            }
        }
        if(missing)return new Match(MatchStatus.COST_INPUT_MISSING,null,dimensions);
        for(Bucket bucket:buckets)if(bucket.ranges().entrySet().stream().allMatch(e->e.getValue().contains(dimensions.get(e.getKey()))))
            return new Match(MatchStatus.MATCHED,bucket.id(),dimensions);
        return new Match(MatchStatus.OUT_OF_DISTRIBUTION,null,dimensions);
    }

    private Scalar extract(CostDimension dimension,WorkItem item,Map<String,InputMetadata> inputs) {
        if(dimension.source()==Source.PARAMETER)return item.parameters().get(dimension.selector());
        if(dimension.source()==Source.DELIVERABLE)return item.deliverables().get(dimension.selector());
        BigDecimal sum=BigDecimal.ZERO;boolean missing=false;
        for(String reference:item.inputRefs()) {
            if(dimension.source()==Source.INPUT_COUNT && dimension.mimeTypes().isEmpty()) {sum=sum.add(BigDecimal.ONE);continue;}
            var input=inputs.get(reference);
            if(input==null) {missing=true;continue;}
            if(!reference.equals(input.reference()))throw corruptMetadata();
            if(!dimension.mimeTypes().isEmpty() && !dimension.mimeTypes().contains(input.mimeType()))continue;
            if(dimension.source()==Source.INPUT_COUNT) {sum=sum.add(BigDecimal.ONE);continue;}
            if(!dimension.metadataSchemaVersion().equals(input.metadataSchemaVersion())) {missing=true;continue;}
            var value=input.values().get(dimension.selector());
            if(value==null) {missing=true;continue;}
            if(value.type()!=dimension.type() || !value.unit().equals(dimension.unit()) || decimal(value.value()).signum()<0)throw corruptMetadata();
            sum=sum.add(decimal(value.value()));
        }
        if(missing)return null;
        try {return new Scalar(dimension.type(),dimension.unit(),sum.toPlainString());}
        catch(IllegalArgumentException failure) {throw corruptMetadata();}
    }

    /** Semantic identity excludes itself, publication time, publisher and private request identity. */
    public String specSha() {
        var declared=new HashMap<String,Object>();fields.forEach((key,value)->declared.put(key,value.canonical()));
        return CanonicalValueEncoding.digest(Map.of("schema",schema,"capabilityId",capabilityId,"version",version,
                "fields",declared,"costDimensions",costDimensions.stream().map(CostDimension::canonical).toList(),
                "buckets",buckets.stream().map(Bucket::canonical).toList()));
    }
    public String bucketDefinitionsSha() {
        return CanonicalValueEncoding.digest(Map.of("costDimensions",costDimensions.stream().map(CostDimension::canonical).toList(),
                "buckets",buckets.stream().map(Bucket::canonical).toList()));
    }
    private static List<String> sorted(Set<String> values) {return values.stream().sorted(CanonicalValueEncoding::compareUtf8).toList();}
    private static boolean numeric(ValueType type) {return type==ValueType.INTEGER || type==ValueType.DECIMAL;}
    private static BigDecimal decimal(String value) {return new BigDecimal(value);}
    private static String number(ValueType type,String unit,String value) {return new Scalar(type,unit,value).value();}
    private static void id(String value,int maximum) {
        require(value!=null && !value.isBlank() && value.codePointCount(0,value.length())<=maximum,"Invalid specification identifier");
        CanonicalValueEncoding.utf8(value);
    }
    private static void require(boolean condition,String message) {if(!condition)throw new IllegalArgumentException(message);}
    private static IllegalStateException corruptMetadata() {return new IllegalStateException("Invalid pinned cost metadata");}
}
