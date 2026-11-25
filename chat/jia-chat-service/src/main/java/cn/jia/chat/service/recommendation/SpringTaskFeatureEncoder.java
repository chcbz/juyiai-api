package cn.jia.chat.service.recommendation;

import cn.jia.agent.service.TaskFeatureEncoder;
import com.openai.errors.OpenAIException;
import com.openai.errors.OpenAIInvalidDataException;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import java.util.List;

/** Reuses the configured Spring AI embedding transport, never the chat/summary model. */
@Component
final class SpringTaskFeatureEncoder implements TaskFeatureEncoder {
    private final EmbeddingModel model;
    private final Identity identity;

    SpringTaskFeatureEncoder(@Lazy EmbeddingModel model,
            @Value("${jia.agent.task-feature.provider-config-version:openai-gateway-v1}") String generation) {
        this.model = model;
        this.identity = new Identity(generation, "text-embedding-3-small", 1024);
    }
    @Override public Identity identity() { return identity; }

    @Override public Vector encode(String text) {
        try {
            var response = model.embedForResponse(List.of(text));
            if (response == null || response.getMetadata() == null
                    || !identity.model().equals(response.getMetadata().getModel())
                    || response.getResults().size() != 1) throw new InvalidResult();
            var hit = response.getResult();
            if (hit == null || !Integer.valueOf(0).equals(hit.getIndex())) throw new InvalidResult();
            return new Vector(identity, hit.getOutput());
        } catch (OpenAIInvalidDataException | ArithmeticException malformed) {
            throw new InvalidResult();
        } catch (OpenAIException transport) {
            // Provider exception bodies may contain sensitive data. Keep the port's failures content-free.
            throw new Unavailable();
        }
    }
}
