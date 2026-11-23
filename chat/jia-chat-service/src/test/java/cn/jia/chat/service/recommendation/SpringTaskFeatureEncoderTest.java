package cn.jia.chat.service.recommendation;

import cn.jia.agent.service.TaskFeatureEncoder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.observation.EmbeddingModelObservationConvention;
import org.springframework.ai.model.openai.autoconfigure.OpenAiCommonProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingProperties;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class SpringTaskFeatureEncoderTest {
    private static final String MODEL = "text-embedding-3-small";

    @Test void successfulExchange() throws Exception { exchange(200, MODEL, 1024); }
    @Test void rejectsDifferentModelEvenAtCorrectDimensions() throws Exception { exchange(200, "other", 1024); }
    @Test void rejectsWrongDimensions() throws Exception { exchange(200, MODEL, 3); }
    @Test void rateLimitDoesNotRetry() throws Exception { exchange(429, MODEL, 0); }

    private static void exchange(int status, String returnedModel, int dimensions) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var calls = new AtomicInteger();
        var path = new AtomicReference<String>();
        var authorization = new AtomicReference<String>();
        var request = new AtomicReference<JsonNode>();
        var json = new ObjectMapper();
        server.createContext("/", x -> {
            calls.incrementAndGet();
            path.set(x.getRequestMethod() + " " + x.getRequestURI().getPath());
            authorization.set(x.getRequestHeaders().getFirst("Authorization"));
            request.set(json.readTree(x.getRequestBody()));
            String payload = status == 200
                    ? "{\"object\":\"list\",\"model\":\"" + returnedModel + "\",\"data\":[{\"object\":\"embedding\",\"index\":0,\"embedding\":["
                      + String.join(",", Collections.nCopies(dimensions, "0.5")) + "]}],\"usage\":{\"prompt_tokens\":1,\"total_tokens\":1}}"
                    : "{\"error\":{\"message\":\"synthetic rate limit\",\"type\":\"rate_limit_error\"}}";
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().set("Content-Type", "application/json");
            x.sendResponseHeaders(status, bytes.length);
            try (var out = x.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        try {
            var common = new OpenAiCommonProperties();
            common.setBaseUrl("http://127.0.0.1:1/v1"); common.setApiKey("unused");
            var specific = new OpenAiEmbeddingProperties();
            specific.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            specific.setApiKey("synthetic"); specific.setModel(MODEL); specific.setDimensions(1024);
            specific.setMaxRetries(0); specific.setEncodingFormat(OpenAiEmbeddingOptions.EncodingFormat.FLOAT);
            var beans = new DefaultListableBeanFactory();
            var model = new OpenAiEmbeddingAutoConfiguration().openAiEmbeddingModel(common, specific,
                    beans.getBeanProvider(ObservationRegistry.class), beans.getBeanProvider(MeterRegistry.class),
                    beans.getBeanProvider(EmbeddingModelObservationConvention.class), beans.getBeanProvider(OpenAiHttpClientBuilderCustomizer.class));
            var encoder = new SpringTaskFeatureEncoder(model, "test-generation");
            if (status != 200) assertThrows(TaskFeatureEncoder.Unavailable.class, () -> encoder.encode("fixture"));
            else if (!MODEL.equals(returnedModel) || dimensions != 1024)
                assertThrows(TaskFeatureEncoder.InvalidResult.class, () -> encoder.encode("fixture"));
            else {
                var vector = encoder.encode("fixture");
                assertEquals(encoder.identity(), vector.identity());
                var mutable = vector.values(); mutable[0] = 0;
                assertEquals(0.5f, vector.values()[0]);
            }
            assertEquals(1, calls.get());
            assertEquals("POST /v1/embeddings", path.get());
            assertEquals("Bearer synthetic", authorization.get());
            assertEquals(MODEL, request.get().path("model").asText());
            assertEquals(1024, request.get().path("dimensions").asInt());
            assertEquals("float", request.get().path("encoding_format").asText());
        } finally { server.stop(0); }
    }
}
