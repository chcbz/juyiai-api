package cn.jia.chat.handler;

import cn.jia.agent.service.AgentManagedSessionLookup.SessionFence;
import cn.jia.agent.service.NativeTaskConfigurationProbe.Measurement;
import cn.jia.agent.service.NativeTaskPreparation.Frozen;
import cn.jia.core.entity.ProtocolMessage;
import org.springframework.web.socket.*;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Correlation exists only while a background comparison is outstanding. No catalog, prices or execution authority. */
final class NativeTaskProbeChannel {
    private record Pending(WebSocketSession socket,String message,Map<String,Object> identity,String intent,String comparisonPolicy,SessionFence fence,
                           Supplier<WebSocketSession> current,CompletableFuture<Measurement> result) {}
    private final Map<String,Pending> pending=new ConcurrentHashMap<>();
    private final Semaphore slots=new Semaphore(64);
    private static boolean sha(Object value){return value instanceof String s && s.matches("[0-9a-f]{64}");}

    CompletionStage<Measurement> send(Frozen frozen,SessionFence fence,Supplier<WebSocketSession> current,ObjectMapper mapper){
        if(!sha(frozen.sha256()) || !slots.tryAcquire())return CompletableFuture.completedFuture(null);
        String request=UUID.randomUUID().toString(),message=UUID.randomUUID().toString();
        var result=new CompletableFuture<Measurement>();
        result.whenComplete((r,e)->{pending.remove(request);slots.release();});
        try {
            var socket=current.get(); if(socket==null){result.complete(null);return result;}
            var b=frozen.intent().binding();var identity=Map.<String,Object>of("tenantId",b.scope().tenantId(),"clientId",b.scope().clientId(),
                "agentId",b.target().agent(),"runtimeInstallationId",fence.runtimeInstallationId(),"hostId",fence.hostId(),
                "runtimeInstanceId",fence.runtimeInstanceId(),"sessionGeneration",Long.toString(fence.sessionGeneration()));
            var data=new HashMap<>(identity);data.put("intentSha256",frozen.sha256());data.put("preparationKind",frozen.intent().schema());
            if(frozen.intent().instruction()!=null)data.put("instructionSkill",frozen.intent().instruction());
            data.put("outputs",frozen.intent().output().comparisonOutputs());
            var frame=new TextMessage(mapper.writeValueAsString(ProtocolMessage.event(request,"runtime.task.configuration.probe",message,data)));
            if(frame.getPayloadLength()>16384){result.complete(null);return result;}
            pending.put(request,new Pending(socket,message,identity,frozen.sha256(),frozen.intent().comparisonPolicy(),fence,current,result));
            result.completeOnTimeout(null,5,TimeUnit.SECONDS);
            synchronized(socket){if(!result.isDone() && current.get()==socket)socket.sendMessage(frame);else result.complete(null);}
        }catch(Exception unavailable){result.complete(null);}
        return result.minimalCompletionStage();
    }

    static cn.jia.agent.service.NativeTaskConfigurationProbe.Profile epoch(Object raw){
        if(!(raw instanceof Map<?,?> m)||!m.keySet().equals(Set.of("sequence","profileSha256"))||!(m.get("sequence") instanceof String seq)||!seq.matches("[1-9][0-9]{0,18}")
                || m.get("profileSha256")!=null&&!sha(m.get("profileSha256")))throw new IllegalArgumentException("Invalid prepared profile stamp");
        return new cn.jia.agent.service.NativeTaskConfigurationProbe.Profile(Long.parseLong(seq),(String)m.get("profileSha256"));
    }
    void receive(WebSocketSession socket,ProtocolMessage<Map<String,Object>> envelope,java.util.function.Consumer<cn.jia.agent.service.NativeTaskConfigurationProbe.Profile> publish){
        var p=pending.get(envelope.requestId());var d=envelope.data();
        if(p==null || p.socket()!=socket || !p.message().equals(envelope.correlationId()) || !"E0".equals(envelope.code()) || !"ok".equals(envelope.msg()))return;
        var keys=new HashSet<>(p.identity().keySet());keys.addAll(List.of("sourceAgentId","intentSha256","measurement","configurationEpoch"));
        if(d.containsKey("requestId")){if(!envelope.requestId().equals(d.get("requestId")))return;keys.add("requestId");}
        if(!keys.equals(d.keySet()) || !d.entrySet().containsAll(p.identity().entrySet()) || !p.identity().get("agentId").equals(d.get("sourceAgentId")) || !p.intent().equals(d.get("intentSha256")))return;
        var profile=epoch(d.get("configurationEpoch"));if((d.get("measurement")==null)!=(profile.profileSha256()==null))return;
        Measurement value=null;
        if(d.get("measurement")!=null){
            if(!(d.get("measurement") instanceof Map<?,?> m) || !m.keySet().equals(Set.of("version","executionConfigSha256","routeSha256","toolConfigSha256","capturePolicyVersion"))
                || !"NATIVE_DISPATCH_GROUP_V1".equals(m.get("version")) || !p.comparisonPolicy().equals(m.get("capturePolicyVersion"))
                || !sha(m.get("executionConfigSha256")) || !sha(m.get("routeSha256")) || !sha(m.get("toolConfigSha256")))return;
            value=new Measurement(p.intent(),p.fence(),(String)m.get("version"),(String)m.get("executionConfigSha256"),(String)m.get("routeSha256"),
                (String)m.get("toolConfigSha256"),(String)m.get("capturePolicyVersion"),profile);
        }
        if(p.current().get()!=socket){p.result().complete(null);return;}publish.accept(profile);p.result().complete(value);
    }
    void closed(WebSocketSession socket){pending.values().forEach(p->{if(p.socket()==socket)p.result().complete(null);});}
}
