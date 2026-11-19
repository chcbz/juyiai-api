package cn.jia.agent.api;

import cn.jia.agent.position.PositionSkillException;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.List;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;

/** Exact-model import of the supported tier expression format. No expression execution. */
@Component
public final class BaseLlmModelPriceSource {
    public static final String URL="https://basellm.github.io/llm-metadata/api/newapi/ratio_config-v1-base.json";
    private static final BigDecimal USD_TO_CNY=new BigDecimal("6.6879"); // Existing CYF public-reference conversion, not live FX.
    private static final String RATE="((?:0|[1-9][0-9]{0,8})(?:\\.[0-9]{1,10})?)";
    private static final String TERMS="p\\s*\\*\\s*"+RATE+"\\s*\\+\\s*cr\\s*\\*\\s*"+RATE
            +"\\s*\\+\\s*cc\\s*\\*\\s*"+RATE+"\\s*\\+\\s*cc12h\\s*\\*\\s*"+RATE+"\\s*\\+\\s*c\\s*\\*\\s*"+RATE;
    private static final Pattern EXPRESSION=Pattern.compile("^len\\s*<=\\s*([1-9][0-9]{0,8})\\s*\\?\\s*tier\\(\"[a-zA-Z0-9_]+\",\\s*"
            +TERMS+"\\s*\\)\\s*:\\s*tier\\(\"[a-zA-Z0-9_]+\",\\s*"+TERMS+"\\s*\\)$");
    private static final String[] CATEGORIES={"INPUT","CACHED_INPUT","cc","cc12h","OUTPUT"};
    private static final int MAX_BYTES=2*1024*1024;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final JsonMapper json=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

    record Quote(String model,String version,String sourceSha256,long threshold,Map<String,String> shortUsd,Map<String,String> longUsd) {
        Map<String,Object> priceData() {
            return Map.of("basis","BASELLM_NEWAPI_ZH_REFERENCE_USD_TO_CNY_6.6879", "shortContextMaxInputTokens",threshold,
                    "yuanPerMillionTokens",yuan(shortUsd),"longContextYuanPerMillionTokens",yuan(longUsd),
                    "reference",Map.of("source",URL,"sha256",sourceSha256,"currency","USD",
                            "unit","perMillionTokens","base",shortUsd,"longContext",longUsd));
        }
        private static Map<String,String> yuan(Map<String,String> rates) {
            var result=new TreeMap<String,String>();
            for(var key:List.of("INPUT","CACHED_INPUT","OUTPUT"))
                result.put(key,new BigDecimal(rates.get(key)).multiply(USD_TO_CNY).stripTrailingZeros().toPlainString());
            return result;
        }
    }

    Quote read(String model) {
        var request=HttpRequest.newBuilder(URI.create(URL)).timeout(Duration.ofSeconds(12)).GET().build();
        try {
            // Bounded subscriber; ofInputStream would allow an unbounded response body read after headers.
            var pending=http.sendAsync(request, ignored->new BoundedPriceResponse(MAX_BYTES));
            HttpResponse<byte[]> response;
            try {response=pending.get(12,java.util.concurrent.TimeUnit.SECONDS);}
            finally {if(!pending.isDone())pending.cancel(true);}
            if(response.statusCode()!=200)throw new PositionSkillException(503,"MODEL_PRICE_SOURCE_UNAVAILABLE");
            return parse(response.body(),model);
        } catch(InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new PositionSkillException(503,"MODEL_PRICE_SOURCE_UNAVAILABLE");
        } catch(java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) {
            throw new PositionSkillException(503,"MODEL_PRICE_SOURCE_UNAVAILABLE");
        }
    }

    Quote parse(byte[] bytes,String model) {
        JsonNode root;
        try {root=json.readTree(bytes);}
        catch(tools.jackson.core.JacksonException invalid){throw new PositionSkillException(503,"MODEL_PRICE_SOURCE_INVALID");}
        if(root==null || !root.path("success").asBoolean(false))throw new PositionSkillException(503,"MODEL_PRICE_SOURCE_INVALID");
        var data=root.path("data");
        var mode=data.path("billing_mode").path(model);
        var expr=data.path("billing_expr").path(model);
        if(mode.isMissingNode() || expr.isMissingNode())throw new PositionSkillException(404,"MODEL_PRICE_SOURCE_MISSING");
        if(!"tiered_expr".equals(mode.asText()) || !expr.isTextual() || expr.asText().length()>1024)
            throw new PositionSkillException(422,"MODEL_PRICE_FORMAT_UNSUPPORTED");
        var match=EXPRESSION.matcher(expr.asText().strip());
        if(!match.matches())throw new PositionSkillException(422,"MODEL_PRICE_FORMAT_UNSUPPORTED");
        var base=new TreeMap<String,String>();var extended=new TreeMap<String,String>();
        for(int i=0;i<CATEGORIES.length;i++) {
            base.put(CATEGORIES[i],new BigDecimal(match.group(i+2)).stripTrailingZeros().toPlainString());
            extended.put(CATEGORIES[i],new BigDecimal(match.group(i+7)).stripTrailingZeros().toPlainString());
        }
        long threshold=Long.parseLong(match.group(1));
        var version="basellm-"+sha256(("v1\n"+model+"\n"+USD_TO_CNY+"\n"+threshold+"\n"+base+"\n"+extended).getBytes(StandardCharsets.UTF_8));
        return new Quote(model,version,sha256(bytes),threshold,Map.copyOf(base),Map.copyOf(extended));
    }
    private static final class BoundedPriceResponse implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body=new CompletableFuture<>();
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        private final int limit;
        private Flow.Subscription upstream;
        BoundedPriceResponse(int limit) {this.limit=limit;}
        @Override public CompletableFuture<byte[]> getBody(){return body;}
        @Override public void onSubscribe(Flow.Subscription subscription){upstream=subscription;upstream.request(1);}
        @Override public void onNext(List<ByteBuffer> chunks) {
            for(var chunk:chunks) {
                if(chunk.remaining()>limit-bytes.size()) {
                    upstream.cancel();body.completeExceptionally(new IOException("Price response exceeds bound"));return;
                }
                var data=new byte[chunk.remaining()];chunk.get(data);bytes.writeBytes(data);
            }
            upstream.request(1);
        }
        @Override public void onError(Throwable failure){body.completeExceptionally(failure);}
        @Override public void onComplete(){body.complete(bytes.toByteArray());}
    }

    private static String sha256(byte[] bytes) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
