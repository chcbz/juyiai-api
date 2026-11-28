package cn.jia.economy.service.impl;

import cn.jia.economy.pricing.MeasuredTaskRepricing;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

/** One deterministic representation for both the retained study artifact and current contract admission. */
final class TaskTariffBook {
    record Rate(String kind,String component,String type,String body){}
    record Book(String version,List<Rate> rates){}
    private static final ObjectMapper JSON=JsonMapper.builder().enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).addModule(new SimpleModule().addSerializer(BigDecimal.class,new ValueSerializer<BigDecimal>(){
                @Override public void serialize(BigDecimal value,JsonGenerator out,SerializationContext context){out.writeNumber(value.stripTrailingZeros());}
            })).build();
    static String encode(MeasuredTaskRepricing.Reference book){
        var rows=new ArrayList<Rate>();new TreeMap<>(book.byKind()).forEach((k,components)->new TreeMap<>(components).forEach((c,r)->{
            String type=switch(r){case MeasuredTaskRepricing.TokenRate ignored->"token";case MeasuredTaskRepricing.UnitRate ignored->"unit";case MeasuredTaskRepricing.ExplicitZero ignored->"zero";};
            rows.add(new Rate(k,c,type,JSON.writeValueAsString(r)));}));return JSON.writeValueAsString(new Book(book.version(),rows));
    }
    static String sha(String body){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    record ScopedRate(cn.jia.economy.pricing.CostRouteReference resource,String type,String body) {}
    record ScopedBook(String version,List<ScopedRate> rates) {}
    static String encodeScoped(MeasuredTaskRepricing.ResourceReference book){
        var rows=book.rates().entrySet().stream().sorted(java.util.Comparator.comparing(e->e.getKey().sha())).map(e->{
            String type=switch(e.getValue()){case MeasuredTaskRepricing.TokenRate ignored->"token";case MeasuredTaskRepricing.UnitRate ignored->"unit";case MeasuredTaskRepricing.ExplicitZero ignored->"zero";};
            return new ScopedRate(e.getKey(),type,JSON.writeValueAsString(e.getValue()));}).toList();
        return JSON.writeValueAsString(new ScopedBook(book.version(),rows));
    }
    static MeasuredTaskRepricing.ResourceReference scoped(ScopedBook book){
        var rates=new java.util.HashMap<cn.jia.economy.pricing.CostRouteReference,MeasuredTaskRepricing.Rate>();
        for(var row:book.rates()){
            MeasuredTaskRepricing.Rate rate=switch(row.type()){
                case "token"->JSON.readValue(row.body(),MeasuredTaskRepricing.TokenRate.class);
                case "unit"->JSON.readValue(row.body(),MeasuredTaskRepricing.UnitRate.class);
                case "zero"->JSON.readValue(row.body(),MeasuredTaskRepricing.ExplicitZero.class);
                default->throw new IllegalArgumentException("Unknown scoped tariff type");};
            if(rates.putIfAbsent(row.resource(),rate)!=null)throw new IllegalArgumentException("Duplicate scoped tariff resource");
        }
        return new MeasuredTaskRepricing.ResourceReference(book.version(),rates);
    }
    private TaskTariffBook(){}
}
