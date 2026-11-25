package cn.jia.agent.service.recommendation;

import cn.jia.agent.hosting.HostingRentHttp.Actor;
import cn.jia.agent.service.TaskFeatureEncoder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Shared by independent task queries, only after task authorization. No raw text in completed cache. */
@Service
final class TaskQueryVectors {
    private record Key(String tenant, String client, String owner, String template,
                       String sha256, TaskFeatureEncoder.Identity identity) { }
    private record Entry(long created, TaskFeatureEncoder.Vector vector) { }
    private final Map<Key, Entry> ready = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<Key, Mono<TaskFeatureEncoder.Vector>> pending = new HashMap<>();
    private record Lookup(String path, Mono<TaskFeatureEncoder.Vector> result) { }
    private final TaskFeatureEncoder encoder;
    private final ObservationRegistry observations;
    private final int capacity, concurrency;
    private final long ttlNanos;

    TaskQueryVectors(TaskFeatureEncoder encoder, int capacity, int concurrency, Duration ttl) {
        this(encoder, capacity, concurrency, ttl, java.util.Optional.empty());
    }
    @Autowired TaskQueryVectors(TaskFeatureEncoder encoder,
            @Value("${jia.agent.task-feature.cache-entries:256}") int capacity,
            @Value("${jia.agent.task-feature.max-inflight:8}") int concurrency,
            @Value("${jia.agent.task-feature.cache-ttl:PT10M}") Duration ttl, java.util.Optional<ObservationRegistry> registry) {
        this.observations = registry.orElse(ObservationRegistry.NOOP);
        this.encoder = encoder;
        if (capacity < 1 || concurrency < 1 || ttl.isNegative() || ttl.isZero())
            throw new IllegalArgumentException("invalid task feature cache configuration");
        this.capacity = capacity; this.concurrency = concurrency; this.ttlNanos = ttl.toNanos();
    }

    TaskFeatureEncoder.Identity identity() { return encoder.identity(); }

    Mono<TaskFeatureEncoder.Vector> encode(Actor actor, TaskRetrievalText input) {
        return encode(actor, input, "recommendation");
    }
    Mono<TaskFeatureEncoder.Vector> encodeQuote(Actor actor, TaskRetrievalText input) {
        return encode(actor, input, "quote");
    }
    private Mono<TaskFeatureEncoder.Vector> encode(Actor actor, TaskRetrievalText input, String operation) {
        return Mono.defer(() -> {
            var span = Observation.createNotStarted("cyf.task.feature.cache", observations)
                    .lowCardinalityKeyValue("operation", operation).start();
            String path = "error";
            try {
                var found = lookup(new Key(actor.tenantId(), actor.clientId(), actor.ownerJiacn(),
                        TaskRetrievalText.VERSION, input.sha256(), encoder.identity()), input, operation);
                path = found.path(); return found.result();
            } finally { span.lowCardinalityKeyValue("path", path).stop(); }
        });
    }
    private synchronized Lookup lookup(Key key, TaskRetrievalText input, String operation) {
        var entry = ready.get(key);
        if (entry != null) {
            if (System.nanoTime() - entry.created() < ttlNanos) return new Lookup("hit", Mono.just(entry.vector()));
            ready.remove(key);
        }
        var existing = pending.get(key);
        if (existing != null) return new Lookup("join", existing);
        if (pending.size() >= concurrency) return new Lookup("saturated", Mono.error(new TaskFeatureEncoder.Unavailable()));
        var load = Mono.fromCallable(() -> {
            // Only the shared producer is measured; waiters do not count as extra external calls.
            var span = Observation.createNotStarted("cyf.task.feature.encode", observations)
                    .lowCardinalityKeyValue("operation", operation).start();
            String outcome = "error";
            try {
                var v = encoder.encode(input.text());
                if (v == null || !key.identity().equals(v.identity())) throw new TaskFeatureEncoder.InvalidResult();
                outcome = "ok"; return v;
            } catch (TaskFeatureEncoder.InvalidResult invalid) { outcome = "invalid"; throw invalid; }
            finally { span.lowCardinalityKeyValue("outcome", outcome).stop(); }
        }).subscribeOn(Schedulers.boundedElastic())
                .doOnSuccess(v -> publish(key, v)).doOnError(e -> failed(key)).cache();
        // The cached in-flight subscription is independent of any one HTTP subscriber's cancellation.
        pending.put(key, load);
        return new Lookup("miss", load);
    }
    private synchronized void publish(Key key, TaskFeatureEncoder.Vector v) {
        ready.put(key, new Entry(System.nanoTime(), v));
        if (ready.size() > capacity) ready.remove(ready.keySet().iterator().next());
        pending.remove(key);
    }
    private synchronized void failed(Key key) { pending.remove(key); }
}
