package cn.jia.agent.service.recommendation;

import cn.jia.agent.api.AssigneeRecommendationController;
import cn.jia.agent.service.*;
import cn.jia.agent.skill.SkillManagementService;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import static org.junit.jupiter.api.Assertions.*;

/** Test-only, caller owns the random cluster and the SQL task fixture. No configured URL or shared index. */
public final class RecommendationHttpTestWiring implements AutoCloseable {
    private final ElasticsearchClient client;
    private final CapabilityIndexWriter writer;
    private final TaskFeatureEncoder.Identity id = new TaskFeatureEncoder.Identity("rec-http-fixture","text-embedding-3-small",1024);
    public final AtomicInteger encodes=new AtomicInteger();
    public volatile boolean slowQuery,failQuery;
    private final java.net.URI stats;
    private final java.net.http.HttpClient statsClient=java.net.http.HttpClient.newHttpClient();
    public long openContexts()throws Exception{
        var request=java.net.http.HttpRequest.newBuilder(stats).timeout(Duration.ofSeconds(5)).GET().build();
        var response=statsClient.send(request,java.net.http.HttpResponse.BodyHandlers.ofString());assertEquals(200,response.statusCode());
        var value=new tools.jackson.databind.ObjectMapper().readTree(response.body()).at("/_all/total/search/open_contexts");
        assertTrue(value.isIntegralNumber());return value.asLong();
    }
    private final io.micrometer.observation.ObservationRegistry registry=io.micrometer.observation.ObservationRegistry.create();
    private final java.util.concurrent.ConcurrentMap<String,java.util.concurrent.ConcurrentLinkedQueue<Long>> stages=new java.util.concurrent.ConcurrentHashMap<>();
    public void resetStages(){stages.clear();} // only between completed request windows
    public int reportStages(String temperature,int n,int lanes){
        for(var e:new java.util.TreeMap<>(stages).entrySet()){
            var times=e.getValue().stream().sorted().toList();
            System.out.printf(java.util.Locale.ROOT,"RECOMMEND_STAGE temperature=%s candidates=%d concurrency=%d stage=%s count=%d p95ms=%.3f%n",temperature,n,lanes,e.getKey(),times.size(),times.get((95*times.size()+99)/100-1)/1e6);
        }
        for(String k:List.of("candidate.read.initial","candidate.read.recheck","search.lexical","rank"))assertEquals(30,stages.get(k).size(),k);
        return stages.getOrDefault("feature.encode",new java.util.concurrent.ConcurrentLinkedQueue<>()).size();
    }
    public final AssigneeRecommendationController controller;
    public RecommendationHttpTestWiring(AgentService agents,SkillManagementService skills,RecommendationMaterials materials,AgentTaskRootObservation roots)throws Exception {
        int port=Integer.parseInt(System.getProperty("cyf.capabilityEsPort"));String cluster=System.getProperty("cyf.capabilityEsCluster");
        assertTrue(port>=1024 && port<=65535 && port!=9200 && port!=9300);assertTrue(cluster.matches("cyf-rb-fixture-[a-f0-9]{32}"));
        stats=java.net.URI.create("http://127.0.0.1:"+port+"/"+CapabilityIndexWriter.GENERATION+"/_stats/search");
        client=ElasticsearchClient.of(b->b.host("http://127.0.0.1:"+port).useCompression(false));
        try {
            var info=client.info();assertEquals(cluster,info.clusterName());assertEquals("9.3.0",info.version().number());
            for(String name:List.of(CapabilityIndexWriter.GENERATION,CapabilityIndexWriter.GENERATION+"_write",CapabilitySearch.INDEX))assertFalse(client.indices().exists(b->b.index(name)).value());
            try(var mapping=Objects.requireNonNull(getClass().getResourceAsStream("/elasticsearch/agent-capability-evidence-v1.json"))){client.indices().create(b->b.index(CapabilityIndexWriter.GENERATION).withJson(mapping));}
            var factory=new DefaultListableBeanFactory();factory.registerSingleton("es",client);var clients=factory.getBeanProvider(ElasticsearchClient.class);writer=new CapabilityIndexWriter(clients);
            var encoder=new TaskFeatureEncoder(){public Identity identity(){return id;}public Vector encode(String text){assertTrue(text.contains("原始文件名: pinned-v1-"));assertFalse(text.contains("unselected-v2"));assertFalse(text.contains("private:v"));assertFalse(text.contains("b".repeat(64)));encodes.incrementAndGet();if(slowQuery){try{Thread.sleep(4000);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}if(failQuery)throw new TaskFeatureEncoder.Unavailable();}return vector();}};
            registry.observationConfig().observationHandler(new io.micrometer.observation.ObservationHandler<io.micrometer.observation.Observation.Context>(){
                public boolean supportsContext(io.micrometer.observation.Observation.Context c){return true;}
                public void onStart(io.micrometer.observation.Observation.Context c){c.put("fixtureStart",System.nanoTime());}
                public void onStop(io.micrometer.observation.Observation.Context c){
                    String k=c.getName().replace("cyf.task.recommendation.","").replace("cyf.task.","");
                    for(var tag:c.getLowCardinalityKeyValues())if(List.of("phase","route","path").contains(tag.getKey()))k+="."+tag.getValue();
                    long start=c.get("fixtureStart");stages.computeIfAbsent(k,ignored->new java.util.concurrent.ConcurrentLinkedQueue<>()).add(System.nanoTime()-start);
                }
            });
            var reg=java.util.Optional.of(registry);
            controller=new AssigneeRecommendationController(new AssigneeRecommendationService(agents,skills,materials,new TaskQueryVectors(encoder,256,8,Duration.ofMinutes(10),reg),new CapabilitySearch(clients,reg),roots,reg));
        }catch(Exception|Error failure){try{client.close();}catch(Exception cleanup){failure.addSuppressed(cleanup);}throw failure;}
    }
    /** Real SQL ownership reader and HTTP endpoint; candidate data is an explicit empty fixture. */
    public record ReadOnlyRecommendation(AssigneeRecommendationController controller,Runnable verifyReads) {}
    public static ReadOnlyRecommendation readOnly(AgentTaskRootObservation roots,String taskId){
        var agents=org.mockito.Mockito.mock(AgentService.class);var skills=org.mockito.Mockito.mock(SkillManagementService.class);
        var materials=org.mockito.Mockito.mock(RecommendationMaterials.class);var vectors=org.mockito.Mockito.mock(TaskQueryVectors.class);
        var search=org.mockito.Mockito.mock(CapabilitySearch.class);
        var task=new cn.jia.agent.entity.AgentTaskDTO();task.setTaskVersion("0");task.setStatus("open");task.setTitle("Paused admission read fixture");
        org.mockito.Mockito.when(agents.getTaskRecommendationInputWithinLockedTask(org.mockito.ArgumentMatchers.eq(taskId),org.mockito.ArgumentMatchers.any())).thenReturn(task);
        org.mockito.Mockito.when(agents.listTaskAssigneeCandidates(taskId)).thenReturn(java.util.List.of());
        var service=new AssigneeRecommendationService(agents,skills,materials,vectors,search,roots);
        return new ReadOnlyRecommendation(new AssigneeRecommendationController(service),()->{
            org.mockito.Mockito.verify(agents,org.mockito.Mockito.times(3)).getTaskRecommendationInputWithinLockedTask(org.mockito.ArgumentMatchers.eq(taskId),org.mockito.ArgumentMatchers.any());
            org.mockito.Mockito.verify(agents,org.mockito.Mockito.times(2)).listTaskAssigneeCandidates(taskId);
            org.mockito.Mockito.verifyNoMoreInteractions(agents);
            org.mockito.Mockito.verifyNoInteractions(skills,materials,vectors,search);
        });
    }
    private TaskFeatureEncoder.Vector vector(){float[] v=new float[1024];v[0]=1;return new TaskFeatureEncoder.Vector(id,v);}
    public void index(String tenant,String clientId,String owner,String agent,List<String> abilities)throws Exception {
        var docs=CapabilityDocuments.declared(tenant,clientId,owner,agent,abilities,id);assertEquals(abilities.size(),docs.size());for(var citation:docs)writer.publishVector(citation.doc(),vector());
    }
    public void indexSkill(String tenant,String clientId,String agent,SkillManagementService skills)throws Exception {
        var docs=CapabilityDocuments.purposes(tenant,clientId,agent,skills.citableSkillPurposes(tenant,clientId,agent),id);
        assertEquals(1,docs.size());writer.publishVector(docs.getFirst().doc(),vector());
    }
    public long documentCount()throws Exception{return client.count(b->b.index(CapabilityIndexWriter.GENERATION)).count();}
    public void refresh()throws Exception{client.indices().refresh(b->b.index(CapabilityIndexWriter.GENERATION));}
    public void close()throws Exception{try{client.close();}finally{statsClient.close();}}
}
