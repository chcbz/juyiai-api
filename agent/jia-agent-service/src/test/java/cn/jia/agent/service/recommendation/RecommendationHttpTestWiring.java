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
            controller=new AssigneeRecommendationController(new AssigneeRecommendationService(agents,skills,materials,new TaskQueryVectors(encoder,256,8,Duration.ofMinutes(10)),new CapabilitySearch(clients),roots,new CandidateSuitability("","")));
        }catch(Exception|Error failure){try{client.close();}catch(Exception cleanup){failure.addSuppressed(cleanup);}throw failure;}
    }
    /** Real root reader; probes must remain untouched for every stopped or invisible request. */
    public record BeforeRetrieval(AssigneeRecommendationController controller,Runnable verifyUntouched) {}
    public static BeforeRetrieval beforeRetrieval(AgentTaskRootObservation roots){
        var agents=org.mockito.Mockito.mock(AgentService.class);var skills=org.mockito.Mockito.mock(SkillManagementService.class);
        var materials=org.mockito.Mockito.mock(RecommendationMaterials.class);var vectors=org.mockito.Mockito.mock(TaskQueryVectors.class);
        var search=org.mockito.Mockito.mock(CapabilitySearch.class);
        var service=new AssigneeRecommendationService(agents,skills,materials,vectors,search,roots,new CandidateSuitability("",""));
        return new BeforeRetrieval(new AssigneeRecommendationController(service),()->org.mockito.Mockito.verifyNoInteractions(agents,skills,materials,vectors,search));
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
