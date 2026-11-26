package cn.jia.chat.handler;

import cn.jia.agent.common.AgentMessageWire;
import cn.jia.agent.service.*;
import cn.jia.core.entity.ProtocolMessage;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletionStage;
import org.springframework.web.socket.*;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Test peer for the signed synthetic selection. Only the response values here are simulated. */
public final class SelectedProbeTestPeer {
    private SelectedProbeTestPeer(){}
    public static CompletionStage<NativeTaskConfigurationProbe.Measurement> exchange(NativeTaskPreparation.Frozen frozen,AgentManagedSessionLookup.SessionFence fence){
        var channel=new NativeTaskProbeChannel();var socket=mock(WebSocketSession.class);var mapper=new ObjectMapper();
        try {
            var sent=new java.util.concurrent.atomic.AtomicReference<TextMessage>();
            doAnswer(call->{assertTrue(sent.compareAndSet(null,call.getArgument(0)));return null;}).when(socket).sendMessage(any());
            var pending=channel.send(frozen,fence,()->socket,mapper);var text=sent.get().getPayload();
            var event=AgentMessageWire.decode(mapper.readValue(text,new tools.jackson.core.type.TypeReference<Map<String,Object>>(){}));var data=event.data();
            assertEquals("runtime.task.configuration.probe",event.messageType());
            var keys=new HashSet<>(Set.of("tenantId","clientId","agentId","runtimeInstallationId","hostId","runtimeInstanceId","sessionGeneration","intentSha256","preparationKind","outputs"));if(frozen.intent().instruction()!=null){keys.add("instructionSkill");assertEquals(frozen.intent().instruction(),mapper.convertValue(data.get("instructionSkill"), NativeTaskPreparation.Instruction.class));}assertEquals(keys,data.keySet());
            assertEquals(frozen.sha256(),data.get("intentSha256"));assertEquals(frozen.intent().output().comparisonOutputs(),data.get("outputs"));
            // Explicit fixture export only, bounded to this build; never include HTTP requests, credentials or task text.
            String out=System.getProperty("cyf.selectedProbeFixture");if(out!=null){var file=Path.of(out);Files.createDirectories(file.getParent());Files.writeString(file,text,StandardOpenOption.CREATE_NEW);}
            var reply=new HashMap<String,Object>(data);reply.remove("outputs");reply.remove("preparationKind");reply.remove("instructionSkill");reply.put("sourceAgentId",data.get("agentId"));
            reply.put("measurement",Map.of("version","NATIVE_DISPATCH_GROUP_V1","executionConfigSha256","1".repeat(64),"routeSha256","2".repeat(64),"toolConfigSha256","3".repeat(64),"capturePolicyVersion",frozen.intent().comparisonPolicy()));
            reply.put("configurationEpoch",Map.of("sequence","1","profileSha256","4".repeat(64)));
            var wrong=new HashMap<String,Object>(reply);var wrongGroup=new HashMap<>((Map<?,?>)reply.get("measurement"));
            var changed=new HashMap<Object,Object>(wrongGroup);changed.put("capturePolicyVersion",frozen.intent().instruction()==null?"NATIVE_PINNED_IMAGE_V1":"NATIVE_LOCAL_ARTIFACT_TEXT_V1");wrong.put("measurement",changed);
            channel.receive(socket,ProtocolMessage.reply(event,"runtime.task.configuration.measured","wrong-policy","E0","ok",wrong),p->fail("mixed policy published"));
            assertFalse(pending.toCompletableFuture().isDone());
            channel.receive(socket,ProtocolMessage.reply(event,"runtime.task.configuration.measured","fixture-reply","E0","ok",reply),p->assertEquals(1,p.sequence()));return pending;
        }catch(Exception e){throw new AssertionError(e);}finally{channel.closed(socket);}
    }
}
