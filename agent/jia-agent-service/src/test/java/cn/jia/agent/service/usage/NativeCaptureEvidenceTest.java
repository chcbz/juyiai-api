package cn.jia.agent.service.usage;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeCaptureEvidenceTest {
    private static final ObjectMapper JSON=new ObjectMapper();
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
