package cn.jia.economy.pricing;

import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Canonical evidence of the actual rates read, not just a catalog version string. */
public record GroupTariffSnapshot(MeasuredTaskRepricing.Reference reference,String tariffSha,String canonicalBase64) {
    private static final Comparator<String> UTF8=(a,b)->Arrays.compareUnsigned(bytes(a),bytes(b));
    public static GroupTariffSnapshot capture(MeasuredTaskRepricing.Reference reference) {
        var buffer=new ByteArrayOutputStream();
        try(var out=new DataOutputStream(buffer)) {
            field(out,"group-tariff-1");var kinds=reference.byKind().keySet().stream().sorted(UTF8).toList();out.writeInt(kinds.size());
            if(kinds.isEmpty())throw new IllegalArgumentException("Missing pricing kinds");
            for(var kind:kinds) {
                field(out,kind);var rates=reference.byKind().get(kind);var components=rates.keySet().stream().sorted(UTF8).toList();out.writeInt(components.size());
                if(components.isEmpty())throw new IllegalArgumentException("Missing pricing components");
                for(var component:components){field(out,component);rate(out,rates.get(component));}
            }
            out.flush();byte[] b=buffer.toByteArray();return new GroupTariffSnapshot(reference,
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b)), Base64.getEncoder().encodeToString(b));
        } catch(IOException | NoSuchAlgorithmException impossible) {throw new IllegalStateException(impossible);}
    }
    public record Scoped(MeasuredTaskRepricing.ResourceReference reference,String tariffSha,String canonicalBase64) {}
    public static Scoped capture(MeasuredTaskRepricing.ResourceReference reference){
        var buffer=new ByteArrayOutputStream();
        try(var out=new DataOutputStream(buffer)){
            field(out,"scope-group-tariff-1");var refs=reference.rates().keySet().stream().sorted(Comparator.comparing(CostRouteReference::sha)).toList();out.writeInt(refs.size());
            for(var ref:refs){for(var value:List.of(ref.kind(),ref.component(),ref.namespace(),ref.modelOrTool(),ref.normalizationBasis()))field(out,value);rate(out,reference.rates().get(ref));}
            out.flush();byte[] bytes=buffer.toByteArray();return new Scoped(reference,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),Base64.getEncoder().encodeToString(bytes));
        }catch(IOException|NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private static void rate(DataOutputStream out,MeasuredTaskRepricing.Rate value) throws IOException {
        switch(value) {
            case MeasuredTaskRepricing.TokenRate token -> {
                var r=token.reference();for(String s:List.of("token",token.normalizationBasis(),r.channel(),r.model(),r.tier(),r.version(),r.basis()))field(out,s);
                rates(out,r.yuanPerMillionTokens());out.writeBoolean(r.shortContextMaxInputTokens()!=null);
                if(r.shortContextMaxInputTokens()!=null)out.writeLong(r.shortContextMaxInputTokens());rates(out,r.longContextYuanPerMillionTokens());
            }
            case MeasuredTaskRepricing.UnitRate unit -> {
                var u=unit.unit();var r=unit.reference();for(String s:List.of("unit",u.channel(),u.tool(),u.unit(),r.version(),r.basis()))field(out,s);decimal(out,r.yuanPerUnit());
            }
            case MeasuredTaskRepricing.ExplicitZero zero -> {field(out,"explicit-zero");field(out,zero.contractVersion());}
        }
    }
    private static void rates(DataOutputStream out,Map<ModelTokenPrice.Category,BigDecimal> rates) throws IOException {
        var keys=rates.keySet().stream().sorted(Comparator.comparing(Enum::name)).toList();out.writeInt(keys.size());
        for(var k:keys){field(out,k.name());decimal(out,rates.get(k));}
    }
    private static void decimal(DataOutputStream out,BigDecimal n) throws IOException {field(out,n.stripTrailingZeros().toPlainString());}
    private static byte[] bytes(String s){if(s.isBlank() || !StandardCharsets.UTF_8.newEncoder().canEncode(s))throw new IllegalArgumentException("Invalid tariff field");return s.getBytes(StandardCharsets.UTF_8);}
    private static void field(DataOutputStream out,String s) throws IOException {byte[] b=bytes(s);out.writeInt(b.length);out.write(b);}
}
