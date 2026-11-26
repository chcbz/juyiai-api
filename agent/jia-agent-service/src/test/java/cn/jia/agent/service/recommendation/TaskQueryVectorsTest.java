package cn.jia.agent.service.recommendation;

import cn.jia.agent.entity.AgentTaskDTO;
import cn.jia.economy.task.TaskBudgetEstimator;
import cn.jia.economy.task.TaskQuoteBatches;
import cn.jia.economy.task.TaskBillingService;
import cn.jia.agent.hosting.HostingRentHttp.Actor;
import cn.jia.agent.service.TaskFeatureEncoder;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import cn.jia.economy.pricing.TaskReferencePricing;
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
        var agents=mock(cn.jia.agent.service.AgentExternalServicePolicyService.class);var materials=mock(RecommendationMaterials.class);var features=mock(QuoteFeaturePreparation.class);
        var service=new TaskQuoteIntentService(roots,batches,requirements,grants,agents,materials,features);
        var intent=new cn.jia.economy.task.TaskQuoteBatches.Intent("3",List.of("agent"));var original=new cn.jia.economy.task.TaskQuoteBatches.Result("task","3",11,List.of());
        when(batches.replay(any(),eq(intent))).thenReturn(original);assertSame(original,((TaskQuoteIntentService.Replay)service.begin(OWNER,"task","key",intent)).stored());
        var different=new cn.jia.economy.task.TaskQuoteBatches.Intent("4",List.of("agent"));when(batches.replay(any(),eq(different))).thenThrow(new cn.jia.economy.task.TaskQuoteBatches.IntentConflict());
        assertThrows(cn.jia.economy.task.TaskQuoteBatches.IntentConflict.class,()->service.begin(OWNER,"task","key",different));verifyNoInteractions(requirements,grants,agents,materials,features);
    }
    @Test void independentBudgetSeparatesPrepareFromCommitAndDoesNotTouchBillingForUnavailableOrReplayedIntent(){
        var intents=mock(TaskQuoteIntentService.class);var budget=mock(TaskBudgetEstimator.class);var billing=mock(TaskBillingService.class);
        var app=new IndependentTaskQuotes(intents,java.util.Optional.empty(),budget,billing);var intent=new TaskQuoteBatches.Intent("3",List.of("agent"));
        var task=new TaskBillingService.Task(OWNER.scope(),OWNER.ownerJiacn(),"task");var key=new TaskQuoteBatches.Key(task,OWNER.actorId(),"key");
        var f=mock(cn.jia.economy.pricing.TaskEstimateFeatures.class);var fresh=new TaskQuoteIntentService.Fresh(key,intent,1,f,List.of(new cn.jia.agent.service.AgentExternalServicePolicyService.QuoteTarget("agent","owner",7,0)),List.of());
        when(intents.begin(OWNER,"task","key",intent)).thenReturn(fresh);when(budget.prepare(task,f,null)).thenReturn(new TaskBudgetEstimator.Unavailable(TaskQuoteBatches.Reason.BILLING_ROUTE_MISSING));
        when(intents.finish(eq(OWNER),eq(fresh),any())).thenAnswer(call->{var items=call.<java.util.function.Function<String,List<TaskQuoteBatches.Item>>>getArgument(2).apply("batch");return new TaskQuoteBatches.Result("task","3",12,items);});
        var result=app.quote(OWNER,"task","key",intent);assertEquals(TaskQuoteBatches.Reason.BILLING_ROUTE_MISSING,result.quotes().getFirst().unavailableReason());assertNull(result.quotes().getFirst().amount());
        verify(budget).lockSelected(task,List.of());verifyNoInteractions(billing);clearInvocations(budget);
        when(intents.begin(OWNER,"task","key",intent)).thenReturn(new TaskQuoteIntentService.Replay(result));assertSame(result,app.quote(OWNER,"task","key",intent));verifyNoInteractions(budget,billing);
        when(intents.begin(OWNER,"task","key",intent)).thenThrow(new TaskFeatureEncoder.Unavailable());when(intents.replay(OWNER,"task","key",intent)).thenReturn(result);
        assertSame(result,app.quote(OWNER,"task","key",intent));verifyNoInteractions(budget,billing);
    }
    @Test void batchPreparesOncePerExactExecutionAndPricingContractButPreservesEachTarget() {
        var intents=mock(TaskQuoteIntentService.class);var source=mock(ProspectiveTaskExecution.class);
        var estimator=mock(TaskBudgetEstimator.class);var billing=mock(TaskBillingService.class);
        var app=new IndependentTaskQuotes(intents,java.util.Optional.of(source),estimator,billing);
        var intent=new TaskQuoteBatches.Intent("3",List.of("c","b","a"));var task=new TaskBillingService.Task(OWNER.scope(),OWNER.ownerJiacn(),"task");
        var features=mock(cn.jia.economy.pricing.TaskEstimateFeatures.class);var targets=intent.agentIds().stream()
                .map(id->new cn.jia.agent.service.AgentExternalServicePolicyService.QuoteTarget(id,"owner",7,0)).toList();
        var fresh=new TaskQuoteIntentService.Fresh(new TaskQuoteBatches.Key(task,OWNER.actorId(),"key"),intent,1,features,targets,List.of());
        var sha="a".repeat(64);var group=new TaskBillingService.MeasuredGroup("dispatch",sha,sha,"route",sha,"coverage");
        var terms=new TaskBudgetEstimator.Terms(group,Map.of("op",Map.of("cost",new TaskReferencePricing.ZeroRoute("one"))));
        var different=new TaskBudgetEstimator.Terms(group,Map.of("op",Map.of("cost",new TaskReferencePricing.ZeroRoute("two"))));
        var proofs=Map.of("a",new ProspectiveTaskExecution.Evidence("revision-a",terms),"b",new ProspectiveTaskExecution.Evidence("revision-b",new TaskBudgetEstimator.Terms(group,terms.routes())),"c",new ProspectiveTaskExecution.Evidence("revision-c",different));
        when(intents.begin(OWNER,"task","key",intent)).thenReturn(fresh);when(source.observe(any(),eq(targets))).thenReturn(proofs);
        when(source.current(any(),eq(targets),eq(proofs))).thenReturn(true);
        when(estimator.prepare(task,features,terms)).thenReturn(new TaskBudgetEstimator.Unavailable(TaskQuoteBatches.Reason.NO_BASELINE));
        when(estimator.prepare(task,features,different)).thenReturn(new TaskBudgetEstimator.Unavailable(TaskQuoteBatches.Reason.PRICE_MISSING));
        when(intents.finish(eq(OWNER),eq(fresh),any())).thenAnswer(c->new TaskQuoteBatches.Result("task","3",12,
                c.<java.util.function.Function<String,List<TaskQuoteBatches.Item>>>getArgument(2).apply("batch")));
        when(features.requirementSha256()).thenReturn(sha);when(features.inputSnapshotSha256()).thenReturn(sha);
        when(billing.storeFixedFallback(eq(task),any(),eq(OWNER.actorId()),any())).thenAnswer(c->new TaskBillingService.StoredQuote("q_"+c.<TaskBillingService.Binding>getArgument(1).agentId(),1));
        var rows=app.quote(OWNER,"task","key",intent).quotes();assertEquals(intent.agentIds(),rows.stream().map(TaskQuoteBatches.Item::agentId).toList());
        assertEquals(List.of("q_a","q_b","q_c"),rows.stream().map(TaskQuoteBatches.Item::quoteId).toList());assertTrue(rows.stream().allMatch(r->r.state()==TaskQuoteBatches.State.AVAILABLE));
        verify(estimator).prepare(task,features,terms);verify(estimator).prepare(task,features,different);verify(estimator).lockSelected(task,List.of());verifyNoMoreInteractions(estimator);
    }
    @Test void featureStageObservationsDistinguishHitFromEncodeAcrossBothOperations() {
        var registry=io.micrometer.observation.ObservationRegistry.create();
        var events=new java.util.concurrent.CopyOnWriteArrayList<String>();
        registry.observationConfig().observationHandler(new io.micrometer.observation.ObservationHandler<io.micrometer.observation.Observation.Context>() {
            public boolean supportsContext(io.micrometer.observation.Observation.Context c){return true;}
            public void onStop(io.micrometer.observation.Observation.Context c){
                var labels=new java.util.TreeMap<String,String>();c.getLowCardinalityKeyValues().forEach(k->labels.put(k.getKey(),k.getValue()));
                assertFalse(c.getHighCardinalityKeyValues().iterator().hasNext());events.add(c.getName()+labels);
            }
        });
        var vectors=new TaskQueryVectors(new TaskFeatureEncoder(){public Identity identity(){return ID;}
            public Vector encode(String text){if(text.contains("invalid"))throw new InvalidResult();return VALUE;}},2,1,Duration.ofMinutes(1),java.util.Optional.of(registry));
        vectors.encode(OWNER,input("private title")).block();vectors.encodeQuote(OWNER,input("private title")).block();
        assertThrows(TaskFeatureEncoder.InvalidResult.class,()->vectors.encodeQuote(OWNER,input("invalid")).block());
        assertEquals(List.of("cyf.task.feature.cache{operation=recommendation, path=miss}","cyf.task.feature.encode{operation=recommendation, outcome=ok}",
                "cyf.task.feature.cache{operation=quote, path=hit}","cyf.task.feature.cache{operation=quote, path=miss}","cyf.task.feature.encode{operation=quote, outcome=invalid}"),events);
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
