package cn.jia.chat.service;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChatActionExecutionServiceTest {
    @Test void answerAndArtifactContractsRemainDistinctAndDigestBound() {
        var answer=ChatActionExecutionService.nativeOutputs(Map.of("outputMode","ANSWER"),List.of("text/plain"));
        assertEquals(List.of(),answer.get("mimeTypes"));assertEquals(false,answer.get("multiple"));
        assertEquals(false,answer.get("artifactsRequired"));
        assertEquals(Map.of("$schema","https://json-schema.org/draft/2020-12/schema","type","object",
                "properties",Map.of("answer",Map.of("type","string","minLength",1,"pattern","\\S")),
                "required",List.of("answer"),"additionalProperties",false),answer.get("dataSchema"));
        var files=ChatActionExecutionService.nativeOutputs(Map.of("outputMode","ARTIFACTS"),List.of("text/plain"));
        assertEquals(Map.of("mimeTypes",List.of("text/plain"),"multiple",true,"artifactsRequired",true),files);
        assertNotEquals(ChatSkillSnapshotJson.sha(answer),ChatSkillSnapshotJson.sha(files));
        assertEquals(ChatSkillSnapshotJson.sha(answer),ChatSkillSnapshotJson.sha(ChatActionExecutionService.nativeOutputs(Map.of("outputMode","ANSWER"),List.of("text/plain"))));
    }
    @Test void committedFirstFileNarrowsButNeverExtendsTheCurrentNativeCapability(){
        var fixed=new cn.jia.agent.service.NativeTaskPreparation.Output(cn.jia.agent.service.NativeTaskPreparation.Mode.ARTIFACTS,List.of("text/plain"));
        var broad=List.of("application/pdf","text/plain");var files=Map.<String,Object>of("outputMode","ARTIFACTS");
        assertEquals(fixed.commandOutputs(),ChatActionExecutionService.nativeOutputs(files,broad,fixed));
        assertEquals(broad,ChatActionExecutionService.nativeOutputs(files,broad,null).get("mimeTypes"));
        assertThrows(ChatDeliberationException.class,()->ChatActionExecutionService.nativeOutputs(files,List.of("application/pdf"),fixed));
        assertThrows(ChatDeliberationException.class,()->ChatActionExecutionService.nativeOutputs(Map.of("outputMode","ANSWER"),broad,fixed));
    }
    @Test void schemaRequiresExplicitOutputIntentAndDoesNotPermitExtraParameters() {
        var schema=ChatActionCapabilityService.nativeParametersSchema();
        assertEquals(List.of("outputMode"),schema.get("required"));assertEquals(false,schema.get("additionalProperties"));
        assertEquals(List.of("ANSWER","ARTIFACTS"),((Map<?,?>)((Map<?,?>)schema.get("properties")).get("outputMode")).get("enum"));
    }
    @SuppressWarnings("unchecked") @Test void realOutcomeCodecValidatesRequiredModeBeforeAdmission() throws Exception {
        Map<String,Object> fixture;
        try(var stream=getClass().getResourceAsStream("/contracts/unified-action-outcome-v5.json")) {
            fixture=tools.jackson.databind.json.JsonMapper.builder().build().readValue(stream,Map.class);
        }
        var facts=new LinkedHashMap<String,Object>((Map<String,Object>)fixture.get("facts"));
        var capability=new LinkedHashMap<String,Object>(((List<Map<String,Object>>)facts.get("availableActions")).getFirst());
        capability.put("operation","EXECUTE_NATIVE");capability.put("skill",null);
        capability.put("parametersSchema",ChatActionCapabilityService.nativeParametersSchema());
        facts.put("schemaVersion",6);facts.put("availableActions",List.of(capability));
        var parsed=ChatActionOutcomeContract.facts(facts);
        var requested=new LinkedHashMap<String,Object>(((List<Map<String,Object>>)fixture.get("outcomes")).stream()
                .filter(o->"ACTION_REQUEST".equals(o.get("kind"))).findFirst().orElseThrow());
        var selection=new LinkedHashMap<String,Object>((Map<String,Object>)requested.get("action"));
        selection.put("actionId",capability.get("actionId"));requested.put("action",selection);
        for(String mode:List.of("ANSWER","ARTIFACTS")) {
            selection.put("parameters",Map.of("outputMode",mode));
            assertEquals(mode,ChatActionOutcomeContract.outcome(requested,parsed).action().parameters().get("outputMode"));
        }
        for(var invalid:List.of(Map.of(),Map.of("outputMode","AUTO"),Map.of("outputMode","ANSWER","extra",true),Collections.singletonMap("outputMode",null))) {
            selection.put("parameters",invalid);
            assertThrows(IllegalArgumentException.class,()->ChatActionOutcomeContract.outcome(requested,parsed));
        }
    }
}
