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
    private TaskTariffBook(){}
}
