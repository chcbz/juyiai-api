package cn.jia.agent.security;

import cn.jia.agent.api.PersonalWorkspaceConversationRuntimeController;
import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import cn.jia.agent.service.SkillExecutionService;
import cn.jia.core.config.*;
import cn.jia.core.filter.RequestIdFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real Q/R decoding and JSON encoding with a trusted Native principal, no production storage. */
class ConversationLeaseWireHttpTest {
    private static final String TOKEN="00000000-0000-0000-0000-000000000001";
    private static final String BASE="/internal/agent/executions/execution/runs/run";
    private PersonalWorkspaceExecutionService executions;
    private SkillExecutionService skills;
    private AgentRuntimeAuthentication auth;
    private MockMvc mvc;
    private AgentRuntimeAuthenticationService nativeAuth;
    @BeforeEach void setup() {
        executions=mock(PersonalWorkspaceExecutionService.class,withSettings().extraInterfaces(SkillExecutionService.class));
        skills=(SkillExecutionService)executions;
        nativeAuth=mock(AgentRuntimeAuthenticationService.class);
        when(nativeAuth.withNativeFence(any(),any())).thenAnswer(invocation->invocation.getArgument(1,Supplier.class).get());
        auth=new AgentRuntimeAuthentication(new AgentRuntimeAuthentication.Scope("tenant","client","owner","agent","runtime"));
        mvc = http(false);
    }
    private MockMvc http(boolean withSanitizer) {
        var advice=new ArrayList<Object>(List.of(new ApiEnvelopeAdvice(),new ApiRequestEnvelopeAdvice(),new ExceptionHandlerAdvice()));
        if(withSanitizer) advice.add(new cn.jia.core.security.SensitiveResponseBodyAdvice(new cn.jia.core.security.SensitiveResponseProperties()));
        var converters=new ArrayList<HttpMessageConverter<?>>();
        converters.add(new StringHttpMessageConverter(java.nio.charset.StandardCharsets.UTF_8));
        converters.add(new JacksonJsonHttpMessageConverter());
        new ApiEnvelopeJsonConfig().extendMessageConverters(converters);
        return MockMvcBuilders.standaloneSetup(new PersonalWorkspaceConversationRuntimeController(executions,nativeAuth))
                .setControllerAdvice(advice.toArray())
                .setMessageConverters(converters.toArray(HttpMessageConverter[]::new))
                .addFilters(new RequestIdFilter()).build();
    }
    private org.springframework.test.web.servlet.ResultActions call(String suffix,String data) throws Exception {
        return mvc.perform(post(BASE+suffix).principal(auth).contentType(MediaType.APPLICATION_JSON)
                .header("X-Request-Id","lease-test").content("{\"protocolVersion\":1,\"requestId\":\"lease-test\",\"data\":"+data+"}"));
    }
    private PersonalWorkspaceExecutionService.ConversationLease lease(long version) {
        return new PersonalWorkspaceExecutionService.ConversationLease("execution",version,TOKEN,1_800_000_000_000L);
    }
    @Test void privateFailureReceiptCarriesExecutionScopeWithoutTaskAuthority() throws Exception {
        var scope=new SkillExecutionService.ScopeView("PRIVATE","42","1",null);
        when(executions.failConversation(any(),eq("execution"),eq("run"),any(),eq("NATIVE_EXECUTION_TURN_FAILED")))
                .thenReturn(new PersonalWorkspaceExecutionService.RuntimeFailure("execution","run",scope,"agent","FAILED","AGENT_DELIVERY_FAILED"));
        call("/failure","{\"fence\":{\"version\":\"1\",\"token\":\""+TOKEN+"\"},\"code\":\"NATIVE_EXECUTION_TURN_FAILED\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.executionId").value("execution"))
                .andExpect(jsonPath("$.data.scope.kind").value("PRIVATE"))
                .andExpect(jsonPath("$.data.scope.conversationGeneration").value("1"))
                .andExpect(jsonPath("$.data.targetAgentId").value("agent"))
                .andExpect(jsonPath("$.data.failureCode").value("AGENT_DELIVERY_FAILED"))
                .andExpect(jsonPath("$.data.taskId").doesNotExist());
    }
    @Test void native6FilterRequiresNativeCredentialsAndRejectsRetiredTaskRoutes() {
        for(String suffix:List.of("/lease","/lease/renew","/inputs","/inputs/ref/content","/outputs/out/content",
                "/effects/effect/start","/effects/effect/result","/resume","/result-commits/manifest","/failure","/usage","/provider-usage")) {
            var current=new org.springframework.mock.web.MockHttpServletRequest("POST",BASE+suffix);
            assertTrue(AgentRuntimeAuthenticationFilter.selectsRuntimeCredentialLane(current),suffix);
            assertTrue(AgentRuntimeAuthenticationFilter.allowed(current),suffix);
            assertFalse(AgentRuntimeAuthenticationFilter.allowed(new org.springframework.mock.web.MockHttpServletRequest(
                    "POST","/internal/agent/tasks/task/runs/run/conversation"+suffix)),suffix);
        }
        assertTrue(AgentRuntimeAuthenticationFilter.allowed(new org.springframework.mock.web.MockHttpServletRequest(
                "GET","/internal/agent/executions/commands")));
        assertFalse(AgentRuntimeAuthenticationFilter.allowed(new org.springframework.mock.web.MockHttpServletRequest(
                "GET","/internal/agent/tasks/conversation-executions/commands")));
    }

    @Test void leaseHasExactDecimalVersionAndNumericEpoch() throws Exception {
        when(executions.claimConversationStart(any(),eq("execution"),eq("run"),eq("cmd"),eq("msg"))).thenReturn(lease(9007199254740993L));
        call("/lease","{\"commandId\":\"cmd\",\"messageId\":\"msg\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value("9007199254740993"))
                .andExpect(jsonPath("$.data.expiresAt").isNumber()).andExpect(jsonPath("$.data.token").value(TOKEN))
                .andExpect(jsonPath("$.requestId").value("lease-test"));
    }
    @Test void renewalPreservesFullInt64Fence() throws Exception {
        when(executions.renewConversationLease(any(),eq("execution"),eq("run"),any())).thenReturn(lease(Long.MAX_VALUE));
        call("/lease/renew","{\"version\":\"9223372036854775807\",\"token\":\""+TOKEN+"\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value("9223372036854775807"));
        verify(executions).renewConversationLease(any(),eq("execution"),eq("run"),eq(new PersonalWorkspaceExecutionService.ConversationFence(Long.MAX_VALUE,TOKEN)));
    }
    @Test void oldNumericNoncanonicalOverflowAndUnknownFieldsFailBeforeMutation() throws Exception {
        for(String version:List.of("1","1.0","true","null","\"01\"","\"0\"","\"-1\"","\"9223372036854775808\""))
            call("/lease/renew","{\"version\":"+version+",\"token\":\""+TOKEN+"\"}").andExpect(status().isBadRequest());
        call("/lease/renew","{\"version\":\"1\",\"token\":\""+TOKEN+"\",\"extra\":true}").andExpect(status().isBadRequest());
        verifyNoInteractions(executions);
    }
    @Test void inputAndResumeLeaseVersionsUseSameWireType() throws Exception {
        when(skills.inputs(any(),eq("execution"),eq("run"),any())).thenReturn(new SkillExecutionService.Inputs(6,"execution",9007199254740993L,"a".repeat(64),true,List.of()));
        call("/inputs","{\"version\":\"9007199254740993\",\"token\":\""+TOKEN+"\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.leaseVersion").value("9007199254740993"));
        when(skills.resume(any(),eq("execution"),eq("run"),any())).thenReturn(new SkillExecutionService.ResumeReceipt(lease(9007199254740993L),"RUNNING",List.of("EXECUTE"),null));
        call("/resume","{\"executionId\":\"execution\",\"snapshotSha256\":\""+"a".repeat(64)+"\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.lease.version").value("9007199254740993"));
    }
    @Test void nativeInputStreamsAndClosesOnSuccessfulResponse() throws Exception {
        var closed=new java.util.concurrent.atomic.AtomicBoolean();
        var stream=new java.io.ByteArrayInputStream(new byte[]{1,2,3}) {
            @Override public byte[] readAllBytes() {throw new AssertionError("Runtime input must stream");}
            @Override public void close() {closed.set(true);}
        };
        when(skills.inputContent(any(),eq("execution"),eq("run"),eq("input_1"),any()))
                .thenReturn(new PersonalWorkspaceExecutionService.RuntimeDownload("source","image/png","a".repeat(64),3,stream));
        call("/inputs/input_1/content","{\"version\":\"1\",\"token\":\""+TOKEN+"\"}")
                .andExpect(status().isOk()).andExpect(header().string("Content-Length","3"))
                .andExpect(content().bytes(new byte[]{1,2,3}));
        org.junit.jupiter.api.Assertions.assertTrue(closed.get());
    }
    @Test void nativeInputClosesWhenPostIoIdentityFenceRejects() throws Exception {
        var closed=new java.util.concurrent.atomic.AtomicBoolean();
        var stream=new java.io.ByteArrayInputStream(new byte[]{1,2,3}) {
            @Override public void close() {closed.set(true);}
        };
        when(skills.inputContent(any(),eq("execution"),eq("run"),eq("input_1"),any()))
                .thenReturn(new PersonalWorkspaceExecutionService.RuntimeDownload("source","image/png","a".repeat(64),3,stream));
        doThrow(new SecurityException("fixture")).when(nativeAuth).withNativeFence(any(),any());
        call("/inputs/input_1/content","{\"version\":\"1\",\"token\":\""+TOKEN+"\"}")
                .andExpect(status().isServiceUnavailable()).andExpect(header().doesNotExist("Content-Disposition"));
        org.junit.jupiter.api.Assertions.assertTrue(closed.get());
    }
    @Test void rawEffectFenceIsProjectedWithoutChangingItsDomainLong() throws Exception {
        when(skills.startEffect(any(),eq("execution"),eq("run"),eq("effect"),any()))
                .thenReturn(new SkillExecutionService.EffectReceipt(1,"NEW","START_RECORDED",null,null));
        String fields="\"executionId\":\"execution\",\"snapshotSha256\":\""+"a".repeat(64)+"\",\"effectSha256\":\""+"b".repeat(64)+"\",\"providerBindingRevision\":\"0\",";
        call("/effects/effect/start","{"+fields+"\"fence\":{\"version\":\"9007199254740993\",\"token\":\""+TOKEN+"\"}}")
                .andExpect(status().isOk());
        verify(skills).startEffect(any(),eq("execution"),eq("run"),eq("effect"),argThat(value->value.fence().version()==9007199254740993L));
        call("/effects/effect/start","{"+fields+"\"fence\":{\"version\":1,\"token\":\""+TOKEN+"\"}}")
                .andExpect(status().isBadRequest());
    }
    @Test void hashNamesAndArtifactLengthsProjectToUnchangedServiceFacts() throws Exception {
        when(skills.commitResult(any(),eq("execution"),eq("run"),eq("manifest"),any()))
                .thenReturn(new SkillExecutionService.ResultReceipt("manifest","RESULT_COMMITTED","ARCHIVING","c".repeat(64)));
        String data="{\"fence\":{\"version\":\"1\",\"token\":\""+TOKEN+"\"},\"snapshotSha256\":\""+"a".repeat(64)+"\","
                +"\"data\":null,\"inputReceipts\":[],\"effectDependencies\":[],\"artifacts\":[{\"outputId\":\"output_1\",\"sha256\":\""+"b".repeat(64)+"\",\"byteLength\":\"9007199254740993\"}]}";
        call("/result-commits/manifest",data).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resultSha256").value("c".repeat(64))).andExpect(jsonPath("$.data.resultDigest").doesNotExist());
        verify(skills).commitResult(any(),eq("execution"),eq("run"),eq("manifest"),argThat(value->
                value.snapshotDigest().equals("a".repeat(64))&&value.artifacts().getFirst().byteLength()==9007199254740993L));
        call("/result-commits/manifest",data.replace("snapshotSha256","snapshotDigest")).andExpect(status().isBadRequest());
        call("/result-commits/manifest",data.replace("\"effectDependencies\":[],", "")).andExpect(status().isBadRequest());
        call("/result-commits/manifest",data.replace("\"9007199254740993\"","1")).andExpect(status().isBadRequest());
    }

    @Test void commandWirePreservesMeteringPresenceAndNestedRedaction() throws Exception {
        mvc=http(true);
        for(boolean metered:List.of(false,true)) {
            var view=new SkillExecutionService.Command(6,"execution","run",new SkillExecutionService.ScopeView("PRIVATE","42","1",null),"cmd","msg",List.of(),
                    "a".repeat(64),java.util.Map.of("password","fixture-secret"),"draw",List.of(),java.util.Map.of(),SkillExecutionService.NATIVE_LOCAL_FILES_POLICY,
                    metered?"RESPONSE_V2":null,metered?"METERED_CAPTURE_V2":null);
            when(executions.runtimeConversationCommandViews(any(),eq(16))).thenAnswer(i->List.of(view));
            String wire=mvc.perform(get("/internal/agent/executions/commands").principal(auth).header("X-Request-Id","lease-test"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            var item=tools.jackson.databind.json.JsonMapper.builder().build().readTree(wire).path("data").path("items").get(0);
            assertTrue(item.path("scope").path("taskId").isNull());
            assertTrue(item.path("parameters").path("password").isNull());
            assertFalse(wire.contains("fixture-secret"));
            assertEquals(metered,item.has("usageMode")); assertEquals(metered,item.has("captureProtocol"));
            if(metered){assertEquals("RESPONSE_V2",item.path("usageMode").asText());assertEquals("METERED_CAPTURE_V2",item.path("captureProtocol").asText());}
        }
    }
    @Test void v6EmptySkillCommandAndResultUseTheSharedExactWireFixture() throws Exception {
        var mapper=tools.jackson.databind.json.JsonMapper.builder().build();
        var fixture=mapper.readTree(new org.springframework.core.io.ClassPathResource("contracts/native-execution-v6-local-files.json")
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        var command=fixture.path("commandResponse").path("data").path("items").get(0);
        var outputs=mapper.convertValue(command.path("outputs"),java.util.Map.class);
        var view=new SkillExecutionService.Command(6,"execution","run",new SkillExecutionService.ScopeView("TASK","42","1","task"),"cmd","msg",List.of(),
                "a".repeat(64),java.util.Map.of(),command.path("instruction").asText(),List.of(),outputs,SkillExecutionService.NATIVE_LOCAL_FILES_POLICY,null,null);
        when(executions.runtimeConversationCommandViews(any(),eq(16))).thenAnswer(i->List.of(view));
        String actual=mvc.perform(get("/internal/agent/executions/commands").principal(auth).header("X-Request-Id","lease-test"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].skills").isEmpty())
                .andExpect(jsonPath("$.data.items[0].skill").doesNotExist()).andExpect(jsonPath("$.data.items[0].taskSkillBinding").doesNotExist())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        org.junit.jupiter.api.Assertions.assertEquals(command,mapper.readTree(actual).path("data").path("items").get(0));
        var preimage=mapper.convertValue(fixture.path("resultPreimage"),java.util.Map.class);
        String canonical=cn.jia.agent.service.SkillCanonicalJson.write(preimage);
        org.junit.jupiter.api.Assertions.assertEquals(fixture.path("canonicalPreimage").asText(),canonical);
        String digest=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        org.junit.jupiter.api.Assertions.assertEquals(fixture.path("resultSha256").asText(),digest);
        String manifest=fixture.path("manifestId").asText();
        when(skills.commitResult(any(),eq("execution"),eq("run"),eq(manifest),any()))
                .thenReturn(new SkillExecutionService.ResultReceipt(manifest,"RESULT_COMMITTED","ARCHIVING",digest));
        mvc.perform(post(BASE+"/result-commits/"+manifest).principal(auth).header("X-Request-Id","lease-test")
                .contentType(MediaType.APPLICATION_JSON).content(fixture.path("resultCommitRequest").toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.resultSha256").value(digest));
        verify(skills).commitResult(any(),eq("execution"),eq("run"),eq(manifest),argThat(value->
                value.effectDependencies().isEmpty()&&value.artifacts().getFirst().byteLength()==14));
    }
    @Test void resumeProjectsRealEffectFactsWithoutLeakingInternalDigestNames() throws Exception {
        var effect=new java.util.LinkedHashMap<String,Object>();effect.put("effectDigest","a".repeat(64));effect.put("resultDigest",null);
        effect.put("status","START_RECORDED");effect.put("bindingRevision",null);
        when(skills.resume(any(),eq("execution"),eq("run"),any())).thenReturn(new SkillExecutionService.ResumeReceipt(lease(1),
                "WAITING_PROVIDER",List.of("QUERY_EFFECT","RECOVER_LOCAL_RESULT"),null,java.util.Map.of("operation_1",effect)));
        call("/resume","{\"executionId\":\"execution\",\"snapshotSha256\":\""+"a".repeat(64)+"\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.effects.operation_1.effectSha256").value("a".repeat(64)))
                .andExpect(jsonPath("$.data.effects.operation_1.status").value("START_RECORDED"))
                .andExpect(jsonPath("$.data.effects.operation_1.effectDigest").doesNotExist());
    }

    @Test void resumeReturnsAuthoritativeFailedTurnWithoutAResultOrLease() throws Exception {
        when(skills.resume(any(),eq("execution"),eq("run"),any())).thenReturn(new SkillExecutionService.ResumeReceipt(null,
                "FAILED",List.of(),null,java.util.Map.of(),"NATIVE_EXECUTION_TURN_FAILED"));
        call("/resume","{\"executionId\":\"execution\",\"snapshotSha256\":\""+"a".repeat(64)+"\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.phase").value("FAILED"))
                .andExpect(jsonPath("$.data.turnFailureCode").value("NATIVE_EXECUTION_TURN_FAILED"))
                .andExpect(jsonPath("$.data.allowedWork").isEmpty())
                .andExpect(jsonPath("$.data.lease").doesNotExist()).andExpect(jsonPath("$.data.resultManifest").doesNotExist());
    }

    @Test void multipartStageUsesByteLengthAndKeepsBytesAndLeaseProof() throws Exception {
        byte[] bytes="中文成果".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String sha=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        when(executions.stageConversationOutputStream(any(),eq("execution"),eq("run"),any(),eq("output_1"),any(),any(),any(),eq((long)bytes.length),eq(sha)))
                .thenReturn(new PersonalWorkspaceExecutionService.StagedOutput("output_1",sha,bytes.length,"STAGED"));
        var file=new org.springframework.mock.web.MockMultipartFile("file","result.txt","text/plain",bytes);
        mvc.perform(multipart(BASE+"/outputs/output_1/content").file(file).principal(auth)
                .param("version","9007199254740993").param("token",TOKEN).param("sha256",sha).param("byteLength",Integer.toString(bytes.length))
                .header("X-Request-Id","lease-test"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.byteLength").value(Integer.toString(bytes.length)));
        var source = org.mockito.ArgumentCaptor.forClass(cn.jia.agent.service.PersonalWorkspaceService.UploadSource.class);
        verify(executions).stageConversationOutputStream(any(),eq("execution"),eq("run"),eq(new PersonalWorkspaceExecutionService.ConversationFence(9007199254740993L,TOKEN)),
                eq("output_1"),eq("result.txt"),eq("text/plain"),source.capture(),eq((long)bytes.length),eq(sha));
        try (var input = source.getValue().open()) {
            org.junit.jupiter.api.Assertions.assertArrayEquals(bytes, input.readAllBytes());
        }
        mvc.perform(multipart(BASE+"/outputs/output_1/content").file(file).principal(auth)
                .param("version","1").param("token",TOKEN).param("sha256",sha).param("length",Integer.toString(bytes.length)))
                .andExpect(status().isBadRequest());
    }

    @Test void retiredConversationOutputCommitHasNoRoute() throws Exception {
        call("/output-commits/manifest","{}").andExpect(status().isNotFound());
        verifyNoInteractions(executions);
    }

    @Test void retiredTaskOutputWriteRoutesDoNotInvokeNativeServices() throws Exception {
        mvc.perform(post("/internal/agent/tasks/task/runs/run/output-commits/manifest")
                .principal(auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"protocolVersion\":1,\"requestId\":\"test\",\"data\":{}}"))
                .andExpect(status().isNotFound());
        var file=new org.springframework.mock.web.MockMultipartFile("file","result.txt","text/plain",new byte[]{1});
        mvc.perform(multipart("/internal/agent/tasks/task/runs/run/outputs/output_1/content").file(file).principal(auth))
                .andExpect(status().isNotFound());
        verifyNoInteractions(executions);
    }

    @Test void nativeConversationMultipartStreamsWithoutWholeFileArray() throws Exception {
        byte[] bytes={1,2,3};String hash="a".repeat(64);
        when(executions.stageConversationOutputStream(any(),eq("execution"),eq("run"),any(),eq("output_1"),
                eq("result.txt"),eq("text/plain"),any(),eq(3L),eq(hash))).thenAnswer(i->{
            try(var input=i.getArgument(7,cn.jia.agent.service.PersonalWorkspaceService.UploadSource.class).open()) {
                org.junit.jupiter.api.Assertions.assertArrayEquals(bytes,input.readAllBytes());
            }
            return new PersonalWorkspaceExecutionService.StagedOutput("output_1",hash,3,"STAGED");
        });
        var file=new org.springframework.mock.web.MockMultipartFile("file","result.txt","text/plain",bytes) {
            @Override public byte[] getBytes(){throw new AssertionError("Native stage must stream");}
        };
        mvc.perform(multipart(BASE+"/outputs/output_1/content").file(file).principal(auth)
                .param("version","1").param("token",TOKEN).param("sha256",hash).param("byteLength","3"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.byteLength").value("3"));
    }

}
