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
import static org.mockito.Mockito.*;

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
    @Test void independentQuoteBindsOriginalInputsAndSharesRecommendationCacheWithoutRetainingText(){
        var calls=new AtomicInteger();float[] raw=new float[1024];raw[0]=1;
        var encoder=new TaskFeatureEncoder(){public Identity identity(){return new Identity("scope-1","text-embedding-3-small",1024);}public Vector encode(String text){calls.incrementAndGet();return new Vector(identity(),raw);}};
        var cache=new TaskQueryVectors(encoder,2,1,Duration.ofMinutes(1));var quotes=new QuoteFeaturePreparation(cache);
        var req=new cn.jia.agent.service.AgentTaskRequirementSnapshotService.CurrentSnapshot("task",5,3,"秘密标题","秘密说明","1".repeat(64),"CREATE");
        var file=new cn.jia.agent.service.AgentTaskExecutionGrantService.AuthorizedInput("private-file",2,"INPUT","text/plain",999,"2".repeat(64));
        var metadata=List.of(new RecommendationMaterials.Observation("link",1,"private-file",1,"2".repeat(64),999,new RecommendationMaterials.Material("私有文件名","私有文件名","text/plain",2,"INPUT","ACTIVE",null)));
        var input=quotes.observe(req,List.of("审查"),new cn.jia.agent.service.AgentTaskExecutionGrantService.InputCatalogue("3".repeat(64),List.of(file)),metadata);
        var conflicting=new RecommendationMaterials.Observation("ref",1,"private-file",1,"5".repeat(64),999,new RecommendationMaterials.Material("name","name","text/plain",2,"REFERENCE","ACTIVE",null));
        assertThrows(TaskQuoteIntentService.Stale.class,()->quotes.observe(req,List.of(),input.catalogue(),List.of(metadata.getFirst(),conflicting)));
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        try { assertThrows(IllegalStateException.class,()->quotes.prepare(OWNER,input)); } finally {org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);}
        assertEquals(0,calls.get());
        var features=quotes.prepare(OWNER,input);assertEquals(1,calls.get());assertEquals(1024,features.vector().size());assertNull(features.requestedDeliverables());
        assertEquals(999,features.files().getFirst().byteLength());assertEquals(req.sha256(),features.requirementSha256());assertEquals("3".repeat(64),features.inputSnapshotSha256());
        var task=new AgentTaskDTO();task.setTitle(req.title());task.setDescription(req.description());task.setRequiredAbilities(List.of("审查"));
        assertNotNull(cache.encode(OWNER,TaskRetrievalText.from(task,metadata)).block());assertEquals(1,calls.get());assertEquals(features.digest(),quotes.prepare(OWNER,input).digest());
        var changed=quotes.observe(req,List.of("审查"),new cn.jia.agent.service.AgentTaskExecutionGrantService.InputCatalogue("4".repeat(64),List.of(file)),metadata);
        assertNotEquals(features.digest(),quotes.prepare(OWNER,changed).digest());assertEquals(1,calls.get());
        var serialized=new tools.jackson.databind.ObjectMapper().writeValueAsString(features);assertFalse(serialized.contains("秘密"));assertFalse(serialized.contains("private-file"));
    }
    @Test void replayAndConflictOnClosedTaskNeverPrepareOrReadNewInputs(){
        var root=new cn.jia.agent.entity.AgentTaskMetaEntity();root.setRewardStatus("completed");
        var roots=mock(cn.jia.agent.service.AgentTaskMutationTransaction.class);
        doAnswer(call -> ((cn.jia.agent.service.AgentTaskMutationTransaction.LockedTaskMutation<?>)call.getArgument(4)).apply(root))
            .when(roots).executeWithLockedTaskRootInOwnerScope(eq(OWNER.tenantId()),eq(OWNER.clientId()),eq(OWNER.ownerJiacn()),eq("task"),any());
        var batches=mock(cn.jia.economy.task.TaskQuoteBatches.class);
        var requirements=mock(cn.jia.agent.service.AgentTaskRequirementSnapshotService.class);var grants=mock(cn.jia.agent.service.AgentTaskExecutionGrantService.class);
        var agents=mock(cn.jia.agent.service.AgentService.class);var materials=mock(RecommendationMaterials.class);var features=mock(QuoteFeaturePreparation.class);
        var service=new TaskQuoteIntentService(roots,batches,requirements,grants,agents,materials,features);
        var intent=new cn.jia.economy.task.TaskQuoteBatches.Intent("3",List.of("agent"));var original=new cn.jia.economy.task.TaskQuoteBatches.Result("task","3",11,List.of());
        when(batches.replay(any(),eq(intent))).thenReturn(original);assertSame(original,((TaskQuoteIntentService.Replay)service.begin(OWNER,"task","key",intent)).stored());
        var different=new cn.jia.economy.task.TaskQuoteBatches.Intent("4",List.of("agent"));when(batches.replay(any(),eq(different))).thenThrow(new cn.jia.economy.task.TaskQuoteBatches.IntentConflict());
        assertThrows(cn.jia.economy.task.TaskQuoteBatches.IntentConflict.class,()->service.begin(OWNER,"task","key",different));verifyNoInteractions(requirements,grants,agents,materials,features);
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
