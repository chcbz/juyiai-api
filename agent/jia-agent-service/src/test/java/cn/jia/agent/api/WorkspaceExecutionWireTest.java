package cn.jia.agent.api;

import cn.jia.agent.service.PersonalWorkspaceExecutionService.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class WorkspaceExecutionWireTest {
    @Test void explicitNullsAndExactDecimalProjectionPreserveHistoricalFacts() throws Exception {
        var input=new RuntimeInput("in","file",1,"input.png","image/png",0,"a".repeat(64));
        var view=new ExecutionView("execution","task","run",null,"agent","QUEUED",null,null,Long.MAX_VALUE,null,List.of(input),null,"CONVERSATION",null,null,null);
        var json=JsonMapper.builder().build().readTree(JsonMapper.builder().build().writeValueAsString(WorkspaceExecutionWire.from(view)));
        assertEquals(16,json.size());assertEquals(Long.toString(Long.MAX_VALUE),json.path("grantRevision").asText());
        assertTrue(json.path("runtimeCommand").isNull());assertEquals("1",json.path("inputs").get(0).path("version").asText());
        assertEquals("0",json.path("inputs").get(0).path("byteLength").asText());assertEquals(1,input.version());
    }
    @Test void retiredHistoryNeverContainsAnExecutableCommand() throws Exception {
        var view=new ExecutionView("execution","task","run",null,"agent","QUEUED",null,null,1L,
                "text/plain",List.of(),null,"PRIVATE",null,null,null,"UNSUPPORTED_RETIRED_PROTOCOL");
        var mapper=JsonMapper.builder().build();
        var json=mapper.readTree(mapper.writeValueAsString(WorkspaceExecutionWire.from(view)));
        assertTrue(json.path("runtimeCommand").isNull());
        assertEquals("UNSUPPORTED_RETIRED_PROTOCOL",json.path("recoveryStatus").asText());
        assertEquals(1,ConversationCommandView.class.getPermittedSubclasses().length);
        assertEquals(cn.jia.agent.service.SkillExecutionService.Command.class,
                ConversationCommandView.class.getPermittedSubclasses()[0]);
    }
}
