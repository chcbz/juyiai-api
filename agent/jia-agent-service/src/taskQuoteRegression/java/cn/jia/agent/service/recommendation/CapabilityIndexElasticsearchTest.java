package cn.jia.agent.service.recommendation;

import cn.jia.agent.service.TaskFeatureEncoder;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in only: the owning fixture supplies a random loopback port and an exact fresh cluster name.
 * No remote/configured address, chat index, provider API, test cleanup DELETE or cluster-settings writes. */
class CapabilityIndexElasticsearchTest {
    private static final TaskFeatureEncoder.Identity ID=new TaskFeatureEncoder.Identity("fixture-1","text-embedding-3-small",1024);
    private static List<CapabilityDocuments.Citation> abilities(String id,TaskFeatureEncoder.Identity identity,String... text){
        return CapabilityDocuments.declared("0","isolated-client","owner",id,List.of(text),identity);
    }
    private static TaskFeatureEncoder.Vector vector(TaskFeatureEncoder.Identity identity,int dimension){float[] values=new float[1024];values[dimension]=1;return new TaskFeatureEncoder.Vector(identity,values);}
    /** Component benchmark; no simulated gateway delay. Includes all retrieval outcomes, not HTTP/DB time. */
    private static void measureSearch(CapabilitySearch search,CapabilityIndexWriter writer,ElasticsearchClient es) throws Exception {
        var docs=new ArrayList<CapabilityDocuments.Doc>();var ids=new java.util.LinkedHashSet<String>();var v=vector(ID,0);
        for(int i=0;i<108;i++){
            String id="load-"+i;ids.add(id);var doc=abilities(id,ID,"合同审查 "+i).getFirst().doc();docs.add(doc);writer.publishVector(doc,v);
        }
        es.indices().refresh(b->b.index(CapabilityIndexWriter.GENERATION));
        int invalid=0;
        for(int n:List.of(1,108))for(int concurrency:List.of(1,5,10)){
            var subset=List.copyOf(docs.subList(0,n));var allowed=Set.copyOf(ids.stream().limit(n).toList());
            var samples=new ArrayList<Long>();int degraded=0,errors=0;int rounds=30/concurrency;
            try(var pool=java.util.concurrent.Executors.newFixedThreadPool(concurrency)){
                for(int round=0;round<rounds;round++){
                    var ready=new java.util.concurrent.CountDownLatch(concurrency);var go=new java.util.concurrent.CountDownLatch(1);
                    record Sample(long nanos,boolean degraded,boolean error){}var pending=new ArrayList<java.util.concurrent.Future<Sample>>();
                    for(int lane=0;lane<concurrency;lane++)pending.add(pool.submit(()->{
                        ready.countDown();go.await();long start=System.nanoTime();try{
                            var r=search.retrieve(allowed,subset,"合同审查",()->new CapabilitySearch.QueryVector(v,null));
                            long elapsed=System.nanoTime()-start;
                            return new Sample(elapsed,!"HYBRID".equals(r.mode()),r.rows().size()!=n || !allowed.equals(r.rows().stream().map(CandidateRankFusion.Ranked::agentId).collect(java.util.stream.Collectors.toSet())));
                        }catch(RuntimeException failure){return new Sample(System.nanoTime()-start,false,true);}
                    }));
                    ready.await();go.countDown();for(var f:pending){var s=f.get();samples.add(s.nanos);if(s.degraded)degraded++;if(s.error)errors++;}
                }
            }
            samples.sort(Long::compare);System.out.printf(java.util.Locale.ROOT,"CAPABILITY_ES_LOAD candidates=%d concurrency=%d count=%d degraded=%d errors=%d p50ms=%.3f p95ms=%.3f maxms=%.3f%n",
                n,concurrency,samples.size(),degraded,errors,samples.get(14)/1e6,samples.get(28)/1e6,samples.getLast()/1e6);
            invalid+=errors+degraded;
        }
        assertEquals(0,invalid,"see all six measured groups above");
    }
    @Test void realPitRecallVersionCoverageAndWithdrawnAuthority() throws Exception {
        int port=Integer.parseInt(System.getProperty("cyf.capabilityEsPort"));String cluster=System.getProperty("cyf.capabilityEsCluster");
        assertTrue(port>=1024&&port<=65535&&port!=9200&&port!=9300);assertTrue(cluster.matches("cyf-rb-fixture-[a-f0-9]{32}"));
        try(var es=ElasticsearchClient.of(b->b.host("http://127.0.0.1:"+port).useCompression(false))){
            var info=es.info();assertEquals(cluster,info.clusterName());assertEquals("9.3.0",info.version().number());
            String index=CapabilityIndexWriter.GENERATION;
            for(String target:List.of(index,index+"_write",CapabilitySearch.INDEX))assertFalse(es.indices().exists(b->b.index(target)).value());
            var beans=new DefaultListableBeanFactory();beans.registerSingleton("es",es);var provider=beans.getBeanProvider(ElasticsearchClient.class);
            var search=new CapabilitySearch(provider);var writer=new CapabilityIndexWriter(provider);var all=new ArrayList<CapabilityDocuments.Citation>();
            all.addAll(abilities("a",ID,"合同审查","合同条款核对"));all.addAll(abilities("b",ID,"统计报表"));
            var denied=abilities("withdrawn",ID,"合同审查").getFirst();var authorized=all.stream().map(CapabilityDocuments.Citation::doc).toList();var ids=Set.of("a","b","empty");
            var q=vector(ID,0);assertEquals("UNAVAILABLE",search.retrieve(ids,authorized,"合同审查",()->new CapabilitySearch.QueryVector(q,null)).mode());
            try(var mapping=Objects.requireNonNull(getClass().getResourceAsStream("/elasticsearch/agent-capability-evidence-v1.json"))){es.indices().create(b->b.index(index).withJson(mapping));}
            assertEquals("UNAVAILABLE",search.retrieve(ids,authorized,"合同审查",()->new CapabilitySearch.QueryVector(q,null)).mode());
            for(var doc:authorized)writer.publishText(doc);es.indices().refresh(b->b.index(index));
            var lexical=search.retrieve(ids,authorized,"合同审查",()->new CapabilitySearch.QueryVector(q,null));assertEquals("LEXICAL_ONLY",lexical.mode());assertEquals("a",lexical.rows().getFirst().agentId());assertNotNull(lexical.rows().getFirst().lexical());
            var encoder=new TaskFeatureEncoder(){public Identity identity(){return ID;} public Vector encode(String text){return vector(ID,text.contains("合同")?0:1);}};
            writer.repair(all,encoder);writer.repair(List.of(denied),encoder);es.indices().refresh(b->b.index(index));
            for(var doc:authorized)assertTrue(writer.encoded(doc));var hybrid=search.retrieve(ids,authorized,"合同审查",()->new CapabilitySearch.QueryVector(q,null));
            assertEquals("HYBRID",hybrid.mode());assertEquals("a",hybrid.rows().getFirst().agentId());assertNotNull(hybrid.rows().getFirst().vector());
            assertEquals(ids,hybrid.rows().stream().map(CandidateRankFusion.Ranked::agentId).collect(java.util.stream.Collectors.toSet()));
            var empty=hybrid.rows().stream().filter(r->r.agentId().equals("empty")).findFirst().orElseThrow();assertNull(empty.lexical());assertNull(empty.vector());
            // Stale write after a loses its authority cannot take part even with perfect lexical/vector scores.
            writer.publishVector(authorized.getFirst(),q);es.indices().refresh(b->b.index(index));
            var onlyB=search.retrieve(Set.of("b"),List.of(authorized.getLast()),"合同审查",()->new CapabilitySearch.QueryVector(q,null));assertEquals("HYBRID",onlyB.mode());assertEquals(List.of("b"),onlyB.rows().stream().map(CandidateRankFusion.Ranked::agentId).toList());
            var next=new TaskFeatureEncoder.Identity("fixture-2",ID.model(),1024);var newDoc=abilities("a",next,"合同审查").getFirst().doc();
            assertFalse(writer.encoded(newDoc));assertEquals("UNAVAILABLE",search.retrieve(Set.of("a"),List.of(newDoc),"合同审查",()->new CapabilitySearch.QueryVector(vector(next,0),null)).mode());
            writer.publishText(newDoc);es.indices().refresh(b->b.index(index));assertEquals("LEXICAL_ONLY",search.retrieve(Set.of("a"),List.of(newDoc),"合同审查",()->new CapabilitySearch.QueryVector(vector(next,0),null)).mode());
            if(Boolean.getBoolean("cyf.capabilityEsLoad"))measureSearch(search,writer,es);
            // Deliberately mis-provision this disposable read alias. The write alias stays single-target.
            String other=index+"_fixture_alt";
            try(var mapping=getClass().getResourceAsStream("/elasticsearch/agent-capability-evidence-v1.json")){
                var json=new com.fasterxml.jackson.databind.ObjectMapper();var schema=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(mapping);
                schema.withObject("/aliases").remove(index+"_write");String mappingJson=json.writeValueAsString(schema);es.indices().create(b->b.index(other).withJson(new java.io.StringReader(mappingJson)));
            }
            var a=authorized.getFirst();var missing=abilities("a",ID,"missing").getFirst().doc();
            var source=es.get(g->g.index(index).id(a.id()),java.util.Map.class).source();
            es.index(b->b.index(other).id(a.id()).document(source));es.indices().refresh(b->b.index(other));
            java.util.function.Supplier<CapabilitySearch.QueryVector> unused=()->{throw new AssertionError("mixed coverage must not encode");};
            var duplicate=search.retrieve(Set.of("a"),List.of(a,missing),"合同",unused);
            assertEquals("UNAVAILABLE",duplicate.mode());assertEquals(List.of("CAPABILITY_INDEX_INCOMPLETE"),duplicate.reasons());
            assertNull(duplicate.rows().getFirst().lexical());
            // Each batch alone is complete and single-index; the 129th candidate lives in another generation.
            var across=new ArrayList<CapabilityDocuments.Doc>();var names=new java.util.HashSet<String>();
            for(int i=0;i<129;i++){
                String name="cross-"+String.format(java.util.Locale.ROOT,"%03d",i);var doc=abilities(name,ID,"审查").getFirst().doc();across.add(doc);names.add(name);
                if(i<128)writer.publishText(doc);else es.index(b->b.index(other).id(doc.id()).document(java.util.Map.of("agent_id",name,"partition",doc.partition(),"evidence_key",doc.evidenceKey(),"text",doc.text())));
            }
            es.indices().refresh(b->b.index(index,other));var mixed=search.retrieve(names,across,"审查",unused);
            assertEquals("UNAVAILABLE",mixed.mode());assertEquals(129,mixed.rows().size());assertEquals(List.of("CAPABILITY_INDEX_INCOMPLETE"),mixed.reasons());
            assertTrue(mixed.rows().stream().allMatch(r->r.lexical()==null&&r.vector()==null));
        }
    }
}
