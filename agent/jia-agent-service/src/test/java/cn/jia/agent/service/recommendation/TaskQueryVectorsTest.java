package cn.jia.agent.service.recommendation;

import cn.jia.agent.entity.AgentTaskDTO;
import cn.jia.agent.hosting.HostingRentHttp.Actor;
import cn.jia.agent.service.TaskFeatureEncoder;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

class TaskQueryVectorsTest {
    private static final Actor OWNER = new Actor("u", "0", "client", "owner");
    private static final TaskFeatureEncoder.Identity ID = new TaskFeatureEncoder.Identity("test", "fixture", 1);
    private static final TaskFeatureEncoder.Vector VALUE = new TaskFeatureEncoder.Vector(ID, new float[]{1});
    private static TaskQueryVectors cache(Supplier<TaskFeatureEncoder.Vector> fn) {
        return new TaskQueryVectors(new TaskFeatureEncoder() {
            public Identity identity() { return ID; }
            public Vector encode(String text) { return fn.get(); }
        }, 1, 1, Duration.ofMinutes(1));
    }
    private static TaskRetrievalText input(String title) {
        var task = new AgentTaskDTO(); task.setTitle(title); return TaskRetrievalText.from(task, List.of());
    }
    @Test void scopeAndBoundsPreventLeakageAndUnboundedRetention() {
        var calls = new AtomicInteger(); var c = cache(() -> { calls.incrementAndGet(); return VALUE; });
        var t = input("demo");
        assertSame(VALUE, c.encode(OWNER, t).block()); assertSame(VALUE, c.encode(OWNER, t).block());
        assertEquals(1, calls.get());
        c.encode(new Actor("v", "0", "client", "other"), t).block();
        c.encode(OWNER, t).block(); assertEquals(3, calls.get());
    }
    @Test void failedEncodingIsNotACompletedEntry() {
        var calls = new AtomicInteger(); var c = cache(() -> {
            if (calls.getAndIncrement() == 0) throw new TaskFeatureEncoder.Unavailable(); return VALUE;
        });
        assertThrows(TaskFeatureEncoder.Unavailable.class, () -> c.encode(OWNER, input("x")).block());
        assertSame(VALUE, c.encode(OWNER, input("x")).block()); assertEquals(2, calls.get());
    }
    @Test void cancellationDoesNotCancelOtherSubscriberOrStartDuplicateRequest() throws Exception {
        var release = new CountDownLatch(1); var entered = new CountDownLatch(1); var calls = new AtomicInteger();
        var c = cache(() -> {
            calls.incrementAndGet(); entered.countDown();
            try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("encode timed out"); }
            catch (InterruptedException e) { throw new AssertionError(e); }
            return VALUE;
        });
        var first = c.encode(OWNER, input("x")).toFuture();
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var other = c.encode(OWNER, input("x")).toFuture(); first.cancel(true);
            assertThrows(TaskFeatureEncoder.Unavailable.class, () -> c.encode(OWNER, input("different")).block());
            release.countDown(); assertSame(VALUE, other.get(5, TimeUnit.SECONDS)); assertEquals(1, calls.get());
        } finally { release.countDown(); }
    }
    @Test void textIsNormalizedAndTruncatedOnUnicodeBoundaries() {
        assertEquals(input("é  文").sha256(), input("e\u0301\n文").sha256());
        var longInput = input("😀".repeat(3000));
        assertTrue(longInput.truncated()); assertFalse(input("😀").truncated());
        assertTrue(longInput.text().getBytes(StandardCharsets.UTF_8).length <= 7500);
        assertTrue(longInput.text().endsWith("😀"));
    }
}
