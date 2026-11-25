package cn.jia.economy.pricing;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Monetary aggregation only. Eligibility, calibration, distance and neighbour selection belong upstream. */
public final class MeasuredNeighborEstimate {
    private MeasuredNeighborEstimate() { }

    /** weight is positive unnormalized distance weight; revision identifies the exact observed whole-task sample. */
    public record Neighbor(String sampleId, String revision, BigDecimal weight, List<MeasuredTaskRepricing.Fact> facts) {
        public Neighbor {
            if (sampleId == null || sampleId.isBlank() || revision == null || revision.isBlank() || weight.signum() <= 0)
                throw new IllegalArgumentException("Invalid measured neighbour");
            facts = List.copyOf(facts);
        }
    }
    public record Contribution(String sampleId, String revision, BigDecimal weight, MeasuredTaskRepricing.Result repricing) { }
    /** normalized weight = contribution.weight / totalWeight. Retain the exact fraction, including repeating decimals. */
    public record Estimate(List<Contribution> contributions, BigDecimal weightedYuan, BigDecimal totalWeight, long wen) {
        public Estimate { contributions = List.copyOf(contributions); }
    }
    public static Estimate aggregate(MeasuredTaskRepricing.Reference frozen, List<Neighbor> selected) {
        if (selected.isEmpty()) throw new MeasuredTaskRepricing.Unavailable("NO_BASELINE");
        var ids = new HashSet<String>();
        var lines = new ArrayList<Contribution>();
        var numerator = BigDecimal.ZERO;
        var denominator = BigDecimal.ZERO;
        for (var neighbor : selected) {
            if (!ids.add(neighbor.sampleId())) throw new IllegalArgumentException("Duplicate whole-task neighbour");
            var price = MeasuredTaskRepricing.reprice(frozen, neighbor.facts());
            lines.add(new Contribution(neighbor.sampleId(), neighbor.revision(), neighbor.weight(), price));
            numerator = numerator.add(price.yuan().multiply(neighbor.weight()));
            denominator = denominator.add(neighbor.weight());
        }
        return new Estimate(lines, numerator, denominator, CnyAmounts.quoteWen(numerator, denominator));
    }
}
