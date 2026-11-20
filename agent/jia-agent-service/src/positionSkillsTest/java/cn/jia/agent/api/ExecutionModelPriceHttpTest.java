package cn.jia.agent.api;

import cn.jia.agent.position.PositionSkillException;
import cn.jia.agent.security.*;
import cn.jia.agent.service.*;
import cn.jia.agent.service.PersonalWorkspaceExecutionService.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.*;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ExecutionModelPriceHttpTest {
    @Test void lookupUsesFrozenGrantAndCannotReadAfterRevocationOrExpiredLease() throws Exception {
        var executions=mock(SkillExecutionService.class);var authority=mock(ModelMaintenanceAuthority.class);
        var prices=mock(ModelMaintenanceOperations.class);
        var mvc=MockMvcBuilders.standaloneSetup(new ExecutionModelPriceController(executions,authority,prices)).build();
        var identity=new AgentRuntimeAuthentication.Scope("tenant","client","executor","agent","instance");
        var principal=mock(AgentRuntimeAuthentication.class);
        when(principal.isAuthenticated()).thenReturn(true);when(principal.getPrincipal()).thenReturn(identity);
        when(principal.getDetails()).thenReturn(new AgentRuntimeAuthenticationService.Proof(identity,"install","host",1,"digest",1,1));
        var caller=new RuntimeScope("tenant","client","executor","agent","instance");
        var scope=new PositionAuthority.Scope("tenant","client","executor");
        var grant=new SkillExecutionService.RunningSkills(new RuntimeScope("tenant","client","task-owner","agent","instance","executor"),"install","host",List.of(Map.of("skill",Map.of("key","model-maintenance","installationId","si"))));
        String digest="a".repeat(64);var fence=new ConversationFence(1,"fixture");
        var admitted=doAnswer(i->i.<Function<SkillExecutionService.RunningSkills,Object>>getArgument(5).apply(grant));
        admitted.when(executions).withRunningSkills(eq(caller),eq("e"),eq("r"),eq(fence),eq(digest),any());
        when(prices.readPrice(scope,"fixture")).thenReturn(Map.of("configured",true,"model","fixture"));
        var request=post("/internal/agent/executions/e/runs/r/business/model-prices/lookup").principal(principal).contentType("application/json")
                .content("{\"protocolVersion\":1,\"requestId\":\"test\",\"data\":{\"model\":\"fixture\",\"snapshotSha256\":\""+digest+"\",\"fence\":{\"version\":\"1\",\"token\":\"fixture\"}}}");
        var response=mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse();
        assertTrue(response.getContentAsString().contains("\"configured\":true"));
        doThrow(new PositionSkillException(403,"MODEL_SKILL_REQUIRED")).when(authority).requireInstalled(scope,"agent","install","host",true,grant.selections());
        mvc.perform(request).andExpect(status().isForbidden());
        doThrow(new Failure(Reason.TASK_CONFLICT)).when(executions).withRunningSkills(eq(caller),eq("e"),eq("r"),eq(fence),eq(digest),any());
        mvc.perform(request).andExpect(status().isConflict());verify(prices,times(1)).readPrice(scope,"fixture");
    }
}
