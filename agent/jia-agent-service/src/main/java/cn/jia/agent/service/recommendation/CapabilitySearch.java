package cn.jia.agent.service.recommendation;

import cn.jia.agent.service.TaskFeatureEncoder.Vector;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.TotalHitsRelation;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.io.StringReader;
import java.util.*;

/** Independent capability index. One PIT across all authorized batches, not a search of chat memory. */
@Component
final class CapabilitySearch {
    static final String INDEX = "agent_capability_evidence";
    private static final String KEEP_ALIVE = "1m";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(CapabilitySearch.class);
    record Result(String mode, List<CandidateRankFusion.Ranked> rows, List<String> reasons) { }
    private final ObjectProvider<ElasticsearchClient> clients;
    CapabilitySearch(ObjectProvider<ElasticsearchClient> clients) { this(clients, Optional.empty()); }
    private final ObservationRegistry observations;
    @org.springframework.beans.factory.annotation.Autowired
    CapabilitySearch(ObjectProvider<ElasticsearchClient> clients, Optional<ObservationRegistry> registry) {
        this.clients=clients; this.observations=registry.orElse(ObservationRegistry.NOOP);
    }

    record QueryVector(Vector value,String missingReason) { }
    Result retrieve(Set<String> candidates, List<CapabilityDocuments.Doc> authorized, String text, java.util.function.Supplier<QueryVector> query) {
        var lexical = new ArrayList<CandidateRankFusion.Hit>();
        var semantic = new ArrayList<CandidateRankFusion.Hit>();
        if (candidates.isEmpty()) return new Result("NOT_NEEDED", List.of(), List.of());
        // Batches preserve whole candidates except a single very large candidate, whose best hits
        // are still merged before global ranking. No request can exceed 4096 terms or 128 groups.
        var batches = batches(candidates, authorized);
        var es = clients.getIfAvailable();
        if (es == null) return unavailable(candidates, "CAPABILITY_SEARCH_UNAVAILABLE");
        var view = new View(es);
        boolean wordsComplete = false;
        try {
            var open = es.openPointInTime(co.elastic.clients.elasticsearch.core.OpenPointInTimeRequest.of(b -> b.index(INDEX).allowPartialSearchResults(false).keepAlive(t -> t.time(KEEP_ALIVE))));
            view.pit = open.id();
            if (open.shards() != null && open.shards().failed().intValue() != 0) throw new IOException("partial PIT");
            boolean vectorsComplete = true;
            for (var batch : batches) {
                var coverage = view.search(batch.coverage());
                var total = coverage.hits().total();
                if (total == null || total.relation() != TotalHitsRelation.Eq || total.value() != batch.docs.size())
                    return unavailable(candidates, "CAPABILITY_INDEX_INCOMPLETE");
                var count = coverage.aggregations().get("encoded");
                if (count == null || !count.isFilter() || count.filter().docCount() != total.value()) vectorsComplete = false;
                readRanked(view,batch,text,null,lexical);
            }
            wordsComplete = true;
            if (!vectorsComplete) return lexical(candidates, lexical, "CAPABILITY_VECTOR_INCOMPLETE");
            // Only a complete authorized index can consume a query embedding. Never reopen another PIT to rescue this view.
            var resolved=query.get();var vector=resolved.value();
            if(vector==null)return lexical(candidates,lexical,resolved.missingReason());
            if(authorized.stream().anyMatch(d->!vector.identity().equals(d.embedding())))return lexical(candidates,lexical,"CAPABILITY_VECTOR_INCOMPLETE");
            var values = vector.values();
            for (var batch : batches) {
                readRanked(view,batch,text,values,semantic);
            }
            return new Result("HYBRID", CandidateRankFusion.measured(observations, candidates, lexical, semantic), List.of());
        } catch (IOException | co.elastic.clients.elasticsearch._types.ElasticsearchException failed) {
            return wordsComplete ? lexical(candidates, lexical, "CAPABILITY_VECTOR_SEARCH_FAILED") : unavailable(candidates, "CAPABILITY_SEARCH_FAILED");
        } finally {
            if (view.pit != null) try { es.closePointInTime(co.elastic.clients.elasticsearch.core.ClosePointInTimeRequest.of(b -> b.id(view.pit))); }
            catch (IOException | RuntimeException closeFailure) { LOG.warn("capability_search_pit_close_failed"); }
        }
    }
    // One actual ranked ES batch, never an authorization/coverage probe or an embedding call.
    private void readRanked(View view,Batch batch,String text,float[] vector,List<CandidateRankFusion.Hit> into) throws IOException {
        var span=Observation.createNotStarted("cyf.task.recommendation.search",observations)
                .lowCardinalityKeyValue("route",vector==null?"lexical":"vector").start();
        String outcome="error";
        try {
            var res=view.search(batch.ranked(text,vector));
            if(vector!=null && res.hits().hits().size()!=batch.agents.size()) throw new IOException("incomplete vector groups");
            batch.collect(res,into); outcome="ok";
        } finally { span.lowCardinalityKeyValue("outcome",outcome).stop(); }
    }
    private Result unavailable(Set<String> ids, String reason) {
        return new Result("UNAVAILABLE", CandidateRankFusion.measured(observations, ids, List.of(), List.of()), List.of(reason));
    }
    private Result lexical(Set<String> ids, List<CandidateRankFusion.Hit> hits, String reason) {
        return new Result("LEXICAL_ONLY", CandidateRankFusion.measured(observations, ids, hits, List.of()), List.of(reason));
    }
    private static final class View {
        private final ElasticsearchClient es;
        View(ElasticsearchClient es) { this.es = es; }
        String pit;
        SearchResponse<Void> search(Map<String,Object> query) throws IOException {
            var body = new LinkedHashMap<>(query); body.put("pit", Map.of("id", pit, "keep_alive", KEEP_ALIVE));
            var request = new SearchRequest.Builder().allowPartialSearchResults(false)
                    .withJson(new StringReader(JSON.writeValueAsString(body))).build();
            var response = es.search(request, Void.class);
            if (response.pitId() != null) pit = response.pitId(); // ES can rotate the handle within the same view.
            if (response.timedOut() || response.shards().failed().intValue() != 0) throw new IOException("partial search");
            return response;
        }
    }
    static List<Batch> batches(Set<String> candidates, List<CapabilityDocuments.Doc> docs) {
        var grouped = new TreeMap<String,List<CapabilityDocuments.Doc>>(); String partition = null;
        for (var doc : docs) {
            if (!candidates.contains(doc.agentId()) || (partition != null && !partition.equals(doc.partition())))
                throw new IllegalArgumentException("unexpected capability partition or candidate");
            partition = doc.partition(); grouped.computeIfAbsent(doc.agentId(), ignored -> new ArrayList<>()).add(doc);
        }
        var result = new ArrayList<Batch>(); Batch b = new Batch(partition);
        for (var group : grouped.values()) for (var doc : group) {
            if (b.docs.size() == 4096 || (!b.agents.contains(doc.agentId()) && b.agents.size() == 128)) {
                result.add(b); b = new Batch(partition);
            }
            b.docs.put(doc.id(),doc); b.agents.add(doc.agentId());
        }
        if (!b.docs.isEmpty()) result.add(b);
        return result;
    }
    static final class Batch {
        final Map<String,CapabilityDocuments.Doc> docs = new LinkedHashMap<>();
        final Set<String> agents = new HashSet<>();
        private final String partition;
        Batch(String partition) { this.partition = partition; }
        private List<Object> filters() { return new ArrayList<>(List.of(Map.of("ids", Map.of("values", docs.keySet())),
                Map.of("term", Map.of("partition", Map.of("value",partition))))); }
        Map<String,Object> coverage() { return Map.of("size",0,"track_total_hits",true,"query",Map.of("bool",Map.of("filter",filters())),
                "aggs",Map.of("encoded",Map.of("filter",Map.of("exists",Map.of("field","vector"))))); }
        Map<String,Object> ranked(String text, float[] vector) {
            var filters = filters(); Object query;
            if (vector == null) query = Map.of("bool", Map.of("filter",filters,"must",List.of(Map.of("match",Map.of("text",Map.of("query",text))))));
            else {
                filters.add(Map.of("exists",Map.of("field","vector")));
                query = Map.of("script_score",Map.of("query",Map.of("bool",Map.of("filter",filters)),"script",Map.of(
                        "source","cosineSimilarity(params.v, 'vector') + 1.0", "params",Map.of("v",vector))));
            }
            return Map.of("size",agents.size(),"_source",false,"track_total_hits",false,"query",query,
                    "collapse",Map.of("field","agent_id"),"sort",List.of(Map.of("_score",Map.of("order","desc")),Map.of("evidence_key",Map.of("order","asc"))));
        }
        void collect(SearchResponse<Void> response, List<CandidateRankFusion.Hit> hits) throws IOException {
            var seen = new HashSet<String>();
            for (var h : response.hits().hits()) {
                var doc = docs.get(h.id());
                if (doc == null || h.score() == null || !Double.isFinite(h.score()) || !seen.add(doc.agentId()))
                    throw new IOException("invalid grouped search result");
                hits.add(new CandidateRankFusion.Hit(doc.agentId(), doc.evidenceKey(), h.score(),h.index()));
            }
        }
    }
}
