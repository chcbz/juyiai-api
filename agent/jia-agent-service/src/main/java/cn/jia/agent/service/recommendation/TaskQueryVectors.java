package cn.jia.agent.service.recommendation;

import cn.jia.agent.hosting.HostingRentHttp.Actor;
import cn.jia.agent.service.TaskFeatureEncoder;
import org.springframework.beans.factory.annotation.Value;
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
    private final TaskFeatureEncoder encoder;
    private final int capacity, concurrency;
    private final long ttlNanos;

    TaskQueryVectors(TaskFeatureEncoder encoder,
            @Value("${jia.agent.task-feature.cache-entries:256}") int capacity,
            @Value("${jia.agent.task-feature.max-inflight:8}") int concurrency,
            @Value("${jia.agent.task-feature.cache-ttl:PT10M}") Duration ttl) {
        this.encoder = encoder;
        if (capacity < 1 || concurrency < 1 || ttl.isNegative() || ttl.isZero())
            throw new IllegalArgumentException("invalid task feature cache configuration");
        this.capacity = capacity; this.concurrency = concurrency; this.ttlNanos = ttl.toNanos();
    }

    TaskFeatureEncoder.Identity identity() { return encoder.identity(); }

    Mono<TaskFeatureEncoder.Vector> encode(Actor actor, TaskRetrievalText input) {
        return Mono.defer(() -> lookup(new Key(actor.tenantId(), actor.clientId(), actor.ownerJiacn(),
                TaskRetrievalText.VERSION, input.sha256(), encoder.identity()), input));
    }
    private synchronized Mono<TaskFeatureEncoder.Vector> lookup(Key key, TaskRetrievalText input) {
        var entry = ready.get(key);
        if (entry != null) {
            if (System.nanoTime() - entry.created() < ttlNanos) return Mono.just(entry.vector());
            ready.remove(key);
        }
        var existing = pending.get(key);
        if (existing != null) return existing;
        if (pending.size() >= concurrency) return Mono.error(new TaskFeatureEncoder.Unavailable());
        var load = Mono.fromCallable(() -> {
            var v = encoder.encode(input.text());
            if (v == null || !key.identity().equals(v.identity())) throw new TaskFeatureEncoder.InvalidResult();
            return v;
        }).subscribeOn(Schedulers.boundedElastic())
                .doOnSuccess(v -> publish(key, v)).doOnError(e -> failed(key)).cache();
        // The cached in-flight subscription is independent of any one HTTP subscriber's cancellation.
        pending.put(key, load);
        return load;
    }
    private synchronized void publish(Key key, TaskFeatureEncoder.Vector v) {
        ready.put(key, new Entry(System.nanoTime(), v));
        if (ready.size() > capacity) ready.remove(ready.keySet().iterator().next());
        pending.remove(key);
    }
    private synchronized void failed(Key key) { pending.remove(key); }
}
