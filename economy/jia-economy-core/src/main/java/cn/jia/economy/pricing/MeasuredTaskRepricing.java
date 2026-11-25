package cn.jia.economy.pricing;

import java.math.BigDecimal;
import java.util.*;

/** Reprices measured operations of an already proven whole task. No actual invoice, historical price,
 * predicted token count, wallet, or rounding per response. Callers own authoritative membership and scope. */
public final class MeasuredTaskRepricing {
    private MeasuredTaskRepricing() {}
    /** Different components of one operation can be combined ONLY if the server's contract declares them. */
    public record Operation(String namespace,String externalId) {
        public Operation { required(namespace);required(externalId); }
    }
    public sealed interface Rate permits TokenRate,UnitRate,ExplicitZero {}
    public record TokenRate(String normalizationBasis,ModelTokenPrice reference) implements Rate {
        public TokenRate { required(normalizationBasis);Objects.requireNonNull(reference); }
    }
    public record UnitRate(TaskQuoteCalculator.ToolKey unit,TaskQuoteCalculator.ToolPrice reference) implements Rate {
        public UnitRate { Objects.requireNonNull(unit);Objects.requireNonNull(reference); }
    }
    public record ExplicitZero(String contractVersion) implements Rate { public ExplicitZero { required(contractVersion); } }
    public sealed interface Quantity permits Tokens,Units,Zero,Missing {}
    public record Tokens(String normalizationBasis,Map<ModelTokenPrice.Category,Long> disjoint) implements Quantity {
        public Tokens { required(normalizationBasis);disjoint=Map.copyOf(disjoint);if(disjoint.values().stream().anyMatch(n->n<0))throw new IllegalArgumentException("Negative counter"); }
    }
    public record Units(TaskQuoteCalculator.ToolKey unit,BigDecimal count) implements Quantity {
        public Units { Objects.requireNonNull(unit);CnyAmounts.nonNegative(count);count=count.stripTrailingZeros(); }
    }
    public enum Zero implements Quantity { EXPLICIT_ZERO }
    public enum Missing implements Quantity { MISSING }
    public record Fact(Operation operation,String kind,String component,String evidenceSha,Quantity quantity) {
        public Fact { Objects.requireNonNull(operation);required(kind);required(component);required(evidenceSha);Objects.requireNonNull(quantity); }
    }
    /** Immutable per-compatibility-group current reference book; never derives tool units from deliverables. */
    public record Reference(String version,Map<String,Map<String,Rate>> byKind) {
        public Reference { required(version);var copy=new HashMap<String,Map<String,Rate>>();byKind.forEach((k,r)->copy.put(k,Map.copyOf(r)));byKind=Map.copyOf(copy); }
    }
    public record Line(Fact fact,Rate price,BigDecimal yuan) {}
    public record Result(String referenceVersion,List<Line> lines,BigDecimal yuan) {public Result{lines=List.copyOf(lines);} }
    public static final class Unavailable extends RuntimeException { Unavailable(String reason){super(reason);} }
    private static void required(String s){if(s==null||s.isBlank())throw new IllegalArgumentException("Missing pricing identity");}
    public static Result reprice(Reference reference,List<Fact> facts){
        if(facts.isEmpty())throw new Unavailable("OPERATION_EVIDENCE_MISSING");
        var operations=new TreeMap<Operation,Map<String,Fact>>(Comparator.comparing(Operation::namespace).thenComparing(Operation::externalId));var kinds=new HashMap<Operation,String>();
        for(var fact:facts){String kind=kinds.putIfAbsent(fact.operation(),fact.kind());
            if(kind!=null&&!kind.equals(fact.kind()))throw new Unavailable("OPERATION_CONFLICT");
            var components=operations.computeIfAbsent(fact.operation(),k->new TreeMap<>());
            var previous=components.putIfAbsent(fact.component(),fact);
            if(previous!=null&&!previous.equals(fact))throw new Unavailable("CHARGE_COMPONENT_CONFLICT");
        }
        var lines=new ArrayList<Line>();var total=BigDecimal.ZERO;
        for(var entry:operations.entrySet()){
            var contract=reference.byKind().get(kinds.get(entry.getKey()));var components=entry.getValue();
            if(contract==null||!contract.keySet().equals(components.keySet()))throw new Unavailable("CHARGE_COMPONENT_MISMATCH");
            for(var fact:components.values()){
                var rate=contract.get(fact.component());BigDecimal amount;
                if(fact.quantity() instanceof Missing)throw new Unavailable("OPERATION_QUANTITY_MISSING");
                if(rate instanceof TokenRate r && fact.quantity() instanceof Tokens q && r.normalizationBasis().equals(q.normalizationBasis())){
                    try{amount=r.reference().cost(q.disjoint());}catch(ModelTokenPrice.MissingPrice e){throw new Unavailable("TOKEN_PRICE_MISSING");}
                }else if(rate instanceof UnitRate r && fact.quantity() instanceof Units q && r.unit().equals(q.unit()))amount=q.count().multiply(r.reference().yuanPerUnit());
                else if(rate instanceof ExplicitZero && fact.quantity()==Zero.EXPLICIT_ZERO)amount=BigDecimal.ZERO;
                else throw new Unavailable("BILLING_BASIS_MISMATCH");
                total=total.add(amount);lines.add(new Line(fact,rate,amount));
            }
        }
        return new Result(reference.version(),lines,total);
    }
}
