package cn.jia.agent.service.recommendation;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static cn.jia.agent.service.recommendation.CandidateRankFusion.*;
import static org.junit.jupiter.api.Assertions.*;

class CandidateRankFusionTest {
    @Test void ranksAcrossBatchesBeforeFusionAndKeepsOnlyBestEvidence() {
        var ids = Set.of("a", "b", "c");
        var a = new Hit("a", "z", 9);
        var b = new Hit("b", "a", 9);
        var betterKey = new Hit("a", "k", 9);
        var weak = new Hit("b", "x", 1);
        var v = List.of(new Hit("b", "v", 0.7));
        var ranked = fuse(ids, List.of(b, weak, a, betterKey), v);
        assertEquals(ranked, fuse(ids, List.of(betterKey, a, b), v));
        assertEquals(List.of("b", "a", "c"), ranked.stream().map(Ranked::agentId).toList());
        assertEquals(1.0 / 62 + 1.0 / 61, ranked.get(0).score());
        assertEquals(betterKey, ranked.get(1).lexical());
        assertEquals(new Ranked("c", 0.0, null, null), ranked.get(2));
    }
    @Test void unexpectedCandidateOrInvalidScoreCannotEnterRanking() {
        assertThrows(IllegalArgumentException.class, () -> fuse(Set.of("a"), List.of(new Hit("other", "x", 1)), List.of()));
        assertThrows(IllegalArgumentException.class, () -> fuse(Set.of("a"), List.of(), List.of(new Hit("a", "x", Double.NaN))));
    }
}
