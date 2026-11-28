package cn.jia.agent.service.recommendation;

import cn.jia.agent.hosting.HostingRentHttp.Actor;
import cn.jia.agent.service.PersonalWorkspaceInputMetadata;
import cn.jia.agent.mapper.skill.CapabilityQuoteSpecMapper.SpecKey;
import cn.jia.agent.requirement.ConfirmedScope;
import cn.jia.agent.requirement.ConfirmedScope.*;
import cn.jia.agent.security.*;
import cn.jia.agent.service.AgentExternalServicePolicyService.QuoteTarget;
import cn.jia.agent.skill.*;
import cn.jia.agent.skill.quote.*;
import cn.jia.economy.pricing.*;
import cn.jia.economy.task.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ScopedGroupQuoteInputsTest {
    private static final String H="a".repeat(64);
    private static final Actor ACTOR=new Actor("payer","tenant","client","owner");
    private static final CostRouteReference REF=new CostRouteReference("MODEL_RESPONSE","INFERENCE","provider","model","TOKENS_V1");
    private static final CostPricingRoute.TokenRoute ROUTE=new CostPricingRoute.TokenRoute("channel","model","tier","TOKENS_V1");
    private static CapabilityQuoteSpec spec(String id,boolean metadata){
        return new CapabilityQuoteSpec(CapabilityQuoteSpec.SCHEMA,id,"1",
                Map.of("deliverables.count",new CapabilityQuoteSpec.Field(ValueType.INTEGER,"unit/items",true,"1","100",Set.of(),null)),
                List.of(new CapabilityQuoteSpec.CostDimension("size",metadata?CapabilityQuoteSpec.Source.INPUT_SUM:CapabilityQuoteSpec.Source.DELIVERABLE,
                        metadata?"bytes":"count",ValueType.INTEGER,metadata?"unit/bytes":"unit/items",metadata?"real-parser-v1":null,Set.of())),
                List.of(new CapabilityQuoteSpec.Bucket("small",Map.of("size",new CapabilityQuoteSpec.Range("0",metadata?"4096":"3",Set.of())))));
    }
    private static final class Fixture {
        final RegisteredPricingProfiles profiles=mock(RegisteredPricingProfiles.class);
        final PublishedCapabilityQuoteSpecs specs=mock(PublishedCapabilityQuoteSpecs.class);
        final CostRouteBindings bindings=mock(CostRouteBindings.class);
        final ScopedGroupPolicies policies=mock(ScopedGroupPolicies.class);
        final ScopedGroupQuoteInputs service=new ScopedGroupQuoteInputs(profiles,specs,new ResolvedCostProfiles(bindings),policies);
        final Map<SpecKey,CapabilityQuoteSpec> declarations=new HashMap<>();
        final Map<String,RegisteredPricingProfiles.CapabilityObserved> capabilities=new HashMap<>();
        final Map<String,RegisteredPricingProfiles.Observed> reports=new HashMap<>();
        final Map<GroupMeanPricing.ScopeInput,ScopedGroupPolicies.Selected> selections=new HashMap<>();
        final TaskQuoteIntentService.Fresh fresh;
        Fixture(List<String> capabilities,List<String> agents,boolean metadata,String count){
            var items=new ArrayList<WorkItem>();
            for(int i=0;i<capabilities.size();i++){
                String capability=capabilities.get(i);declarations.put(new SpecKey(capability,"1"),spec(capability,metadata));
                items.add(new WorkItem("w"+i,capability,"1",Map.of(),Map.of("count",new Scalar(ValueType.INTEGER,"unit/items",count)),
                        metadata?List.of("file@1"):List.of(),i==0?List.of():List.of("w"+(i-1))));
            }
            var payload=new ConfirmedScope.Payload(ConfirmedScope.VERSION,items,"private acceptance");
            var scope=new ConfirmedScope(1,payload.requirementDigest("title",null,H),payload.digest(H),H,ConfirmedScope.VERSION,100,items,payload.acceptanceText());
            var intent=new TaskQuoteBatches.Intent("3",agents);
            var targets=intent.agentIds().stream().map(a->new QuoteTarget(a,"provider-"+a,7,0)).toList();
            var task=new TaskBillingService.Task(ACTOR.scope(),ACTOR.ownerJiacn(),"task");
            var observation=new RecommendationMaterials.Observation("link",1,"file",1,H,100,
                    new RecommendationMaterials.Material("private-name","private-original","application/pdf",1,"INPUT","ACTIVE",null));
            fresh=new TaskQuoteIntentService.Fresh(new TaskQuoteBatches.Key(task,ACTOR.actorId(),"key"),intent,
                    new QuoteFeaturePreparation.LocalFacts(1,scope.requirementSha256(),H,List.of(),null,null),targets,metadata?List.of(observation):List.of(),
                    "f".repeat(64),scope); // A legacy experiment pointer must NOT select the old reader.
            for(var target:targets){
                var set=new HashSet<SkillManagementService.QuoteCapability>();
                for(int i=0;i<items.size();i++){
                    var item=items.get(i);var s=declarations.get(new SpecKey(item.capabilityId(),"1"));
                    set.add(new SkillManagementService.QuoteCapability(s.capabilityId(),"1",s.specSha(),"skill-"+i,"1",H,target.agent()+"-skill-"+i));
                }
                this.capabilities.put(target.agent(),new RegisteredPricingProfiles.CapabilityObserved(target.agent(),target.provider(),7,
                        "install-"+target.agent(),"host","instance-"+target.agent(),1,set));
            }
            when(profiles.readCapabilities(eq("tenant"),eq("client"),anyList(),anyBoolean())).thenAnswer(c->Map.copyOf(this.capabilities));
            when(profiles.read(eq("tenant"),eq("client"),anyList(),anyBoolean())).thenAnswer(c->{
                List<QuoteTarget> requested=c.getArgument(2);var result=new HashMap<String,RegisteredPricingProfiles.Observed>();
                requested.forEach(t->{if(reports.containsKey(t.agent()))result.put(t.agent(),reports.get(t.agent()));});return Map.copyOf(result);
            });
            when(specs.readActive(eq("tenant"),eq("client"),anyList())).thenAnswer(c->Map.copyOf(declarations));
            when(specs.lockActive(eq("tenant"),eq("client"),anyList())).thenAnswer(c->Map.copyOf(declarations));
            when(bindings.read(eq(ACTOR.scope()),anyList(),anyBoolean())).thenAnswer(c->{
                List<CostRouteReference> refs=c.getArgument(1);var result=new HashMap<CostRouteReference,CostRouteBindings.Binding>();
                refs.forEach(r->result.put(r,new CostRouteBindings.Binding(r,1,H,ROUTE)));return Map.copyOf(result);
            });
            when(policies.read(eq(ACTOR.scope()),anyList(),anyBoolean())).thenAnswer(c->{
                List<GroupMeanPricing.ScopeInput> inputs=c.getArgument(1);var result=new HashMap<GroupMeanPricing.ScopeInput,ScopedGroupPolicies.Selected>();
                inputs.forEach(i->{if(selections.containsKey(i))result.put(i,selections.get(i));});return Map.copyOf(result);
            });
        }
        MeasuredCostProfile measurement(String agent,String context){
            var slots=new ArrayList<MeasuredCostProfile.Slot>();
            for(int i=0;i<fresh.confirmedScope().workItems().size();i++){
                var item=fresh.confirmedScope().workItems().get(i);var s=declarations.get(new SpecKey(item.capabilityId(),"1"));
                slots.add(new MeasuredCostProfile.Slot(i,item.capabilityId(),"1",s.specSha(),i==0?List.of():List.of(i-1),"skill-"+i,"1",H,agent+"-skill-"+i));
            }
            return new MeasuredCostProfile("plan",1,0,slots,"native-1",context,H,H,"capture-1",List.of(REF));
        }
        void report(String agent,List<MeasuredCostProfile> measurements){
            var c=capabilities.get(agent);var report=new CostProfileReport(1,measurements);
            reports.put(agent,new RegisteredPricingProfiles.Observed(agent,c.provider(),c.binding(),c.installation(),c.host(),c.instance(),c.generation(),1,report.sha256(),CostProfileReportJson.write(report)));
        }
        ScopedGroupPolicies.Selected approved(String agent,String context){
            var measurement=measurement(agent,context);report(agent,List.of(measurement));
            var declared=new HashMap<ScopedCostInputs.Capability,CapabilityQuoteSpec>();declarations.forEach((key,s)->declared.put(new ScopedCostInputs.Capability(key.capabilityId(),key.version()),s));
            var inputs=ScopedCostInputs.prepare(fresh.confirmedScope(),declared,RecommendationMaterials.quoteMetadata(fresh.metadata()));
            var profile=new CostProfile(measurement,List.of(new CostProfile.Route(REF,ROUTE)));var input=inputs.input(profile).orElseThrow();
            var policy=new GroupMeanPricing.ScopedPolicy("scope-1",input.specShas(),input.scopeShapeSha(),"config-1",GroupMeanPricing.ScopedPolicy.definitionsSha(input.specShas()),3);
            var group=inputs.group(policy.featureVersion(),policy.sha(),profile).orElseThrow();
            var selected=new ScopedGroupPolicies.Selected(H,policy,group);selections.put(input,selected);return selected;
        }
        void deny(String agent){var c=capabilities.get(agent);capabilities.put(agent,new RegisteredPricingProfiles.CapabilityObserved(c.agent(),c.provider(),c.binding(),c.installation(),c.host(),c.instance(),c.generation(),Set.of()));}
    }
    private static Fixture single(){return new Fixture(List.of("text.summary"),List.of("a","b","c","d"),false,"2");}
    private static void locked(Runnable body){TransactionSynchronizationManager.setActualTransactionActive(true);try{body.run();}finally{TransactionSynchronizationManager.setActualTransactionActive(false);}}

    @Test void ordinaryConfirmedEntryProducesSevenTwelveOneAndUnavailableBeforeAnySelection(){
        var f=single();var a=f.approved("a","context-a");var b=f.approved("b","context-b");f.deny("d");
        var intents=mock(TaskQuoteIntentService.class);var old=mock(RegisteredGroupQuoteInputs.class);var prices=mock(GroupBudgetQuotes.class);var billing=mock(TaskBillingService.class);
        when(intents.begin(ACTOR,"task","key",f.fresh.intent())).thenReturn(f.fresh);
        when(prices.prepare(eq(ACTOR.scope()),anySet())).thenAnswer(c->{
            var ka=new GroupBudgetQuotes.Key(a.group().key());var kb=new GroupBudgetQuotes.Key(b.group().key());
            assertEquals(Set.of(ka,kb),c.getArgument(1));
            return Map.of(ka,new GroupBudgetQuotes.Mean(new GroupBudgetQuotes.Pin(ka,1,1,1,H,a.policy().sha(),3,new BigDecimal("0.21")),7),
                    kb,new GroupBudgetQuotes.Mean(new GroupBudgetQuotes.Pin(kb,1,1,1,H,b.policy().sha(),3,new BigDecimal("0.36")),12));
        });
        when(intents.finish(eq(ACTOR),same(f.fresh),any())).thenAnswer(c->{
            java.util.function.Function<String,List<TaskQuoteBatches.Item>> writer=c.getArgument(2);
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try{return new TaskQuoteBatches.Result("task","3",100,writer.apply("batch"));}finally{TransactionSynchronizationManager.setActualTransactionActive(false);}
        });
        when(billing.storeOffersBatch(eq(f.fresh.key().task()),eq(ACTOR.actorId()),anyList())).thenAnswer(c->{
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());List<TaskBillingService.OfferWrite> writes=c.getArgument(2);assertEquals(3,writes.size());
            var result=new ArrayList<TaskBillingService.StoredQuote>();
            for(var write:writes){var snapshot=((TaskBillingService.ScopedWrite)write).snapshot();
                assertEquals(f.fresh.confirmedScope().scopeSha256(),snapshot.scopePin().scopeSha256());assertNull(snapshot.executionBinding());
                result.add(new TaskBillingService.StoredQuote("tq_s1_"+snapshot.binding().agentId(),snapshot.amountWen()));}
            return result;
        });
        var app=new IndependentTaskQuotes(intents,new CandidateGroupOffers(prices,billing),old,f.service);
        var result=app.quote(ACTOR,"task","key",f.fresh.intent());
        assertEquals(List.of("a","b","c","d"),result.quotes().stream().map(TaskQuoteBatches.Item::agentId).toList());
        assertEquals(List.of("7","12","1"),result.quotes().subList(0,3).stream().map(q->q.amount().amountMinor()).toList());
        assertEquals(TaskQuoteBatches.State.UNAVAILABLE,result.quotes().get(3).state());assertNull(result.quotes().get(3).quoteId());
        verifyNoInteractions(old);verify(f.bindings).read(eq(ACTOR.scope()),eq(List.of(REF)),eq(false));
        var order=inOrder(f.profiles,f.specs,f.bindings,f.policies,prices,billing);
        order.verify(f.profiles).readCapabilities(eq("tenant"),eq("client"),eq(f.fresh.candidates()),eq(true));
        order.verify(f.specs).lockActive(eq("tenant"),eq("client"),anyList());order.verify(f.bindings).read(eq(ACTOR.scope()),anyList(),eq(true));
        order.verify(f.policies).read(eq(ACTOR.scope()),anyList(),eq(true));order.verify(prices).lockSelected(eq(ACTOR.scope()),anyCollection());order.verify(billing).storeOffersBatch(any(),anyString(),anyList());
        clearInvocations(billing);
        when(f.policies.read(eq(ACTOR.scope()),anyList(),eq(true))).thenThrow(new ScopedGroupPolicies.Busy());
        assertThrows(IndependentTaskQuotes.Changed.class,()->app.quote(ACTOR,"task","key",f.fresh.intent()));
        verifyNoInteractions(billing);
        clearInvocations(f.profiles,f.specs,f.bindings,f.policies,billing,old);
        when(intents.begin(ACTOR,"task","key",f.fresh.intent())).thenReturn(new TaskQuoteIntentService.Replay(result));
        assertSame(result,app.quote(ACTOR,"task","key",f.fresh.intent()));verifyNoInteractions(f.profiles,f.specs,f.bindings,f.policies,billing,old);
    }

    @Test void threeBusinessKindsUseTheSameOrdinaryConsumerWithoutCostReportsErasingAbility(){
        for(String capability:List.of("text.summary","visual.asset","audio.transcribe")){
            var f=new Fixture(List.of(capability),List.of("a","b"),false,"2");f.approved("a","context");
            var prepared=f.service.read(f.fresh);assertInstanceOf(CandidateGroupOffers.ScopedGroup.class,prepared.bases().get("a"));
            assertEquals(TaskQuoteBatches.Reason.BILLING_ROUTE_MISSING,((CandidateGroupOffers.Missing)prepared.bases().get("b")).reason());
            locked(()->f.service.recheck(prepared));
        }
    }
    @Test void completeCombinationReportIsRequiredAndMissingCostRouteDoesNotEraseIt(){
        var f=new Fixture(List.of("document.extract","text.summary"),List.of("a","b","c"),false,"1");
        f.report("a",List.of(f.measurement("a","shared")));var full=f.measurement("b","shared");var singles=new ArrayList<MeasuredCostProfile>();
        for(int i=0;i<full.scopePattern().size();i++){
            var s=full.scopePattern().get(i);singles.add(new MeasuredCostProfile("single-"+i,1,i,List.of(new MeasuredCostProfile.Slot(0,s.capabilityId(),s.capabilityVersion(),s.specSha(),List.of(),s.skillKey(),s.skillVersion(),s.packageSha(),s.installId())),"native-1","shared",H,H,"capture-1",List.of(REF)));
        }
        f.report("b",singles);when(f.bindings.read(eq(ACTOR.scope()),anyList(),anyBoolean())).thenReturn(Map.of());
        var read=f.service.read(f.fresh);
        assertEquals(TaskQuoteBatches.Reason.BILLING_ROUTE_MISSING,((CandidateGroupOffers.Missing)read.bases().get("a")).reason());
        assertInstanceOf(CandidateGroupOffers.Unavailable.class,read.bases().get("b"));assertInstanceOf(CandidateGroupOffers.Unavailable.class,read.bases().get("c"));
        verifyNoInteractions(f.policies);locked(()->f.service.recheck(read));
    }
    @Test void unparsedCostMetadataAndOutOfDistributionAvoidRouteCallsButMissingReferenceIsStale(){
        var f=new Fixture(List.of("document.extract"),List.of("a"),true,"1");f.report("a",List.of(f.measurement("a","context")));
        var read=f.service.read(f.fresh);assertEquals(TaskQuoteBatches.Reason.COST_INPUT_MISSING,((CandidateGroupOffers.Missing)read.bases().get("a")).reason());
        var metadata=RecommendationMaterials.quoteMetadata(f.fresh.metadata()).get("file@1");assertEquals(PersonalWorkspaceInputMetadata.PRODUCER,metadata.parserVersion());assertEquals("100",metadata.values().get("byteLength").value());assertFalse(metadata.values().containsKey("bytes"));
        verifyNoInteractions(f.bindings,f.policies);locked(()->f.service.recheck(read));
        var missing=new TaskQuoteIntentService.Fresh(f.fresh.key(),f.fresh.intent(),f.fresh.facts(),f.fresh.candidates(),List.of(),null,f.fresh.confirmedScope());
        assertThrows(TaskQuoteIntentService.Stale.class,()->f.service.read(missing));
        var outside=new Fixture(List.of("text.summary"),List.of("a"),false,"3");outside.report("a",List.of(outside.measurement("a","context")));
        assertEquals(TaskQuoteBatches.Reason.OUT_OF_DISTRIBUTION,((CandidateGroupOffers.Missing)outside.service.read(outside.fresh).bases().get("a")).reason());
        verifyNoInteractions(outside.bindings,outside.policies);
    }
    @Test void storedByteLengthUsesDeclaredStorageSchemaAndProducesOrdinaryGroup() {
        var f=new Fixture(List.of("document.extract"),List.of("a"),true,"1");
        var key=new SpecKey("document.extract","1");var old=f.declarations.get(key);
        f.declarations.put(key,new CapabilityQuoteSpec(old.schema(),old.capabilityId(),old.version(),old.fields(),
                List.of(new CapabilityQuoteSpec.CostDimension("size",CapabilityQuoteSpec.Source.INPUT_SUM,
                        "byteLength",ValueType.INTEGER,"unit/bytes",PersonalWorkspaceInputMetadata.SCHEMA,Set.of())),old.buckets()));
        // Rebuild the authoritative capability binding after changing the synthetic published spec.
        var c=f.capabilities.get("a");var declaration=f.declarations.get(key);
        f.capabilities.put("a",new RegisteredPricingProfiles.CapabilityObserved(c.agent(),c.provider(),c.binding(),
                c.installation(),c.host(),c.instance(),c.generation(),Set.of(new SkillManagementService.QuoteCapability(
                declaration.capabilityId(),"1",declaration.specSha(),"skill-0","1",H,"a-skill-0"))));
        var selected=f.approved("a","context");var prepared=f.service.read(f.fresh);
        assertInstanceOf(CandidateGroupOffers.ScopedGroup.class,prepared.bases().get("a"));
        assertNotNull(selected);locked(()->f.service.recheck(prepared));
    }
    @Test void storageProjectionPreservesVersionZeroAndLargeSizeWithoutInventingParserFacts() {
        var zero=PersonalWorkspaceInputMetadata.fixedVersion("f",1,"application/octet-stream",H,0);
        assertEquals("0",zero.values().get("byteLength").value());
        var large=PersonalWorkspaceInputMetadata.fixedVersion("f",2,"application/octet-stream",H,Long.MAX_VALUE);
        assertEquals("f@2",large.reference());assertEquals(2,large.fileVersion());assertEquals(H,large.contentSha256());
        assertEquals(Long.toString(Long.MAX_VALUE),large.values().get("byteLength").value());
        assertEquals(Set.of("byteLength"),large.values().keySet());
        assertThrows(IllegalStateException.class,()->PersonalWorkspaceInputMetadata.fixedVersion("f",1,"text/plain",H,-1));
        assertThrows(IllegalArgumentException.class,()->PersonalWorkspaceInputMetadata.fixedVersion("f",0,"text/plain",H,1));
        assertThrows(IllegalArgumentException.class,()->PersonalWorkspaceInputMetadata.fixedVersion("f",1,"text/plain","bad",1));
    }
    @Test void quoteStorageProjectionMatchesAdmissionProjectionAndRejectsConflictingVersions() {
        var f=new Fixture(List.of("document.extract"),List.of("a"),true,"1");var o=f.fresh.metadata().getFirst();
        assertEquals(PersonalWorkspaceInputMetadata.fixedVersion(o.fileId(),o.material().version(),
                o.material().contentMimeType(),o.sha256(),o.byteLength()),RecommendationMaterials.quoteMetadata(List.of(o)).get("file@1"));
        assertEquals(1,RecommendationMaterials.quoteMetadata(List.of(o,o)).size());
        var changed=new RecommendationMaterials.Observation(o.relationId(),o.relationRevision(),o.fileId(),
                o.metadataRevision(),o.sha256(),o.byteLength()+1,o.material());
        assertThrows(TaskQuoteIntentService.Stale.class,()->RecommendationMaterials.quoteMetadata(List.of(o,changed)));
    }
    @Test void finishRejectsCapabilityLossButIgnoresUnrelatedSkillChanges(){
        var f=single();f.approved("a","context");var prepared=f.service.read(f.fresh);var before=f.capabilities.get("a");
        var extra=new HashSet<>(before.capabilities());extra.add(new SkillManagementService.QuoteCapability("unrelated","1",H,"other","1",H,"other-install"));
        f.capabilities.put("a",new RegisteredPricingProfiles.CapabilityObserved(before.agent(),before.provider(),before.binding(),before.installation(),before.host(),before.instance(),before.generation(),extra));
        locked(()->assertDoesNotThrow(()->f.service.recheck(prepared)));
        f.deny("a");locked(()->assertThrows(TaskQuoteIntentService.Stale.class,()->f.service.recheck(prepared)));
    }
    @Test void finishUsesOriginalReportAndRoutePinsButDoesNotRepriceNewlyAvailableFallback(){
        var f=single();f.approved("a","context");var prepared=f.service.read(f.fresh);f.approved("c","new-context");
        locked(()->assertDoesNotThrow(()->f.service.recheck(prepared)));
        verify(f.profiles).read(eq("tenant"),eq("client"),eq(List.of(f.fresh.candidates().getFirst())),eq(true));
        var old=f.reports.get("a");f.reports.put("a",new RegisteredPricingProfiles.Observed(old.agent(),old.provider(),old.binding(),old.installation(),old.host(),old.instance(),old.generation(),2,old.sha(),old.canonical()));
        locked(()->assertThrows(TaskQuoteIntentService.Stale.class,()->f.service.recheck(prepared)));f.reports.put("a",old);
        when(f.bindings.read(eq(ACTOR.scope()),anyList(),eq(true))).thenReturn(Map.of(REF,new CostRouteBindings.Binding(REF,2,"b".repeat(64),ROUTE)));
        locked(()->assertThrows(TaskQuoteIntentService.Stale.class,()->f.service.recheck(prepared)));
    }
    @Test void finishRejectsWithdrawnOrReplacedPolicyAndCurrentSpecDrift(){
        var f=single();f.approved("a","context");var prepared=f.service.read(f.fresh);var original=Map.copyOf(f.selections);
        f.selections.clear();locked(()->assertThrows(TaskQuoteIntentService.Stale.class,()->f.service.recheck(prepared)));
        original.forEach((k,v)->f.selections.put(k,new ScopedGroupPolicies.Selected("c".repeat(64),v.policy(),v.group())));
        locked(()->assertThrows(TaskQuoteIntentService.Stale.class,()->f.service.recheck(prepared)));
        f.selections.clear();f.selections.putAll(original);
        var changed=spec("text.summary",true);when(f.specs.lockActive(eq("tenant"),eq("client"),anyList())).thenReturn(Map.of(new SpecKey("text.summary","1"),changed));
        locked(()->assertThrows(TaskQuoteIntentService.Stale.class,()->f.service.recheck(prepared)));
    }
    @Test void oneHundredEightCandidatesShareSemanticPolicyButKeepPrivateConfigurationPins(){
        var ids=new ArrayList<String>();for(int i=0;i<108;i++)ids.add("agent-"+i);
        var f=new Fixture(List.of("text.summary"),ids,false,"2");for(var id:ids)f.approved(id,"shared-context");
        assertEquals(1,f.selections.size());var read=f.service.read(f.fresh);assertEquals(108,read.bases().size());
        assertEquals(1,read.bases().values().stream().map(b->((CandidateGroupOffers.ScopedGroup)b).group().key()).distinct().count());
        assertEquals(108,read.bases().values().stream().map(b->((CandidateGroupOffers.ScopedGroup)b).profile().configSha()).distinct().count());
        verify(f.policies).read(eq(ACTOR.scope()),argThat(xs->xs.size()==1),eq(false));verify(f.bindings).read(eq(ACTOR.scope()),eq(List.of(REF)),eq(false));
        locked(()->f.service.recheck(read));verify(f.policies).read(eq(ACTOR.scope()),argThat(xs->xs.size()==1),eq(true));
    }
    @Test void noPolicyUsesOneWenWithoutGuessingAndTooFewSamplesNeverActivateMean(){
        var f=new Fixture(List.of("text.summary"),List.of("a"),false,"2");var policy=f.approved("a","context");f.selections.clear();
        var absent=f.service.read(f.fresh);assertEquals(TaskQuoteBatches.Reason.NO_BASELINE,((CandidateGroupOffers.Missing)absent.bases().get("a")).reason());
        f.selections.put(policy.group().input(),policy);locked(()->f.service.recheck(absent));
        verify(f.policies,never()).read(any(),anyList(),eq(true));
        var read=f.service.read(f.fresh);var prices=mock(GroupBudgetQuotes.class);var billing=mock(TaskBillingService.class);var key=new GroupBudgetQuotes.Key(policy.group().key());
        when(prices.prepare(any(),anySet())).thenReturn(Map.of(key,new GroupBudgetQuotes.Mean(new GroupBudgetQuotes.Pin(key,1,1,1,H,policy.policy().sha(),2,new BigDecimal("0.14")),7)));
        when(billing.storeOffersBatch(any(),anyString(),anyList())).thenAnswer(c->{List<TaskBillingService.OfferWrite> writes=c.getArgument(2);var snapshot=((TaskBillingService.ScopedWrite)writes.getFirst()).snapshot();assertEquals(1,snapshot.amountWen());assertEquals(TaskQuoteBatches.Reason.NO_BASELINE,snapshot.basis().reason());return List.of(new TaskBillingService.StoredQuote("fallback",1));});
        var offers=new CandidateGroupOffers(prices,billing);assertEquals("1",offers.issue(offers.prepare(f.fresh,read.bases()),"batch").getFirst().amount().amountMinor());
        verify(prices,never()).lockSelected(any(),anyCollection());
    }
    @Test void corruptApprovalOrCrossSessionReportIsNotMissingPriceFallback(){
        var f=single();f.approved("a","context");when(f.policies.read(any(),anyList(),eq(false))).thenThrow(new ScopedGroupPolicies.Corrupt());
        assertThrows(ScopedGroupPolicies.Corrupt.class,()->f.service.read(f.fresh));
        var old=f.reports.get("a");f.reports.put("a",new RegisteredPricingProfiles.Observed(old.agent(),old.provider(),old.binding(),old.installation(),old.host(),"other-instance",2,old.seq(),old.sha(),old.canonical()));
        assertThrows(TaskQuoteIntentService.Stale.class,()->f.service.read(f.fresh));
    }
}
