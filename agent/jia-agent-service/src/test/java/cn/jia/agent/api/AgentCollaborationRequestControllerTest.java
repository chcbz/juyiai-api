package cn.jia.agent.api;

import cn.jia.agent.service.AgentCollaborationRequestService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentCollaborationRequestControllerTest {
    private AgentCollaborationRequestService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(AgentCollaborationRequestService.class);
        mvc = MockMvcBuilders.standaloneSetup(new AgentCollaborationRequestController(service)).build();
    }

    @Test
    void idempotencyRecoveryUsesDedicatedLiteralRoute() throws Exception {
        when(service.getByIdempotencyKey(any(), eq("idempotency-1"))).thenReturn(view("pwe_1"));
        mvc.perform(get("/agent/hall/collaboration/requests/request")
                        .header("Idempotency-Key", "idempotency-1").principal(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requestId").value("pwe_1"));
        verify(service).getByIdempotencyKey(eq(new AgentCollaborationRequestService.OwnerScope(
                "0", "client-a", "owner-a")), eq("idempotency-1"));
    }

    @Test
    void retiredExecutionWritesHaveNoHttpHandler() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/agent/hall/collaboration/requests").principal(jwt()).contentType("application/json").content("{}"))
                .andExpect(status().isNotFound());
        org.mockito.Mockito.verifyNoInteractions(service);
    }

    private static AgentCollaborationRequestService.RequestView view(String id) {
        return new AgentCollaborationRequestService.RequestView(id, "99", "agent-husan-niang",
                "private", "agent:agent-husan-niang", "QUEUED", "RUNTIME_COMMAND_DISPATCH",
                "PRIVATE", null);
    }

    private static JwtAuthenticationToken jwt() {
        Jwt jwt = Jwt.withTokenValue("token").claim("tenant_id", "0").header("alg", "none").claim("jiacn", "owner-a")
                .claim("client_id", "client-a").issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(600)).build();
        JwtAuthenticationToken authentication = new JwtAuthenticationToken(jwt);
        authentication.setAuthenticated(true);
        return authentication;
    }
}
