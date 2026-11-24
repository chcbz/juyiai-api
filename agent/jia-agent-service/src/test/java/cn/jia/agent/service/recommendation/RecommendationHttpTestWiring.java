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
    public final AssigneeRecommendationController controller;
    public RecommendationHttpTestWiring(AgentService agents,SkillManagementService skills,RecommendationMaterials materials,AgentTaskMutationTransaction roots)throws Exception {
        int port=Integer.parseInt(System.getProperty("cyf.capabilityEsPort"));String cluster=System.getProperty("cyf.capabilityEsCluster");
        assertTrue(port>=1024 && port<=65535 && port!=9200 && port!=9300);assertTrue(cluster.matches("cyf-rb-fixture-[a-f0-9]{32}"));
        client=ElasticsearchClient.of(b->b.host("http://127.0.0.1:"+port).useCompression(false));
        try {
            var info=client.info();assertEquals(cluster,info.clusterName());assertEquals("9.3.0",info.version().number());
            for(String name:List.of(CapabilityIndexWriter.GENERATION,CapabilityIndexWriter.GENERATION+"_write",CapabilitySearch.INDEX))assertFalse(client.indices().exists(b->b.index(name)).value());
            try(var mapping=Objects.requireNonNull(getClass().getResourceAsStream("/elasticsearch/agent-capability-evidence-v1.json"))){client.indices().create(b->b.index(CapabilityIndexWriter.GENERATION).withJson(mapping));}
            var factory=new DefaultListableBeanFactory();factory.registerSingleton("es",client);var clients=factory.getBeanProvider(ElasticsearchClient.class);writer=new CapabilityIndexWriter(clients);
            var encoder=new TaskFeatureEncoder(){public Identity identity(){return id;}public Vector encode(String text){assertTrue(text.contains("file-"));assertFalse(text.contains("private:v"));assertFalse(text.contains("b".repeat(64)));encodes.incrementAndGet();return vector();}};
            controller=new AssigneeRecommendationController(new AssigneeRecommendationService(agents,skills,materials,new TaskQueryVectors(encoder,256,8,Duration.ofMinutes(10)),new CapabilitySearch(clients),roots,new CandidateSuitability("","")));
        }catch(Exception|Error failure){try{client.close();}catch(Exception cleanup){failure.addSuppressed(cleanup);}throw failure;}
    }
    private TaskFeatureEncoder.Vector vector(){float[] v=new float[1024];v[0]=1;return new TaskFeatureEncoder.Vector(id,v);}
    public void index(String tenant,String clientId,String owner,String agent,List<String> abilities)throws Exception {
        var docs=CapabilityDocuments.declared(tenant,clientId,owner,agent,abilities,id);assertEquals(abilities.size(),docs.size());for(var citation:docs)writer.publishVector(citation.doc(),vector());
    }
    public long documentCount()throws Exception{return client.count(b->b.index(CapabilityIndexWriter.GENERATION)).count();}
    public void refresh()throws Exception{client.indices().refresh(b->b.index(CapabilityIndexWriter.GENERATION));}
    public void close()throws Exception{client.close();}
}
