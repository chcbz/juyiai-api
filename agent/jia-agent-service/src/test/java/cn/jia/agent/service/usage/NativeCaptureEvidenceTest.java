package cn.jia.agent.service.usage;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeCaptureEvidenceTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    @Test void dispatchGroupIsOptionalImmutablePrivateAndDoesNotGrantCoverage(){
        var plan=new HashMap<String,Object>(Map.of("captureProtocol","METERED_CAPTURE_V2","executionConfigSha256","a".repeat(64),"engineVersion","fixture","coveragePolicyVersion","unverified","rootSourceId","root"));
        NativeCaptureEvidence.planKeys(plan);var old=NativeCaptureEvidence.bindingDigest("PLAN",plan,List.of());
        var group=new HashMap<String,Object>(Map.of("version","NATIVE_DISPATCH_GROUP_V1","executionConfigSha256","b".repeat(64),"routeSha256","c".repeat(64),"toolConfigSha256","d".repeat(64)));
        plan.put("dispatchGroup",group);NativeCaptureEvidence.planKeys(plan);assertNotEquals(old,NativeCaptureEvidence.bindingDigest("PLAN",plan,List.of()));
        var fold=new NativeCaptureCoverage("cap",null);assertNull(fold.dispatchGroup());
        var r=new cn.jia.economy.usage.UsageCaptureProjection.Row("record",1,"1","f".repeat(64),JSON.writeValueAsString(Map.of("captureId","cap","recordId","record","kind","PLAN","observation",plan)));
        fold.accept(r);assertEquals("c".repeat(64),fold.dispatchGroup().routeSha256());assertEquals(r.evidenceSha256(),fold.dispatchGroup().planEvidenceSha256());assertNotEquals(cn.jia.economy.usage.UsageCaptureProjection.Coverage.COMPLETE,fold.finish().state());
        group.put("providerUrl","private");assertThrows(IllegalArgumentException.class,()->NativeCaptureEvidence.planKeys(plan));group.remove("providerUrl");
        group.put("routeSha256","private");assertThrows(IllegalArgumentException.class,()->NativeCaptureEvidence.planKeys(plan));
    }
    @Test void closedImageSourcePinsExactInvocationAndBothPhaseDigests(){
        var img=new HashMap<String,Object>(Map.of("invocationId","11111111-1111-4111-8111-111111111111","operation","generate","childRouteSha256","a".repeat(64),"startedSha256","b".repeat(64),"terminalSha256","c".repeat(64)));
        var o=new HashMap<String,Object>(Map.of("planEvidenceSha256","d".repeat(64),"sourceCount","1","sourcesSha256","e".repeat(64),"closeState","SEALED_INCOMPLETE","reasonCodes",List.of(),"imageInvocation",img));
        NativeCaptureEvidence.captureFinalKeys(o);var original=NativeCaptureEvidence.bindingDigest("CAPTURE_FINAL",o,List.of());
        img.put("terminalSha256","d".repeat(64));assertNotEquals(original,NativeCaptureEvidence.bindingDigest("CAPTURE_FINAL",o,List.of()));
        img.put("url","secret");assertThrows(IllegalArgumentException.class,()->NativeCaptureEvidence.captureFinalKeys(o));img.remove("url");
        img.put("invocationId","other/run");assertThrows(IllegalArgumentException.class,()->NativeCaptureEvidence.captureFinalKeys(o));
    }
    @Test void sealedImageSampleReadsOnlyTwoPinnedLedgerRevisionsAndNeverWritesACost(){
        var costs=org.mockito.Mockito.mock(cn.jia.economy.usage.UsageCostService.class);var decoder=new NativeSampleResponseDecoder(costs);
        var task=new cn.jia.economy.task.TaskBillingService.Task(new cn.jia.economy.service.EconomyScope("t","c"),"owner","task");
        String id="11111111-1111-4111-8111-111111111111", cap=NativeCaptureEvidence.captureIdentity("exec","run"),rid=NativeCaptureEvidence.digest(List.of("USAGE_RECORD_V2",cap,"CAPTURE_FINAL",List.of()));
        var binding=new cn.jia.economy.usage.UsageCostService.Binding(task.scope(),"owner","task","NATIVE_PROVIDER_CALL","exec:"+id);
        var start=new HashMap<String,Object>(Map.of("schemaVersion",1,"invocationId",id,"protocol","openai-images-v1","operation","generate","channel","g","tier","s","requestedModel","im","priceVersion","v1","phase","STARTED"));start.put("evidence",null);
        var evidence=Map.of("providerRequestId","request1","usage",Map.of("input_tokens_details",Map.of("text_tokens",3,"image_tokens",0),"input_tokens",3,"output_tokens",12,"total_tokens",15));
        var end=new HashMap<>(start);end.put("evidence",evidence);end.put("phase","TERMINAL");
        var invocation=new HashMap<String,Object>(Map.of("invocationId",id,"operation","generate","childRouteSha256","a".repeat(64),"startedSha256",NativeCaptureEvidence.digest(start),"terminalSha256",NativeCaptureEvidence.digest(end)));
        var o=Map.of("closeState","DRAINED","reasonCodes",List.of(),"imageInvocation",invocation);var e=Map.of("executionId","exec","runId","run","kind","CAPTURE_FINAL","captureId",cap,"recordId",rid,"sourceRevision","1","observation",o);
        java.util.function.Supplier<cn.jia.economy.usage.ConfirmedSampleEvidence.Response> row=()->new cn.jia.economy.usage.ConfirmedSampleEvidence.Response(1,cap,1,rid,"1","final-hash",JSON.writeValueAsString(e),"exec","run","CAPTURE_FINAL",true);
        var qty=ImageUsageNormalizer.normalize("g","im","s",JSON.valueToTree(evidence));assertNotNull(qty);
        org.mockito.Mockito.when(costs.observation(binding,"started")).thenReturn(Optional.of(new cn.jia.economy.usage.UsageCostService.Observation(binding,"started",0,JSON.writeValueAsString(start),null,null,1)));
        assertNull(decoder.decodeChild(task,row.get()));
        org.mockito.Mockito.when(costs.observation(binding,"terminal")).thenReturn(Optional.of(new cn.jia.economy.usage.UsageCostService.Observation(binding,"terminal",1,JSON.writeValueAsString(end),qty,null,2)));
        var fact=decoder.decodeChild(task,row.get());assertNotNull(fact);assertEquals("generate",fact.component());assertEquals("NATIVE_PROVIDER_CALL",fact.kind());assertEquals("exec:"+id,fact.operation().externalId());
        assertEquals(new cn.jia.economy.pricing.MeasuredTaskRepricing.Tokens(qty.normalizationBasis(),qty.tokens()),fact.quantity());assertEquals(fact,decoder.decodeChild(task,row.get()));
        invocation.put("operation","edit");assertNull(decoder.decodeChild(task,row.get()));invocation.put("operation","generate");invocation.put("terminalSha256","c".repeat(64));assertNull(decoder.decodeChild(task,row.get()));
        org.mockito.Mockito.verify(costs,org.mockito.Mockito.never()).record(org.mockito.ArgumentMatchers.any());
    }
    @Test void requestOutcomeCanChangeButAttemptCannot(){
        var identity=List.of("source","0","1");var o=new HashMap<String,Object>();o.put("attemptId","a");o.put("resolution","PENDING");
        String first=NativeCaptureEvidence.bindingDigest("REQUEST",o,identity);o.put("resolution","RESPONSE");
        assertEquals(first,NativeCaptureEvidence.bindingDigest("REQUEST",o,identity));o.put("attemptId","b");
        assertNotEquals(first,NativeCaptureEvidence.bindingDigest("REQUEST",o,identity));
    }
    @Test void responseOriginNeedsOriginalPlanAndChannelAndRejectsRehashedForeignProviderId() throws Exception {
        var records=new HashMap<String,String>();String response=null,id=null;
        try(var in=getClass().getResourceAsStream("/usage/usage-capture-v2-fixtures.json")){
            for(var message:JSON.readTree(in).get("cases").get(0).get("messages")){
                var d=message.get("request").get("body").get("data");String kind=d.get("kind").asText();
                if(kind.equals("RESPONSE")){response=d.toString();id=d.get("observation").get("sourceAttemptId").asText();break;}
                records.put(d.get("recordId").asText(),d.toString());
            }
        }
        var persisted=(tools.jackson.databind.node.ObjectNode)JSON.readTree(response);var decode=new NativeSampleResponseDecoder(org.mockito.Mockito.mock(cn.jia.economy.usage.UsageCostService.class));assertFalse(decode.coversAllOperations("NATIVE_DISPATCH_GROUP_V1","NATIVE_EXECUTION_UNVERIFIED_V1"));assertTrue(decode.coversAllOperations("NATIVE_DISPATCH_GROUP_V1","TEXT_MODEL_ONLY_V1"));
        var q=(tools.jackson.databind.node.ObjectNode)persisted.path("observation").path("measuredQuantities");
        java.util.function.Supplier<cn.jia.economy.usage.ConfirmedSampleEvidence.Response> row=()->new cn.jia.economy.usage.ConfirmedSampleEvidence.Response(1, persisted.get("captureId").asText(),3,persisted.get("recordId").asText(),persisted.get("sourceRevision").asText(),"h",persisted.toString(),null,null,"RESPONSE",false);
        assertNull(decode.decode(row.get()));q.put("normalizationBasis","RESPONSES_TEXT_TOKENS_V1");var decoded=decode.decode(row.get());assertEquals(id,decoded.operation().externalId());
        assertEquals(100L,((cn.jia.economy.pricing.MeasuredTaskRepricing.Tokens)decoded.quantity()).disjoint().get(cn.jia.economy.pricing.ModelTokenPrice.Category.INPUT));
        assertNull(NativeResponseOrigin.resolve(response,null,k->null).attemptId());
        assertEquals(id,NativeResponseOrigin.resolve(response,null,records::get).attemptId());
        var tree=(tools.jackson.databind.node.ObjectNode)JSON.readTree(response);var obs=(tools.jackson.databind.node.ObjectNode)tree.get("observation");
        obs.putNull("reportedUsage");String missing=tree.toString();
        assertEquals(id,NativeResponseOrigin.resolve(response,missing,records::get).attemptId());
        assertTrue(NativeResponseOrigin.resolve(missing,response,records::get).conflict());
        var changed=(tools.jackson.databind.node.ObjectNode)JSON.readTree(response);var tokens=(tools.jackson.databind.node.ObjectNode)changed.get("observation").get("measuredQuantities").get("tokens");
        tokens.put("CACHED_INPUT","0");assertEquals(id,NativeResponseOrigin.resolve(changed.toString(),response,records::get).attemptId());
        tokens.put("CACHED_INPUT","1");assertTrue(NativeResponseOrigin.resolve(changed.toString(),response,records::get).conflict());
        assertTrue(NativeResponseOrigin.resolve(response.replace("\"input_tokens\":100","\"input_tokens\":101"),response,records::get).conflict());
        assertTrue(NativeResponseOrigin.resolve(response.replace("response_1\"","different\""),null,records::get).conflict());
    }
    @Test void captureFinalMayRebindExactRevisionsButNotGrowTheClosedSourceSet(){
        var id=List.<String>of();var close=new HashMap<String,Object>();close.put("sourceCount","2");
        var first=NativeCaptureEvidence.bindingDigest("CAPTURE_FINAL",close,id);
        close.put("sourcesSha256","new");assertEquals(first,NativeCaptureEvidence.bindingDigest("CAPTURE_FINAL",close,id));
        close.put("sourceCount","3");assertNotEquals(first,NativeCaptureEvidence.bindingDigest("CAPTURE_FINAL",close,id));
    }
    @Test void responsesAdapterCrossChecksProviderCountersBeforeLedgerAssociation(){
        String basis="RESPONSES_TEXT_TOKENS_V1", channel="test", provider="response";
        String attempt=NativeCaptureEvidence.digest(List.of("NATIVE_RESPONSE_USAGE",channel,provider));
        var raw=Map.of("input_tokens",100,"output_tokens",20,"total_tokens",120,"input_tokens_details",Map.of("cached_tokens",30),"output_tokens_details",Map.of("reasoning_tokens",6));
        var measured=Map.of("normalizationBasis",basis,"coverage","COMPLETE","tokens",Map.of("INPUT","70","CACHED_INPUT","30","OUTPUT","20"));
        var obs=new HashMap<String,Object>(Map.of("sourceId","s","epoch","0","providerResponseId",provider,"sourceAttemptId",attempt,"reportedUsage",raw,"measuredQuantities",measured));obs.put("actualBillingIdentity",null);
        var envelope=Map.of("captureId","c","observedAt",1,"observation",obs);
        String source=JSON.writeValueAsString(Map.of("observation",Map.of("sourceKind","METERED_HTTP_RESPONSES_V1","channelNamespace",channel,"adapterVersion",basis)));
        String good=JSON.writeValueAsString(envelope);
        assertEquals(attempt,NativeResponseOrigin.resolve(good,null,k->source).attemptId());
        for(String bad:List.of(good.replace("\"70\"","\"100\""),good.replace(":120",":121"),good.replace(":6}",":21}"),good.replace(":30}",":101}"))){
            assertTrue(NativeResponseOrigin.resolve(bad,null,k->source).conflict());
        }
        assertTrue(NativeResponseOrigin.resolve(good,null,k->source.replace(basis,"UNKNOWN")).conflict());
        obs.put("reportedUsage",null);assertTrue(NativeResponseOrigin.resolve(JSON.writeValueAsString(envelope),null,k->source).conflict());
        obs.put("measuredQuantities",null);var partial=NativeResponseOrigin.resolve(JSON.writeValueAsString(envelope),null,k->source);
        assertEquals(attempt,partial.attemptId());assertNull(partial.facts().quantities());
    }
    @Test void requestMayWaitForItsOwnResponseButNeverRebindAfterResolution(){
        String pending="{\"kind\":\"REQUEST\",\"captureId\":\"c\",\"observation\":{\"sourceId\":\"s\",\"epoch\":\"0\",\"resolution\":\"PENDING\",\"sourceAttemptId\":null}}";
        var resolved=pending.replace("PENDING","RESPONSE").replace(":null",":\"r\"");
        var db=new HashMap<String,cn.jia.economy.usage.UsageCaptureJournal.LinkedEvidence>();
        var src=NativeCaptureEvidence.digest(List.of("USAGE_RECORD_V2","c","SOURCE",List.of("s","0")));
        var res=NativeCaptureEvidence.digest(List.of("USAGE_RECORD_V2","c","RESPONSE",List.of("r")));
        assertEquals("WAITING",NativeSourceLineage.metadata(pending,null,db::get).name());
        db.put(src,new cn.jia.economy.usage.UsageCaptureJournal.LinkedEvidence(true,"{}"));
        assertEquals("ASSOCIATED",NativeSourceLineage.metadata(pending,null,db::get).name());
        assertEquals("WAITING",NativeSourceLineage.metadata(resolved,pending,db::get).name());
        db.put(res,new cn.jia.economy.usage.UsageCaptureJournal.LinkedEvidence(true,resolved));
        assertEquals("ASSOCIATED",NativeSourceLineage.metadata(resolved,pending,db::get).name());
        assertEquals("CONFLICT",NativeSourceLineage.metadata(resolved.replace("\"r\"","\"other\""),resolved,db::get).name());
        assertEquals("CONFLICT",NativeSourceLineage.metadata(pending,resolved,db::get).name());
        db.put(res,new cn.jia.economy.usage.UsageCaptureJournal.LinkedEvidence(true,resolved.replace("\"0\"","\"1\"")));
        assertEquals("CONFLICT",NativeSourceLineage.metadata(resolved,pending,db::get).name());
    }
    @Test void exactClosureFoldsBothSharedRuntimeCasesWithoutGrantingProductionFixtureAuthority() throws Exception {
        try(var in=getClass().getResourceAsStream("/usage/usage-capture-v2-fixtures.json")){
            for(var c:JSON.readTree(in).get("cases")){
                var heads=new TreeMap<String,cn.jia.economy.usage.UsageCaptureProjection.Row>();String cap=null;
                for(var message:c.get("messages")){
                    var d=message.get("request").get("body").get("data");cap=d.get("captureId").asText();String id=d.get("recordId").asText();
                    heads.put(id,new cn.jia.economy.usage.UsageCaptureProjection.Row(id,d.get("sourceRevision").asLong(),d.get("sourceRevision").asText(),d.get("evidenceSha256").asText(),d.toString()));
                }
                var policy=new NativeCaptureCoverage.Policy("50876c00dce84757e268800163453b94487a2c0684124c09a6becaccbc6f4919","codex-cli-0.167.0-fixture","MODEL_ONLY_FIXTURE_V1","FIXTURE_V1","FIXTURE_NON_OVERLAPPING_V1","fixture_gateway");
                var fold=new NativeCaptureCoverage(cap,policy);heads.values().forEach(fold::accept);var result=fold.finish();
                var expected=c.get("expectedAfterAllMessages");assertEquals(expected.get("coverageState").asText(),result.state().name());assertEquals(expected.get("observedPartialTokenTotal").asText(),result.observedTokens().toString());
                var foreign=new NativeCaptureCoverage(cap,new NativeCaptureCoverage.Policy(policy.configSha(),policy.engine(),policy.policy(),policy.adapter(),policy.basis(),"other-channel"));
                heads.values().forEach(foreign::accept);var rejected=foreign.finish();assertEquals("INCOMPLETE",rejected.state().name());assertEquals(0,rejected.observedTokens().signum());
                var prod=new NativeCaptureCoverage(cap,null);heads.values().forEach(prod::accept);assertEquals("INCOMPLETE",prod.finish().state().name());
                var pending=new NativeCaptureCoverage(cap,policy);var first=heads.firstEntry().getValue();pending.accept(new cn.jia.economy.usage.UsageCaptureProjection.Row(first.recordId(),first.revisions()+1,first.sourceRevision(),first.evidenceSha256(),first.evidenceJson()));
                heads.tailMap(first.recordId(),false).values().forEach(pending::accept);assertEquals("WAITING_EVIDENCE",pending.finish().state().name());
            }
        }
    }
    @Test void closedTwoRequestProjectionUsesFrozenAdmissionNotReportedProfileOrCurrentRollout(){
        // Associated-head boundary: HTTP codec/journal/recovery are verified independently with MySQL.
        String cap="a".repeat(64),cfg="b".repeat(64),route="c".repeat(64),tool="d".repeat(64),channel="e".repeat(64);
        for(String fault:List.of("none","requestReason","responseReason")){
        var heads=new TreeMap<String,cn.jia.economy.usage.UsageCaptureProjection.Row>();
        class Fixture { String add(String kind,Map<String,Object> o){String id=String.format("%064x",heads.size()+1);
            var data=Map.of("kind",kind,"captureId",cap,"recordId",id,"observation",o);String sha=NativeCaptureEvidence.digest(data);
            heads.put(id,new cn.jia.economy.usage.UsageCaptureProjection.Row(id,1,"1",sha,JSON.writeValueAsString(data)));return sha;}
            String hash(int from,int to){return NativeCaptureEvidence.digest(heads.values().stream().skip(from).limit(to-from).map(r->List.of(r.recordId(),r.sourceRevision(),r.evidenceSha256())).toList());}}
        var f=new Fixture();var group=Map.of("version","NATIVE_DISPATCH_GROUP_V1","executionConfigSha256",cfg,"routeSha256",route,"toolConfigSha256",tool);
        String p=f.add("PLAN",Map.of("rootSourceId","root","snapshotSha256",cap,"executionConfigSha256","f".repeat(64),"engineVersion","codex-cli-0.167.0","coveragePolicyVersion","NATIVE_LOCAL_ARTIFACT_TEXT_V1","dispatchGroup",group));
        f.add("SOURCE",Map.of("sourceId","root","epoch","0","adapterVersion","RESPONSES_TEXT_TOKENS_V1","sourceKind","METERED_HTTP_RESPONSES_V1","channelNamespace",channel));
        for(int i=1;i<=2;i++){f.add("REQUEST",Map.of("sourceId","root","epoch","0","sequence",""+i,"resolution","RESPONSE","sourceAttemptId","r"+i,"reasonCodes",List.of(fault.equals("requestReason")?"FUTURE_TOOL":"BILLING_IDENTITY_UNKNOWN")));
            f.add("RESPONSE",Map.of("sourceId","root","epoch","0","reasonCodes",List.of(fault.equals("responseReason")?"USAGE_INCOMPLETE":"BILLING_IDENTITY_UNKNOWN"),
                "reportedUsage",Map.of("input_tokens",4,"output_tokens",1,"total_tokens",5,"input_tokens_details",Map.of("cached_tokens",1),"output_tokens_details",Map.of("reasoning_tokens",0)),
                "measuredQuantities",Map.of("coverage","COMPLETE","normalizationBasis","RESPONSES_TEXT_TOKENS_V1","tokens",Map.of("INPUT","3","CACHED_INPUT","1","OUTPUT","1"))));}
        f.add("SOURCE_FINAL",Map.of("sourceId","root","epoch","0","requestCount","2","responseCount","2","reservedSequenceHighWatermark","2","recordsSha256",f.hash(1,6),"closeState","DRAINED","reasonCodes",List.of()));
        f.add("CAPTURE_FINAL",Map.of("planEvidenceSha256",p,"sourceCount","1","sourcesSha256",f.hash(6,7),"closeState","DRAINED","reasonCodes",List.of()));
        String approved=NativeCaptureEvidence.digest(List.of("NATIVE_LOCAL_ARTIFACT_TEXT_V1","codex-cli-0.167.0","RESPONSES_TEXT_TOKENS_V1",cfg,route,tool,channel));
        for(String pin:Arrays.asList(null,approved,NativeCaptureCoverage.profileSha(cfg,route,tool,"other"),NativeCaptureCoverage.profileSha(cfg,route,"other",channel))){
            var fold=NativeCaptureCoverage.forAdmission(cap,pin);heads.values().forEach(fold::accept);var result=fold.finish();boolean match=approved.equals(pin);
            assertEquals(match&&fault.equals("none")?"COMPLETE":"INCOMPLETE",result.state().name());assertEquals(match&&(fault.equals("none")||fault.equals("requestReason"))?10:0,result.observedTokens().intValueExact());}
        }
    }
    @Test void openCaptureHandlerUsesOnlyScopedFinalLookupAndRevisionCas(){
        var store=org.mockito.Mockito.mock(cn.jia.economy.usage.UsageCaptureProjection.class);
        var worker=new NativeCaptureProjection(store,null,new cn.jia.core.security.FixedTenantAdmissionPolicy("t","c"),null);
        var key=new cn.jia.economy.usage.UsageCaptureProjection.Key("t","c",7);var cap="a".repeat(64);
        org.mockito.Mockito.when(store.pending(key)).thenReturn(new cn.jia.economy.usage.UsageCaptureProjection.Snapshot(19,cap,"o","task","a","e","r"));
        var schedule=new cn.jia.task.scheduling.ScheduleKey("c",worker.getName(),"7","");
        worker.executeScheduled(new cn.jia.task.scheduling.ScheduledInvocation("t",schedule,1));
        org.mockito.Mockito.verify(store).pending(key);org.mockito.Mockito.verify(store).hasAssociated(key,NativeCaptureEvidence.digest(List.of("USAGE_RECORD_V2",cap,"CAPTURE_FINAL",List.of())));
        org.mockito.Mockito.verify(store).publish(key,19,cn.jia.economy.usage.UsageCaptureProjection.Coverage.WAITING_EVIDENCE);
        org.mockito.Mockito.verifyNoMoreInteractions(store);
    }
    @Test void onlyRootedSourceAndEarlierEpochCanAuthorizeANewSource(){
        String cap="a".repeat(64);var db=new HashMap<String,cn.jia.economy.usage.UsageCaptureJournal.LinkedEvidence>();
        String plan=NativeCaptureEvidence.digest(List.of("USAGE_RECORD_V2",cap,"PLAN",List.of()));
        db.put(plan,new cn.jia.economy.usage.UsageCaptureJournal.LinkedEvidence(true,"{\"observation\":{\"rootSourceId\":\"root\"}}"));
        var root=source(cap,"root","0",null,null);var rootId=JSON.readTree(root).get("recordId").asText();
        assertEquals("ASSOCIATED",NativeSourceLineage.resolve(root,db::get).name());
        var child=source(cap,"child","0",rootId,null);assertEquals("WAITING",NativeSourceLineage.resolve(child,db::get).name());
        db.put(rootId,new cn.jia.economy.usage.UsageCaptureJournal.LinkedEvidence(true,root));
        assertEquals("ASSOCIATED",NativeSourceLineage.resolve(child,db::get).name());
        String childId=JSON.readTree(child).get("recordId").asText();var resume=source(cap,"child","9007199254740993123456",rootId,childId);
        db.put(childId,new cn.jia.economy.usage.UsageCaptureJournal.LinkedEvidence(false,child));
        assertEquals("WAITING",NativeSourceLineage.resolve(resume,db::get).name());
        db.put(childId,new cn.jia.economy.usage.UsageCaptureJournal.LinkedEvidence(true,child));
        assertEquals("ASSOCIATED",NativeSourceLineage.resolve(resume,db::get).name());
        assertEquals("CONFLICT",NativeSourceLineage.resolve(source(cap,"child","1",rootId,rootId),db::get).name());
        assertEquals("CONFLICT",NativeSourceLineage.resolve(source(cap,"child","0",childId,null),db::get).name());
    }
    private String source(String cap,String id,String epoch,String parent,String prev){
        var facts=new HashMap<String,Object>();facts.put("sourceId",id);facts.put("epoch",epoch);facts.put("parentSourceRecordId",parent);facts.put("previousEpochSourceRecordId",prev);
        return JSON.writeValueAsString(Map.of("captureId",cap,"kind","SOURCE","recordId",NativeCaptureEvidence.digest(List.of("USAGE_RECORD_V2",cap,"SOURCE",List.of(id,epoch))),"observation",facts));
    }
    @Test void fixedRuntimeMessagesHavePortableJavaDigestsAndClosedScope() throws Exception {
        try(var in=getClass().getResourceAsStream("/usage/usage-capture-v2-fixtures.json")){
            var fixture=JSON.readTree(Objects.requireNonNull(in));int count=0;
            for(var c:fixture.get("cases"))for(var m:c.get("messages")){
                var req=m.get("request");var data=JSON.convertValue(req.get("body").get("data"),Map.class);
                var receipt=req.get("headers").get("Idempotency-Key").asText();String exec=(String)data.get("executionId"),run=(String)data.get("runId");
                var result=NativeCaptureEvidence.parse(exec,run,receipt,data);count++;
                assertEquals(m.get("ack").get("body").get("data").get("evidenceSha256").asText(),result.evidenceSha256());
                assertThrows(IllegalArgumentException.class,()->NativeCaptureEvidence.parse("foreign",run,receipt,data));
                assertThrows(IllegalArgumentException.class,()->NativeCaptureEvidence.parse(exec,run,"different",data));
                if(result.kind().equals("RESPONSE")){
                    var o=(Map)data.get("observation");o.put("reportedUsage",Map.of("authorization","fixture-only"));
                    var rehashed=new HashMap(data);for(var key:List.of("expectedRevision","receiptId","evidenceSha256"))rehashed.remove(key);data.put("evidenceSha256",NativeCaptureEvidence.digest(rehashed));
                    assertThrows(IllegalArgumentException.class,()->NativeCaptureEvidence.parse(exec,run,receipt,data));
                }
            }
            assertEquals(19,count);
        }
    }
}
