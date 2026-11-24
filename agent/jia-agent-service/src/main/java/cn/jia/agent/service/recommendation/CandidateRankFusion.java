package cn.jia.agent.service.recommendation;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Candidate-level RRF for already authorized, same-PIT retrieval results (§3.2).
 * Does not assign suitability levels or infer abilities from missing hits. */
final class CandidateRankFusion {
    private CandidateRankFusion() { }
    static final String VERSION = "candidate-rrf-k60-v1";
    private static final int K = 60;
    record Hit(String agentId, String evidenceKey, double score,String physicalIndex) { Hit(String agentId,String evidenceKey,double score){this(agentId,evidenceKey,score,null);} }
    record Ranked(String agentId, double score, Hit lexical, Hit vector) { }
    private record RouteRank(Hit hit, int rank) { }

    private static final Comparator<Hit> BEST = Comparator.comparingDouble(Hit::score).reversed()
            .thenComparing(Hit::evidenceKey);

    static List<Ranked> fuse(Set<String> candidates, Collection<Hit> lexical, Collection<Hit> vector) {
        var words = ranks(candidates, lexical);
        var vectors = ranks(candidates, vector);
        return candidates.stream().map(id -> {
            var l = words.get(id);
            var v = vectors.get(id);
            return new Ranked(id, contribution(l) + contribution(v), l == null ? null : l.hit(), v == null ? null : v.hit());
        }).sorted(Comparator.comparingDouble(Ranked::score).reversed().thenComparing(Ranked::agentId)).toList();
    }

    private static double contribution(RouteRank r) { return r == null ? 0 : 1.0 / (K + r.rank()); }

    private static Map<String, RouteRank> ranks(Set<String> candidates, Collection<Hit> hits) {
        Map<String, Hit> best = new HashMap<>();
        for (var hit : hits) {
            if (!candidates.contains(hit.agentId()) || !Double.isFinite(hit.score())) {
                throw new IllegalArgumentException("invalid candidate retrieval result");
            }
            best.merge(hit.agentId(), hit, (a, b) -> BEST.compare(a, b) <= 0 ? a : b);
        }
        var ordered = best.values().stream().sorted(Comparator.comparingDouble(Hit::score).reversed()
                .thenComparing(Hit::agentId)).toList();
        Map<String, RouteRank> ranks = new HashMap<>();
        for (int i = 0; i < ordered.size(); i++) ranks.put(ordered.get(i).agentId(), new RouteRank(ordered.get(i), i + 1));
        return ranks;
    }
}
