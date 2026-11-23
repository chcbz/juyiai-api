package cn.jia.agent.service.recommendation;

import cn.jia.agent.entity.AgentTaskAssigneeCandidateDTO;
import cn.jia.agent.entity.AgentTaskTargetSummaryDTO;
import cn.jia.agent.hosting.HostingRentHttp.Actor;
import cn.jia.agent.service.TaskFeatureEncoder;
import cn.jia.agent.skill.SkillManagementService.RecommendationSkill;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.jupiter.api.Assertions.*;

class CapabilitySearchTest {
    private static final Actor OWNER = new Actor("actor","0","client","owner");
    private static final TaskFeatureEncoder.Identity ID = new TaskFeatureEncoder.Identity("gateway-v1","text-embedding-3-small",1024);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String,Integer> SHARDS = Map.of("total",1,"successful",1,"failed",0,"skipped",0);
    private static AgentTaskAssigneeCandidateDTO agent(String id, List<String> abilities) {
        return new AgentTaskAssigneeCandidateDTO(new AgentTaskTargetSummaryDTO(id,id,"provider",false,"online"),abilities,"provider");
    }
    @Test void publicOrdinalsAreNotDocumentKeysAndWithdrawnSkillsProduceNoCitation() {
        var skill = new RecommendationSkill("private-install","skill","1","展示","用途",true,"READY","EXECUTABLE","key","fact-a");
        var hidden = new RecommendationSkill("private-other","skill","1","私有","不可引用",false,"OFFLINE","EXECUTABLE","other","fact-b");
        var one = CapabilityDocuments.observe(OWNER,agent("a",List.of("写作"," 写作 ")), List.of(hidden,skill),ID);
        var two = CapabilityDocuments.observe(OWNER,agent("a",List.of("其他","写作")), List.of(skill),ID);
        assertEquals(2,one.size()); assertEquals(one.get(0).doc(),two.get(1).doc());
        assertEquals(one.get(1).doc(),two.get(2).doc()); assertNotEquals(one.get(1).displayRef(),two.get(2).displayRef());
        var swapped=CapabilityDocuments.observe(OWNER,agent("a",List.of("写作")),List.of(),new TaskFeatureEncoder.Identity("v2",ID.model(),1024));
        assertNotEquals(one.get(0).doc().id(),swapped.getFirst().doc().id());
        assertThrows(IllegalArgumentException.class,()->CapabilitySearch.batches(Set.of("other"),List.of(one.get(0).doc())));
    }
    @Test void oneRotatingPitAndAuthorizedFiltersForBothRankings() throws Exception { exchange(1,1,"HYBRID"); }
    @Test void missingVectorKeepsOnlyTrustedLexicalResults() throws Exception { exchange(1,0,"LEXICAL_ONLY"); }
    @Test void staleOrUnindexedDocumentsCannotBecomeNoMatch() throws Exception { exchange(0,0,"UNAVAILABLE"); }

    private static void exchange(int present,int encoded,String expected) throws Exception {
        var doc=CapabilityDocuments.observe(OWNER,agent("a",List.of("写作")),List.of(),ID).getFirst().doc();
        var requests=new CopyOnWriteArrayList<JsonNode>(); var paths=new CopyOnWriteArrayList<String>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/", x->{
            var path=x.getRequestURI().getPath(); paths.add(path);
            var body=x.getRequestBody().readAllBytes(); var query=body.length==0?JSON.createObjectNode():JSON.readTree(body); requests.add(query);
            Object response;
            if (path.endsWith("/_pit") && !"DELETE".equals(x.getRequestMethod())) response=Map.of("id","view-1","_shards",SHARDS);
            else if ("DELETE".equals(x.getRequestMethod())) response=Map.of("succeeded",true,"num_freed",1);
            else {
                var result=new LinkedHashMap<String,Object>(); result.put("_shards",SHARDS); result.put("took",1); result.put("timed_out",false);
                result.put("pit_id","view-"+requests.size());
                if(query.path("size").asInt()==0){
                    result.put("hits",Map.of("total",Map.of("relation","eq","value",present),"hits",List.of()));
                    result.put("aggregations",Map.of("filter#encoded",Map.of("doc_count",encoded)));
                } else result.put("hits",Map.of("hits",List.of(Map.of("_index",CapabilityIndexWriter.GENERATION,"_id",doc.id(),"_score",0.8))));
                response=result;
            }
            var bytes=JSON.writeValueAsBytes(response); x.getResponseHeaders().set("Content-Type","application/json");
            x.getResponseHeaders().set("X-Elastic-Product","Elasticsearch"); x.sendResponseHeaders(200,bytes.length);
            try(var out=x.getResponseBody()){out.write(bytes);}
        });
        server.start();
        try(var es=ElasticsearchClient.of(b->b.host("http://127.0.0.1:"+server.getAddress().getPort()).useCompression(false))){
            var beans=new DefaultListableBeanFactory(); beans.registerSingleton("es",es);
            var vector=new float[1024]; vector[0]=1;
            var result=new CapabilitySearch(beans.getBeanProvider(ElasticsearchClient.class)).retrieve(Set.of("a","b"),List.of(doc),"写作",new TaskFeatureEncoder.Vector(ID,vector));
            assertEquals(expected,result.mode()); assertEquals(2,result.rows().size());
            assertEquals("/agent_capability_evidence/_pit",paths.getFirst()); assertEquals("/_pit",paths.getLast());
            assertEquals(switch(expected){case "HYBRID"->5;case "LEXICAL_ONLY"->4;default->3;},paths.size());
            for(int i=1;i<requests.size()-1;i++){
                assertEquals("/_search",paths.get(i));var req=requests.get(i);
                assertEquals("view-"+i,req.at("/pit/id").asText());
                var filter=req.at(i==3?"/query/script_score/query/bool/filter":"/query/bool/filter");
                assertEquals(doc.id(),filter.at("/0/ids/values/0").asText());assertEquals(doc.partition(),filter.at("/1/term/partition/value").asText());
                if(i>1)assertEquals("agent_id",req.at("/collapse/field").asText());
            }
            assertEquals("view-"+(requests.size()-1),requests.getLast().path("id").asText());
            if(encoded!=0)assertNotNull(result.rows().getFirst().vector());
        } finally {server.stop(0);}
    }
}
