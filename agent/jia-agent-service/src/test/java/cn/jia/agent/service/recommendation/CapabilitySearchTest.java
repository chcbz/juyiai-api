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
    @Test void declaredRepairReadsScopeAndPublishesEveryKeywordBeforeAnyEncoding() throws Exception {
        var facts=org.mockito.Mockito.mock(DeclaredCapabilityFacts.class);
        var writer=org.mockito.Mockito.mock(CapabilityIndexWriter.class);
        org.mockito.Mockito.doCallRealMethod().when(writer).repair(org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.any());
        var encoder=org.mockito.Mockito.mock(TaskFeatureEncoder.class);
        var schedules=org.mockito.Mockito.mock(cn.jia.task.scheduling.ScheduleService.class);
        var repair=new DeclaredCapabilityRepair(schedules,new cn.jia.core.security.FixedTenantAdmissionPolicy("0","client"),facts,encoder,writer);
        var key=new cn.jia.task.scheduling.ScheduleKey("client",repair.getName(),"7","");var invocation=new cn.jia.task.scheduling.ScheduledInvocation("0",key,1);
        org.mockito.Mockito.when(facts.declaredCapabilities("0","client",7)).thenReturn(Optional.of(new DeclaredCapabilityFacts.Snapshot("0","client","provider","a",List.of("写作","调研"))));
        org.mockito.Mockito.when(encoder.identity()).thenReturn(ID);
        var docs=CapabilityDocuments.observe(OWNER,agent("a",List.of("写作","调研")),List.of(),ID);
        org.mockito.Mockito.when(encoder.encode("写作")).thenThrow(new TaskFeatureEncoder.Unavailable());
        assertThrows(TaskFeatureEncoder.Unavailable.class,()->repair.executeScheduled(invocation));
        var order=org.mockito.Mockito.inOrder(writer,encoder);for(var d:docs)order.verify(writer).publishText(d.doc());order.verify(encoder).encode("写作");
        org.mockito.Mockito.when(writer.encoded(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        org.mockito.Mockito.clearInvocations(writer,encoder);repair.executeScheduled(invocation);
        org.mockito.Mockito.verify(encoder,org.mockito.Mockito.never()).encode(org.mockito.ArgumentMatchers.anyString());
        org.mockito.Mockito.verify(writer,org.mockito.Mockito.never()).publishText(org.mockito.ArgumentMatchers.any());
        assertThrows(IllegalArgumentException.class,()->repair.executeScheduled(new cn.jia.task.scheduling.ScheduledInvocation("foreign",key,1)));
    }
    @Test void skillSweepEmitsOnlyLocalHintsAndConsumerReadsAuthorityNotScheduledContents() throws Exception {
        var hints=org.mockito.Mockito.mock(cn.jia.agent.service.SkillCatalogIndexHints.class);
        var skills=org.mockito.Mockito.mock(cn.jia.agent.skill.SkillManagementService.class);
        var schedules=org.mockito.Mockito.mock(cn.jia.task.scheduling.ScheduleService.class);
        var writer=org.mockito.Mockito.mock(CapabilityIndexWriter.class);var encoder=org.mockito.Mockito.mock(TaskFeatureEncoder.class);
        var repair=new SkillCapabilityRepair(new cn.jia.core.security.FixedTenantAdmissionPolicy("0",null),hints,skills,encoder,writer,schedules,
                org.mockito.Mockito.mock(cn.jia.task.scheduling.CronScheduleService.class),org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class),"node-a","0 17 * * * *");
        var key=new cn.jia.task.scheduling.ScheduleKey("client",repair.getName(),"agent:a","node-a");
        org.mockito.Mockito.when(hints.local("0",null)).thenReturn(List.of(new cn.jia.agent.service.SkillCatalogIndexHints.Key("0","client","a")));
        repair.executeScheduled(new cn.jia.task.scheduling.ScheduledInvocation("0",new cn.jia.task.scheduling.ScheduleKey("",repair.getName(),"cron:123","node-a"),1));
        org.mockito.Mockito.verify(schedules).signal(org.mockito.ArgumentMatchers.eq(key),org.mockito.ArgumentMatchers.anyLong());
        assertThrows(IllegalArgumentException.class,()->repair.executeScheduled(new cn.jia.task.scheduling.ScheduledInvocation("0",new cn.jia.task.scheduling.ScheduleKey("client",repair.getName(),"agent:a","node-b"),2)));
        org.mockito.Mockito.verifyNoInteractions(skills,writer,encoder);
        var skill=new RecommendationSkill("install","skill","1","识图","文字",true,"NOT_EXECUTABLE","INSTRUCTION","key","fact");
        org.mockito.Mockito.when(encoder.identity()).thenReturn(ID);org.mockito.Mockito.when(skills.citableSkillPurposes("0","client","a")).thenReturn(List.of(skill),List.of());
        var work=new cn.jia.task.scheduling.ScheduledInvocation("0",key,1);repair.executeScheduled(work);repair.executeScheduled(work);
        var expected=CapabilityDocuments.observe(OWNER,agent("a",List.of()),List.of(skill),ID);
        org.mockito.Mockito.verify(writer).repair(expected,encoder);org.mockito.Mockito.verifyNoMoreInteractions(writer);
    }
    @Test void oneRotatingPitAndAuthorizedFiltersForBothRankings() throws Exception { exchange(1,1,"HYBRID"); }
    @Test void missingVectorKeepsOnlyTrustedLexicalResults() throws Exception { exchange(1,0,"LEXICAL_ONLY"); }
    @Test void staleOrUnindexedDocumentsCannotBecomeNoMatch() throws Exception { exchange(0,0,"UNAVAILABLE"); }

    private static void exchange(int present,int encoded,String expected) throws Exception {
        exchange(present,encoded,expected,0);
    }
    @Test void lostPitAfterEncodingReturnsOnlyPreviouslyAuthorizedLexicalHits() throws Exception {
        exchange(1,1,"LEXICAL_ONLY",4);
    }
    @Test void lostPitBeforeLexicalCompletesNeverEncodesOrReturnsPartialRanks() throws Exception {
        exchange(1,1,"UNAVAILABLE",3);
    }
    private static void exchange(int present,int encoded,String expected,int expiredAt) throws Exception {
        var doc=CapabilityDocuments.observe(OWNER,agent("a",List.of("写作")),List.of(),ID).getFirst().doc();
        var requests=new CopyOnWriteArrayList<JsonNode>(); var paths=new CopyOnWriteArrayList<String>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/", x->{
            var path=x.getRequestURI().getPath(); paths.add(path);
            var body=x.getRequestBody().readAllBytes(); var query=body.length==0?JSON.createObjectNode():JSON.readTree(body); requests.add(query);
            Object response;
            int status=200;
            if(expiredAt!=0 && (requests.size()==expiredAt || "DELETE".equals(x.getRequestMethod()))){
                status=404;response=Map.of("error",Map.of("type","search_context_missing_exception","reason","fixture view expired"),"status",404);
            } else if (path.endsWith("/_pit") && !"DELETE".equals(x.getRequestMethod())) response=Map.of("id","view-1","_shards",SHARDS);
            else if ("DELETE".equals(x.getRequestMethod())) response=Map.of("succeeded",true,"num_freed",1);
            else {
                var result=new LinkedHashMap<String,Object>(); result.put("_shards",SHARDS); result.put("took",1); result.put("timed_out",false);
                result.put("pit_id","view-"+requests.size());
                if(query.path("size").asInt()==0){
                    result.put("hits",Map.of("total",Map.of("relation","eq","value",present),"hits",List.of()));
                    result.put("aggregations",Map.of("filter#encoded",Map.of("doc_count",encoded),"sterms#physical",Map.of("buckets",List.of(Map.of("key",CapabilityIndexWriter.GENERATION,"doc_count",present)))));
                } else result.put("hits",Map.of("hits",List.of(Map.of("_index",CapabilityIndexWriter.GENERATION,"_id",doc.id(),"_score",0.8))));
                response=result;
            }
            var bytes=JSON.writeValueAsBytes(response); x.getResponseHeaders().set("Content-Type","application/json");
            x.getResponseHeaders().set("X-Elastic-Product","Elasticsearch"); x.sendResponseHeaders(status,bytes.length);
            try(var out=x.getResponseBody()){out.write(bytes);}
        });
        server.start();
        try(var es=ElasticsearchClient.of(b->b.host("http://127.0.0.1:"+server.getAddress().getPort()).useCompression(false))){
            var beans=new DefaultListableBeanFactory(); beans.registerSingleton("es",es);
            var vector=new float[1024]; vector[0]=1;
            var calls=new java.util.concurrent.atomic.AtomicInteger();
            var registry=io.micrometer.observation.ObservationRegistry.create();var stages=new ArrayList<String>();
            registry.observationConfig().observationHandler(new io.micrometer.observation.ObservationHandler<io.micrometer.observation.Observation.Context>(){
                public boolean supportsContext(io.micrometer.observation.Observation.Context c){return true;}
                public void onStop(io.micrometer.observation.Observation.Context c){
                    assertFalse(c.getHighCardinalityKeyValues().iterator().hasNext());var tags=new HashMap<String,String>();
                    c.getLowCardinalityKeyValues().forEach(k->tags.put(k.getKey(),k.getValue()));
                    if(c.getName().equals("cyf.task.recommendation.rank")){assertTrue(tags.isEmpty());stages.add("rank");}
                    else{assertEquals("cyf.task.recommendation.search",c.getName());assertEquals(Set.of("route","outcome"),tags.keySet());stages.add(tags.get("route")+":"+tags.get("outcome"));}
                }
            });
            var result=new CapabilitySearch(beans.getBeanProvider(ElasticsearchClient.class),Optional.of(registry)).retrieve(Set.of("a","b"),List.of(doc),"写作",()->{
                assertEquals(3,requests.size(),"PIT, exact coverage and lexical ranking before encoding");calls.incrementAndGet();
                return new CapabilitySearch.QueryVector(new TaskFeatureEncoder.Vector(ID,vector),null);
            });
            assertEquals("HYBRID".equals(expected)||expiredAt==4?1:0,calls.get());
            var expectedStages=new ArrayList<String>();
            if(present!=0)expectedStages.add(expiredAt==3?"lexical:error":"lexical:ok");
            if(encoded!=0 && expiredAt!=3)expectedStages.add(expiredAt==4?"vector:error":"vector:ok");
            expectedStages.add("rank");assertEquals(expectedStages,stages);
            if(expiredAt!=0)assertEquals(List.of(expiredAt==4?"CAPABILITY_VECTOR_SEARCH_FAILED":"CAPABILITY_SEARCH_FAILED"),result.reasons());
            else if("LEXICAL_ONLY".equals(expected))assertEquals(List.of("CAPABILITY_VECTOR_INCOMPLETE"),result.reasons());
            assertEquals(expected,result.mode()); assertEquals(2,result.rows().size());
            assertEquals("/agent_capability_evidence/_pit",paths.getFirst()); assertEquals("/_pit",paths.getLast());
            assertEquals(expiredAt!=0?expiredAt+1:switch(expected){case "HYBRID"->5;case "LEXICAL_ONLY"->4;default->3;},paths.size());
            for(int i=1;i<requests.size()-1;i++){
                assertEquals("/_search",paths.get(i));var req=requests.get(i);
                assertEquals("view-"+i,req.at("/pit/id").asText());
                var filter=req.at(i==3?"/query/script_score/query/bool/filter":"/query/bool/filter");
                assertEquals(doc.id(),filter.at("/0/ids/values/0").asText());assertEquals(doc.partition(),filter.at("/1/term/partition/value").asText());
                if(i>1)assertEquals("agent_id",req.at("/collapse/field").asText());
            }
            assertEquals("view-"+(expiredAt==0?requests.size()-1:expiredAt-1),requests.getLast().path("id").asText());
            if("HYBRID".equals(expected))assertNotNull(result.rows().getFirst().vector());
            else assertTrue(result.rows().stream().allMatch(row->row.vector()==null));
            var cited=result.rows().stream().filter(row->row.lexical()!=null).map(row->row.lexical().evidenceKey()).toList();
            assertEquals("UNAVAILABLE".equals(expected)?List.of():List.of(doc.evidenceKey()),cited);
        } finally {server.stop(0);}
    }
}
