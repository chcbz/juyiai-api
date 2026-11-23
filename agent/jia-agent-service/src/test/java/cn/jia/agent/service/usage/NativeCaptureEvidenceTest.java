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
        assertNull(NativeResponseOrigin.resolve(response,k->null).attemptId());
        assertEquals(id,NativeResponseOrigin.resolve(response,records::get).attemptId());
        assertTrue(NativeResponseOrigin.resolve(response.replace("response_1\"","different\""),records::get).conflict());
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
