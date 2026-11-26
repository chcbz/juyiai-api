package cn.jia.agent.api;

import cn.jia.agent.exception.PersonalWorkspaceException;
import cn.jia.agent.service.recommendation.AssigneeRecommendationService;
import cn.jia.core.config.ApiEnvelopeAdvice;
import cn.jia.core.context.*;
import cn.jia.core.filter.RequestIdFilter;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import static cn.jia.agent.entity.AssigneeRecommendation.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AssigneeRecommendationControllerTest {
    final AssigneeRecommendationService service = mock(AssigneeRecommendationService.class);
    MockMvc mvc;
    JwtAuthenticationToken login;
    static final String BODY = "{\"protocolVersion\":1,\"requestId\":\"recommend-test\",\"data\":{\"expectedTaskVersion\":\"7\"}}";
    @BeforeEach void setup() {
        login = new JwtAuthenticationToken(Jwt.withTokenValue("test").claim("tenant_id", "0").header("alg", "none")
                .claim("sub", "user").claim("jiacn", "owner").claim("client_id", "client").build(), List.of());
        var context = new EsContext(); context.setTenantId("0"); context.setClientId("client"); context.setJiacn("owner"); EsContextHolder.setContext(context);
        mvc = MockMvcBuilders.standaloneSetup(new AssigneeRecommendationController(service))
                .setMessageConverters(new JacksonJsonHttpMessageConverter()).setControllerAdvice(new ApiEnvelopeAdvice())
                .addFilters(new RequestIdFilter()).build();
        when(service.recommend(any(), eq("task"), any())).thenReturn(new Result("task", "7", 1000, "NO_MATCH", List.of(new Recommendation(new Agent("a", "Agent", "online", List.of(), true, true, null), "尚无能力介绍", List.of(), List.of())), true, "HYBRID", "candidate-rrf-k60-v1", List.of()));
    }
    @Test void independentQuoteEndpointUsesHeaderAndOnlyExplicitIdsNotBrowserPrices() throws Exception {
        var app=mock(cn.jia.agent.service.recommendation.IndependentTaskQuotes.class);
        var api=MockMvcBuilders.standaloneSetup(new IndependentTaskQuoteController(app)).setMessageConverters(new JacksonJsonHttpMessageConverter())
                .setControllerAdvice(new ApiEnvelopeAdvice()).addFilters(new RequestIdFilter()).build();
        var intent=new cn.jia.economy.task.TaskQuoteBatches.Intent("7",List.of("a","z"));
        var item=new cn.jia.economy.task.TaskQuoteBatches.Item("a",cn.jia.economy.task.TaskQuoteBatches.State.UNAVAILABLE,null,"batch",null,cn.jia.economy.task.TaskQuoteBatches.Reason.NO_BASELINE,null);
        when(app.quote(any(),eq("task"),eq("key"),eq(intent))).thenReturn(new cn.jia.economy.task.TaskQuoteBatches.Result("task","7",12,List.of(item,new cn.jia.economy.task.TaskQuoteBatches.Item("z",cn.jia.economy.task.TaskQuoteBatches.State.UNAVAILABLE,null,"batch",null,cn.jia.economy.task.TaskQuoteBatches.Reason.PRICE_MISSING,null))));
        String body=BODY.replace("\"7\"","\"7\",\"agentIds\":[\"z\",\"a\",\"z\"]");
        api.perform(post("/agent/tasks/task/quotes").principal(login).contentType(MediaType.APPLICATION_JSON).content(body).header("Idempotency-Key","key").header("X-Request-Id","recommend-test"))
          .andExpect(status().isOk()).andExpect(jsonPath("$.data.quotes[0].unavailableReason").value("NO_BASELINE")).andExpect(header().string("Cache-Control","private, no-store"));
        api.perform(post("/agent/tasks/task/quotes").principal(login).contentType(MediaType.APPLICATION_JSON).content(body.replace("\"agentIds\"","\"amount\":0,\"agentIds\"")).header("Idempotency-Key","key"))
          .andExpect(status().isBadRequest());
        api.perform(post("/agent/tasks/task/quotes").principal(login).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        when(app.quote(any(),eq("task"),eq("key"),eq(intent))).thenThrow(new cn.jia.agent.service.TaskFeatureEncoder.InvalidResult(),new cn.jia.agent.service.TaskFeatureEncoder.Unavailable());
        api.perform(post("/agent/tasks/task/quotes").principal(login).contentType(MediaType.APPLICATION_JSON).content(body).header("Idempotency-Key","key")).andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("QUOTE_FEATURE_INVALID"));
        api.perform(post("/agent/tasks/task/quotes").principal(login).contentType(MediaType.APPLICATION_JSON).content(body).header("Idempotency-Key","key")).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("QUOTE_FEATURE_UNAVAILABLE"));
    }
    @Test void quoteRequirementErrorsStayDistinctFromEncoderAndPerCandidateFailures()throws Exception {
        var app=mock(cn.jia.agent.service.recommendation.IndependentTaskQuotes.class);
        var api=MockMvcBuilders.standaloneSetup(new IndependentTaskQuoteController(app)).setMessageConverters(new JacksonJsonHttpMessageConverter())
                .setControllerAdvice(new ApiEnvelopeAdvice()).addFilters(new RequestIdFilter()).build();
        for(var reason:cn.jia.agent.service.AgentTaskRequirementReadException.Reason.values()){
            doThrow(new cn.jia.agent.service.AgentTaskRequirementReadException(reason)).when(app).quote(any(),anyString(),anyString(),any());
            int expected=switch(reason){case NOT_FOUND->404;case RECONFIRM_REQUIRED->409;default->503;};
            var body=BODY.replace("\"7\"","\"7\",\"agentIds\":[\"a\"]");
            api.perform(post("/agent/tasks/task/quotes").principal(login).contentType(MediaType.APPLICATION_JSON).content(body).header("Idempotency-Key","key"))
               .andExpect(status().is(expected)).andExpect(jsonPath("$.code").value(switch(expected){case 404->"QUOTE_NOT_FOUND";case 409->"QUOTE_STALE";default->"QUOTE_UNAVAILABLE";}))
               .andExpect(jsonPath("$.data.quotes").doesNotExist());
        }
    }
    @Test void recommendationAndQuoteDoNotDiscloseRevokedMaterialsOrHideStorageFailure() throws Exception {
        var missing = new PersonalWorkspaceException(PersonalWorkspaceException.Reason.NOT_FOUND);
        var outage = new PersonalWorkspaceException(PersonalWorkspaceException.Reason.STORAGE_UNAVAILABLE);
        when(service.recommend(any(), eq("task"), any())).thenThrow(missing, outage);
        var app = mock(cn.jia.agent.service.recommendation.IndependentTaskQuotes.class);
        when(app.quote(any(), eq("task"), eq("key"), any())).thenThrow(missing, outage);
        var api = MockMvcBuilders.standaloneSetup(new IndependentTaskQuoteController(app))
                .setMessageConverters(new JacksonJsonHttpMessageConverter()).setControllerAdvice(new ApiEnvelopeAdvice())
                .addFilters(new RequestIdFilter()).build();
        for (boolean quote : List.of(false, true)) {
            var request = post(quote ? "/agent/tasks/task/quotes" : "/agent/tasks/task/recommend").principal(login)
                    .contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", "key")
                    .content(quote ? BODY.replace("\"7\"", "\"7\",\"agentIds\":[\"a\"]") : BODY);
            var client = quote ? api : mvc;
            var prefix = quote ? "QUOTE" : "RECOMMENDATION";
            client.perform(request).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(prefix + "_NOT_FOUND"))
                    .andExpect(header().string("Cache-Control", "private, no-store"));
            client.perform(request).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(prefix + "_UNAVAILABLE"));
        }
    }
    @AfterEach void cleanup() { EsContextHolder.clearContext(); }
    @Test void sameEndpointReturnsNewObjectInOneEnvelopeWithoutLegacyScores() throws Exception {
        mvc.perform(post("/agent/tasks/task/recommend").principal(login).contentType(MediaType.APPLICATION_JSON).content(BODY)
                .header("X-Request-Id", "recommend-test"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(jsonPath("$.protocolVersion").value(1)).andExpect(jsonPath("$.code").value("E0"))
                .andExpect(jsonPath("$.data.workload").doesNotExist())
                .andExpect(jsonPath("$.data.recommendations[0].quote").doesNotExist())
                .andExpect(jsonPath("$.data.retrievalMode").value("HYBRID"))
                .andExpect(jsonPath("$.data.state").value("NO_MATCH")).andExpect(jsonPath("$.data.taskVersion").value("7"))
                .andExpect(jsonPath("$.data.recommendations[0].recommendationLevel").doesNotExist()).andExpect(jsonPath("$.data.score").doesNotExist());
    }
    @Test void responseMatchesSharedWebRecommendationFixture() throws Exception {
        var response = mvc.perform(post("/agent/tasks/task/recommend").principal(login).contentType(MediaType.APPLICATION_JSON).content(BODY)
                .header("X-Request-Id", "recommend-test")).andExpect(status().isOk()).andReturn().getResponse();
        var mapper = new tools.jackson.databind.ObjectMapper();
        try (var fixture = getClass().getResourceAsStream("/billing/task-recommendation.json")) {
            org.junit.jupiter.api.Assertions.assertEquals(mapper.readTree(fixture), mapper.readTree(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8)));
        }
    }
    @Test void missingIdentityAndMismatchedContextNeverInvokeModelService() throws Exception {
        mvc.perform(post("/agent/tasks/task/recommend").contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isUnauthorized());
        EsContextHolder.getContext().setJiacn("other");
        mvc.perform(post("/agent/tasks/task/recommend").principal(login).contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isNotFound());
        verifyNoInteractions(service);
    }
    @Test void unauthenticatedJwtNeverInvokesRecommendation() throws Exception {
        var unauthenticated = new JwtAuthenticationToken(login.getToken());
        mvc.perform(post("/agent/tasks/task/recommend").principal(unauthenticated).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
    @Test void malformedJsonIsBadRequestNotProviderFailure() throws Exception {
        mvc.perform(post("/agent/tasks/task/recommend").principal(login).contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        verifyNoInteractions(service);
    }
    @Test void policyTaskLookupLossIsNotAnInfrastructureFailure() throws Exception {
        when(service.recommend(any(),anyString(),any())).thenThrow(new cn.jia.agent.service.AgentTaskExecutionGrantException(
                cn.jia.agent.service.AgentTaskExecutionGrantException.Reason.NOT_FOUND,"private"));
        mvc.perform(post("/agent/tasks/task/recommend").principal(login).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RECOMMENDATION_NOT_FOUND")).andExpect(jsonPath("$.data.recommendations").doesNotExist());
    }
    @Test void taskRootAccessLossIsNotDisclosedAsProviderFailure() throws Exception {
        when(service.recommend(any(), anyString(), any())).thenThrow(new cn.jia.agent.exception.AgentTaskCollaborationException(
                cn.jia.agent.exception.AgentTaskCollaborationException.Reason.NOT_FOUND, "internal"));
        mvc.perform(post("/agent/tasks/task/recommend").principal(login).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RECOMMENDATION_NOT_FOUND"));
    }
    @Test void missingRequirementConflictsAndCannotMasqueradeAsNoMatch()throws Exception {
        for(var reason:cn.jia.agent.service.AgentTaskRequirementReadException.Reason.values()){
            doThrow(new cn.jia.agent.service.AgentTaskRequirementReadException(reason)).when(service).recommend(any(),anyString(),any());
            int expected=switch(reason){case NOT_FOUND->404; case RECONFIRM_REQUIRED->409; default->503;};
            String code=switch(expected){case 404->"RECOMMENDATION_NOT_FOUND";case 409->"RECOMMENDATION_STALE";default->"RECOMMENDATION_UNAVAILABLE";};
            mvc.perform(post("/agent/tasks/task/recommend").principal(login).contentType(MediaType.APPLICATION_JSON).content(BODY))
               .andExpect(status().is(expected)).andExpect(jsonPath("$.code").value(code)).andExpect(jsonPath("$.data.recommendations").doesNotExist());
        }
    }
    @Test void modelFailureUsesRealFailureNotEmptySuccessfulArray() throws Exception {
        when(service.recommend(any(), anyString(), any())).thenThrow(new Failure(503, "RECOMMENDATION_UNAVAILABLE"));
        mvc.perform(post("/agent/tasks/task/recommend").principal(login).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("RECOMMENDATION_UNAVAILABLE"));
    }
}
