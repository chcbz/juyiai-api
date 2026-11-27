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
import cn.jia.economy.pricing.GroupMeanPricing;
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
    @Test void editingAndReferenceImagesHaveTheSameLocalSizeFactsButDoNotSupplyAnOperation(){
        var file=new cn.jia.agent.service.AgentTaskExecutionGrantService.AuthorizedInput("poster",1,"INPUT","image/png",789,"2".repeat(64));
        var catalogue=new cn.jia.agent.service.AgentTaskExecutionGrantService.InputCatalogue("3".repeat(64),List.of(file));
        var observation=new RecommendationMaterials.Observation("rel",1,"poster",1,file.contentHash(),789,new RecommendationMaterials.Material("poster","poster","image/png",1,"INPUT","ACTIVE",null));
        for(var text:List.of("把这张图的日期改成周六","参考这张图另做一张音乐节海报")){
            var req=new cn.jia.agent.service.AgentTaskRequirementSnapshotService.CurrentSnapshot("task",5,3,"海报",text,"1".repeat(64),"CREATE");
            var facts=QuoteFeaturePreparation.localFacts(req,catalogue,List.of(observation));
            assertNull(facts.operation());assertNull(facts.requestedDeliverables());assertEquals(789,facts.files().getFirst().byteLength());
            assertEquals(req.sha256(),facts.requirementSha256());assertEquals(catalogue.digest(),facts.inputSnapshotSha256());
            assertThrows(TaskQuoteIntentService.Stale.class,()->QuoteFeaturePreparation.localFacts(req,catalogue,List.of()));
        }
    }
    @Test void replayAndConflictOnClosedTaskNeverPrepareOrReadNewInputs(){
        var root=new cn.jia.agent.entity.AgentTaskMetaEntity();root.setRewardStatus("completed");
        var roots=mock(cn.jia.agent.service.AgentTaskMutationTransaction.class);
        doAnswer(call -> ((cn.jia.agent.service.AgentTaskMutationTransaction.LockedTaskMutation<?>)call.getArgument(4)).apply(root))
            .when(roots).executeWithLockedTaskRootInOwnerScope(eq(OWNER.tenantId()),eq(OWNER.clientId()),eq(OWNER.ownerJiacn()),eq("task"),any());
        var batches=mock(cn.jia.economy.task.TaskQuoteBatches.class);
        var requirements=mock(cn.jia.agent.service.AgentTaskRequirementSnapshotService.class);var grants=mock(cn.jia.agent.service.AgentTaskExecutionGrantService.class);
        var agents=mock(cn.jia.agent.service.AgentExternalServicePolicyService.class);var materials=mock(RecommendationMaterials.class);var features=mock(QuoteFeaturePreparation.class);
        var service=new TaskQuoteIntentService(roots,batches,requirements,grants,agents,materials);
        var intent=new cn.jia.economy.task.TaskQuoteBatches.Intent("3",List.of("agent"));var original=new cn.jia.economy.task.TaskQuoteBatches.Result("task","3",11,List.of());
        when(batches.replay(any(),eq(intent))).thenReturn(original);assertSame(original,((TaskQuoteIntentService.Replay)service.begin(OWNER,"task","key",intent)).stored());
        var different=new cn.jia.economy.task.TaskQuoteBatches.Intent("4",List.of("agent"));when(batches.replay(any(),eq(different))).thenThrow(new cn.jia.economy.task.TaskQuoteBatches.IntentConflict());
        assertThrows(cn.jia.economy.task.TaskQuoteBatches.IntentConflict.class,()->service.begin(OWNER,"task","key",different));verifyNoInteractions(requirements,grants,agents,materials);
    }
    @Test void confirmedScopeTravelsThroughOrdinaryIntentAndFinishRejectsWithdrawalBeforeWriter(){
        var root=new cn.jia.agent.entity.AgentTaskMetaEntity();root.setRewardStatus("open");root.setTaskVersion(3L);
        var roots=mock(cn.jia.agent.service.AgentTaskMutationTransaction.class);
        doAnswer(call -> ((cn.jia.agent.service.AgentTaskMutationTransaction.LockedTaskMutation<?>)call.getArgument(4)).apply(root))
            .when(roots).executeWithLockedTaskRootInOwnerScope(eq(OWNER.tenantId()),eq(OWNER.clientId()),eq(OWNER.ownerJiacn()),eq("task"),any());
        var batches=mock(TaskQuoteBatches.class);
        var requirements=mock(cn.jia.agent.service.AgentTaskRequirementSnapshotService.class);
        var grants=mock(cn.jia.agent.service.AgentTaskExecutionGrantService.class);
        var agents=mock(cn.jia.agent.service.AgentExternalServicePolicyService.class);
        var materials=mock(RecommendationMaterials.class);
        var payload=new cn.jia.agent.requirement.ConfirmedScope.Payload(cn.jia.agent.requirement.ConfirmedScope.VERSION,
            List.of(new cn.jia.agent.requirement.ConfirmedScope.WorkItem("w1","text.summary","1",Map.of(),Map.of(),List.of(),List.of())),"private acceptance");
        var digest="a".repeat(64);
        var scope=new cn.jia.agent.requirement.ConfirmedScope(2,payload.requirementDigest("title",null,digest),
            payload.digest(digest),digest,cn.jia.agent.requirement.ConfirmedScope.VERSION,100,payload.workItems(),payload.acceptanceText());
        var current=new cn.jia.agent.service.AgentTaskRequirementSnapshotService.CurrentSnapshot("task",3,2,"title",null,scope.requirementSha256(),"CONFIRM",scope);
        var catalogue=new cn.jia.agent.service.AgentTaskExecutionGrantService.InputCatalogue(digest,List.of());
        when(requirements.readCurrentWithinLockedTask(any(),eq("task"),same(root))).thenReturn(current);
        when(grants.snapshotTaskInputsWithinLockedTask(any(),eq("task"))).thenReturn(catalogue);
        when(materials.observe(OWNER,"task")).thenReturn(List.of());
        var targets=List.of(new cn.jia.agent.service.AgentExternalServicePolicyService.QuoteTarget("agent","owner",7,0));
        when(agents.quoteTargets(any(),eq("task"),eq(List.of("agent")),anyBoolean())).thenReturn(targets);
        var service=new TaskQuoteIntentService(roots,batches,requirements,grants,agents,materials);
        var intent=new TaskQuoteBatches.Intent("3",List.of("agent"));
        var fresh=(TaskQuoteIntentService.Fresh)service.begin(OWNER,"task","key",intent);
        assertSame(scope,fresh.confirmedScope());assertSame(scope,fresh.withFacts(fresh.facts()).confirmedScope());
        when(batches.commit(eq(fresh.key()),eq(intent),any())).thenAnswer(call->{
            java.util.function.Function<String,List<TaskQuoteBatches.Item>> writer=call.getArgument(2);
            return new TaskQuoteBatches.Result("task","3",100,writer.apply("batch"));
        });
        var writes=new AtomicInteger();
        service.finish(OWNER,fresh,batch->{writes.incrementAndGet();return List.of();});
        assertEquals(1,writes.get());
        // Even a malformed same-version projection cannot silently drop confirmed scope.
        when(requirements.readCurrentWithinLockedTask(any(),eq("task"),same(root))).thenReturn(
            new cn.jia.agent.service.AgentTaskRequirementSnapshotService.CurrentSnapshot("task",3,2,"title",null,scope.requirementSha256(),"CONFIRM"));
        assertThrows(TaskQuoteIntentService.Stale.class,()->service.finish(OWNER,fresh,batch->{writes.incrementAndGet();return List.of();}));
        assertEquals(1,writes.get());
        assertThrows(TaskQuoteIntentService.Stale.class,()->QuoteFeaturePreparation.localFacts(current,
            new cn.jia.agent.service.AgentTaskExecutionGrantService.InputCatalogue("b".repeat(64),List.of()),List.of()));
        var corrupt=new cn.jia.agent.service.AgentTaskRequirementSnapshotService.CurrentSnapshot("task",3,9,"title",null,scope.requirementSha256(),"CONFIRM",scope);
        assertThrows(IllegalStateException.class,()->QuoteFeaturePreparation.localFacts(corrupt,catalogue,List.of()));
    }
    @Test void ordinaryBudgetBatchesAllCandidatesAndReplayNeverWrites(){
        var intents=mock(TaskQuoteIntentService.class);var billing=mock(TaskBillingService.class);
        var registry=io.micrometer.observation.ObservationRegistry.create();var stages=new java.util.ArrayList<String>();
        registry.observationConfig().observationHandler(new io.micrometer.observation.ObservationHandler<io.micrometer.observation.Observation.Context>(){
            public boolean supportsContext(io.micrometer.observation.Observation.Context c){return true;}
            public void onStop(io.micrometer.observation.Observation.Context c){
                assertEquals("cyf.task.quote.batch.stage",c.getName());assertFalse(c.getHighCardinalityKeyValues().iterator().hasNext());
                var tags=new java.util.TreeMap<String,String>();c.getLowCardinalityKeyValues().forEach(k->tags.put(k.getKey(),k.getValue()));assertEquals(java.util.Set.of("stage","outcome"),tags.keySet());stages.add(tags.get("stage")+":"+tags.get("outcome"));
            }
        });
        var app=new IndependentTaskQuotes(intents,new CandidateGroupOffers(org.mockito.Mockito.mock(cn.jia.economy.task.GroupBudgetQuotes.class),billing),org.mockito.Mockito.mock(RegisteredGroupQuoteInputs.class),java.util.Optional.of(registry));var intent=new TaskQuoteBatches.Intent("3",List.of("b","a"));
        var task=new TaskBillingService.Task(OWNER.scope(),OWNER.ownerJiacn(),"task");var key=new TaskQuoteBatches.Key(task,OWNER.actorId(),"key");
        var facts=new QuoteFeaturePreparation.LocalFacts(1,"a".repeat(64),"b".repeat(64),List.of(),null,null);
        var targets=intent.agentIds().stream().map(id->new cn.jia.agent.service.AgentExternalServicePolicyService.QuoteTarget(id,"owner",7,0)).toList();
        var fresh=new TaskQuoteIntentService.Fresh(key,intent,facts,targets,List.of());when(intents.begin(OWNER,"task","key",intent)).thenReturn(fresh);
        when(intents.finish(eq(OWNER),eq(fresh),any())).thenAnswer(c->new TaskQuoteBatches.Result("task","3",12,c.<java.util.function.Function<String,List<TaskQuoteBatches.Item>>>getArgument(2).apply("batch")));
        List<TaskBillingService.OfferWrite> writes=intent.agentIds().stream().<TaskBillingService.OfferWrite>map(id->new TaskBillingService.FixedWrite(
            new TaskBillingService.Binding(1,facts.requirementSha256(),facts.inputSnapshotSha256(),id,"batch"),new cn.jia.economy.task.GroupBudgetQuotes.Fixed(TaskQuoteBatches.Reason.OUT_OF_DISTRIBUTION))).toList();
        when(billing.storeOffersBatch(task,OWNER.actorId(),writes)).thenReturn(List.of(new TaskBillingService.StoredQuote("q_a",1),new TaskBillingService.StoredQuote("q_b",1)));
        var result=app.quote(OWNER,"task","key",intent);assertEquals(List.of("q_a","q_b"),result.quotes().stream().map(TaskQuoteBatches.Item::quoteId).toList());
        assertTrue(result.quotes().stream().allMatch(q->q.amount().equals(cn.jia.economy.common.Money.wen(1))));
        verify(billing).storeOffersBatch(task,OWNER.actorId(),writes);verifyNoMoreInteractions(billing);clearInvocations(billing);
        when(intents.begin(OWNER,"task","key",intent)).thenReturn(new TaskQuoteIntentService.Replay(result));assertSame(result,app.quote(OWNER,"task","key",intent));verifyNoInteractions(billing);
        when(intents.begin(OWNER,"task","key",intent)).thenThrow(new TaskQuoteIntentService.CandidateNotAuthorized());
        assertThrows(IndependentTaskQuotes.NotFound.class,()->app.quote(OWNER,"task","key",intent));
        assertEquals(List.of("intent_features:ok","configuration:ok","baseline:ok","finish:ok","intent_features:ok","intent_features:error"),stages);
    }
    @Test void structuredBatchSharesGroupReadButNeverSharesQuoteOrExecutionCommitments(){
        var groups=mock(cn.jia.economy.task.GroupBudgetQuotes.class);var billing=mock(TaskBillingService.class);var offers=new CandidateGroupOffers(groups,billing);
        var task=new TaskBillingService.Task(OWNER.scope(),OWNER.ownerJiacn(),"task");var intent=new TaskQuoteBatches.Intent("3",List.of("a","b","c"));
        var targets=intent.agentIds().stream().map(id->new cn.jia.agent.service.AgentExternalServicePolicyService.QuoteTarget(id,"owner",7,0)).toList();
        var sha="a".repeat(64);var facts=new QuoteFeaturePreparation.LocalFacts(1,sha,sha,List.of(),null,"edit");
        var fresh=new TaskQuoteIntentService.Fresh(new TaskQuoteBatches.Key(task,OWNER.actorId(),"key"),intent,facts,targets,List.of());
        var measured=new TaskBillingService.MeasuredGroup("d",sha,sha,"v",sha,"coverage");
        var routes=List.of(new GroupMeanPricing.Route("MODEL_RESPONSE","INFERENCE","chan","text","base","RESPONSES_TEXT_TOKENS_V1"),
            new GroupMeanPricing.Route("NATIVE_PROVIDER_CALL","edit","chan","image","base","openai-images-modalities-v1"));
        var policy=new GroupMeanPricing.Policy("f1","rule","context-compat",new GroupMeanPricing.Buckets("edit",List.of(new GroupMeanPricing.Bucket("B0",Map.of(GroupMeanPricing.Dimension.INPUT_FILE_COUNT,new GroupMeanPricing.Range(0,2))))),2);
        var config=new GroupMeanPricing.Configuration("ctx","1","tool","capture",routes);
        var a=CandidateGroupOffers.classify(facts,policy,config,measured,null);var b=CandidateGroupOffers.classify(facts,policy,config,measured,sha);
        var group=((CandidateGroupOffers.ComparableGroup)a).group();var key=new cn.jia.economy.task.GroupBudgetQuotes.Key(GroupMeanPricing.key(group));var pin=new cn.jia.economy.task.GroupBudgetQuotes.Pin(key,3,4,4,sha,policy.sha(),2,new java.math.BigDecimal("0.59"));
        var mean=new cn.jia.economy.task.GroupBudgetQuotes.Mean(pin,30);when(groups.prepare(task.scope(),java.util.Set.of(key))).thenReturn(Map.of(key,mean));
        var missing=new CandidateGroupOffers.Missing(TaskQuoteBatches.Reason.BILLING_ROUTE_MISSING);
        var batch=offers.prepare(fresh,Map.of("a",a,"b",b,"c",missing));
        when(billing.storeOffersBatch(eq(task),eq(OWNER.actorId()),any())).thenAnswer(call->{List<TaskBillingService.OfferWrite> writes=call.getArgument(2);
            assertNull(((TaskBillingService.MeanWrite)writes.get(0)).initialIntentSha256());assertEquals(sha,((TaskBillingService.MeanWrite)writes.get(1)).initialIntentSha256());
            assertEquals(missing.reason(),((TaskBillingService.FixedWrite)writes.get(2)).price().reason());return List.of(new TaskBillingService.StoredQuote("a1",30),new TaskBillingService.StoredQuote("b1",30),new TaskBillingService.StoredQuote("c1",1));});
        var rows=offers.issue(batch,"batch");assertEquals(List.of("a1","b1","c1"),rows.stream().map(TaskQuoteBatches.Item::quoteId).toList());
        assertEquals(List.of("GROUP_MEAN","GROUP_MEAN","FIXED_FALLBACK"),rows.stream().map(r->r.estimateSummary().method()).toList());
        verify(groups).prepare(task.scope(),java.util.Set.of(key));verify(groups).lockSelected(task.scope(),java.util.Set.of(pin));verifyNoMoreInteractions(groups);
    }
    private record ScopedOffersFixture(TaskQuoteIntentService.Fresh fresh,GroupMeanPricing.ScopeGroup group,
            cn.jia.economy.task.ScopedTaskQuoteSnapshot.PricingProfilePin profile) {}
    private static ScopedOffersFixture scopedOffersFixture(){
        var sha="a".repeat(64);
        var payload=new cn.jia.agent.requirement.ConfirmedScope.Payload(cn.jia.agent.requirement.ConfirmedScope.VERSION,
            List.of(new cn.jia.agent.requirement.ConfirmedScope.WorkItem("w1","document.extract","1",Map.of(),Map.of(),List.of(),List.of()),
                    new cn.jia.agent.requirement.ConfirmedScope.WorkItem("w2","text.summary","1",Map.of(),Map.of(),List.of(),List.of("w1"))),"private delivery");
        var scope=new cn.jia.agent.requirement.ConfirmedScope(1,payload.requirementDigest("title",null,sha),payload.digest(sha),sha,
                cn.jia.agent.requirement.ConfirmedScope.VERSION,100,payload.workItems(),payload.acceptanceText());
        var intent=new TaskQuoteBatches.Intent("3",List.of("a","b","c","d"));
        var targets=intent.agentIds().stream().map(id->new cn.jia.agent.service.AgentExternalServicePolicyService.QuoteTarget(id,"owner",7,0)).toList();
        var task=new TaskBillingService.Task(OWNER.scope(),OWNER.ownerJiacn(),"task");
        var fresh=new TaskQuoteIntentService.Fresh(new TaskQuoteBatches.Key(task,OWNER.actorId(),"key"),intent,
            new QuoteFeaturePreparation.LocalFacts(1,scope.requirementSha256(),sha,List.of(),null,null),targets,List.of(),null,scope);
        var group=new GroupMeanPricing.ScopeGroup("scope-v1",sha,List.of(
            new GroupMeanPricing.ScopeSlot(0,"document.extract","1",sha,List.of()),
            new GroupMeanPricing.ScopeSlot(1,"text.summary","1",sha,List.of(0))),List.of("small","short"),Map.of("context","actual-context"));
        var profile=new cn.jia.economy.task.ScopedTaskQuoteSnapshot.PricingProfilePin("install","instance",1,1,sha,"profile",1,sha,sha);
        return new ScopedOffersFixture(fresh,group,profile);
    }
    @Test void scopedOffersShareWholeGroupAndPreserveUnavailableCandidateWithoutInventingExecution(){
        var f=scopedOffersFixture();var groups=mock(cn.jia.economy.task.GroupBudgetQuotes.class);var billing=mock(TaskBillingService.class);
        var service=new CandidateGroupOffers(groups,billing);var group=f.group();var key=new cn.jia.economy.task.GroupBudgetQuotes.Key(group.key());
        var pin=new cn.jia.economy.task.GroupBudgetQuotes.Pin(key,3,4,5,"b".repeat(64),group.policySha(),2,new java.math.BigDecimal("0.14"));
        var mean=new cn.jia.economy.task.GroupBudgetQuotes.Mean(pin,7);
        when(groups.prepare(OWNER.scope(),java.util.Set.of(key))).thenReturn(Map.of(key,mean));
        var price=new CandidateGroupOffers.ScopedGroup(group,f.profile());
        var prepared=service.prepare(f.fresh(),Map.of("a",price,"b",new CandidateGroupOffers.Unavailable(),
            "c",price,"d",new CandidateGroupOffers.Missing(TaskQuoteBatches.Reason.COST_INPUT_MISSING)));
        when(billing.storeOffersBatch(eq(f.fresh().key().task()),eq(OWNER.actorId()),any())).thenAnswer(call->{
            List<TaskBillingService.OfferWrite> writes=call.getArgument(2);assertEquals(3,writes.size());
            assertEquals(List.of("a","c","d"),writes.stream().map(w->w.binding().agentId()).toList());
            for(int i=0;i<writes.size();i++){
                var snapshot=((TaskBillingService.ScopedWrite)writes.get(i)).snapshot();
                assertEquals(f.fresh().confirmedScope().scopeSha256(),snapshot.scopePin().scopeSha256());
                assertNull(snapshot.executionBinding());assertNull(snapshot.initialIntentSha256());
                assertEquals(i==2?1:7,snapshot.amountWen());
                if(i==2)assertEquals(TaskQuoteBatches.Reason.COST_INPUT_MISSING,snapshot.basis().reason());
                else {assertEquals(group.key(),snapshot.basis().mean().groupSha());assertEquals(f.profile(),snapshot.pricingProfilePin());}
            }
            return List.of(new TaskBillingService.StoredQuote("qa",7),new TaskBillingService.StoredQuote("qc",7),new TaskBillingService.StoredQuote("qd",1));
        });
        var rows=service.issue(prepared,"batch");
        assertEquals(List.of("a","b","c","d"),rows.stream().map(TaskQuoteBatches.Item::agentId).toList());
        assertEquals("qc",rows.get(2).quoteId());assertEquals("qd",rows.get(3).quoteId());
        var denied=rows.get(1);assertEquals(TaskQuoteBatches.State.UNAVAILABLE,denied.state());
        assertEquals(TaskQuoteBatches.Reason.CAPABILITY_MISMATCH,denied.unavailableReason());assertNull(denied.amount());assertNull(denied.quoteId());
        var ordered=inOrder(groups,billing);ordered.verify(groups).prepare(OWNER.scope(),java.util.Set.of(key));
        ordered.verify(groups).lockSelected(OWNER.scope(),java.util.Set.of(pin));ordered.verify(billing).storeOffersBatch(any(),any(),any());
        verifyNoMoreInteractions(groups,billing);
    }
    @Test void scopedOffersRejectIncompleteShapesAndNeverWriteAllUnavailableBatch(){
        var f=scopedOffersFixture();var groups=mock(cn.jia.economy.task.GroupBudgetQuotes.class);var billing=mock(TaskBillingService.class);
        var service=new CandidateGroupOffers(groups,billing);var denied=new CandidateGroupOffers.Unavailable();
        var bases=new java.util.HashMap<String,CandidateGroupOffers.Basis>();for(var id:f.fresh().intent().agentIds())bases.put(id,denied);
        var rows=service.issue(service.prepare(f.fresh(),bases),"batch");assertEquals(4,rows.size());
        assertTrue(rows.stream().allMatch(r->r.state()==TaskQuoteBatches.State.UNAVAILABLE));verifyNoInteractions(groups,billing);
        var partial=new GroupMeanPricing.ScopeGroup("scope-v1",f.group().policySha(),List.of(f.group().scopeShape().getFirst()),List.of("small"),Map.of("context","actual-context"));
        bases.put("a",new CandidateGroupOffers.ScopedGroup(partial,f.profile()));
        assertThrows(IllegalArgumentException.class,()->service.prepare(f.fresh(),bases));verifyNoInteractions(groups,billing);
        bases.put("a",new CandidateGroupOffers.ScopedGroup(f.group(),f.profile()));
        var missingScope=new TaskQuoteIntentService.Fresh(f.fresh().key(),f.fresh().intent(),f.fresh().facts(),f.fresh().candidates(),List.of());
        assertThrows(NullPointerException.class,()->service.prepare(missingScope,bases));verifyNoInteractions(groups,billing);
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
