package cn.jia.economy.service.impl;

import cn.jia.economy.pricing.MeasuredTaskRepricing;
import cn.jia.economy.usage.ConfirmedSampleEvidence;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Versioned private sample payload. Retains only source identity and typed measured quantities, never raw provider bodies or prices. */
record ProjectedTaskOperation(long memberRevision,String captureId,long captureRevision,String recordId,String sourceRevision,
        String evidenceSha256,MeasuredTaskRepricing.Operation operation,String kind,String component,String basis,JsonNode quantity){
    private record UnitWire(cn.jia.economy.pricing.TaskQuoteCalculator.ToolKey unit,String decimalCount){}
    static ProjectedTaskOperation from(ConfirmedSampleEvidence.Operation source,ObjectMapper json){
        var fact=source.fact();String basis=switch(fact.quantity()){
            case MeasuredTaskRepricing.Tokens ignored -> "TOKEN";
            case MeasuredTaskRepricing.Units ignored -> "UNIT";
            case MeasuredTaskRepricing.Zero ignored -> "EXPLICIT_ZERO";
            case MeasuredTaskRepricing.Missing ignored -> throw new IllegalStateException("Cannot publish missing quantities");
        };
        // Keep decimal unit counts exact across a JSON tree round trip; never parse through binary64.
        JsonNode payload=fact.quantity() instanceof MeasuredTaskRepricing.Units units
                ?json.valueToTree(new UnitWire(units.unit(),units.count().toString())):json.valueToTree(fact.quantity());
        return new ProjectedTaskOperation(source.memberRevision(),source.captureId(),source.captureRevision(),source.recordId(),source.sourceRevision(),
                fact.evidenceSha(),fact.operation(),fact.kind(),fact.component(),basis,payload);
    }
    MeasuredTaskRepricing.Fact fact(ObjectMapper json){
        MeasuredTaskRepricing.Quantity q=switch(basis){
            case "TOKEN" -> json.treeToValue(quantity,MeasuredTaskRepricing.Tokens.class);
            case "UNIT" -> {var wire=json.treeToValue(quantity,UnitWire.class);yield new MeasuredTaskRepricing.Units(wire.unit(),new java.math.BigDecimal(wire.decimalCount()));}
            case "EXPLICIT_ZERO" -> json.treeToValue(quantity,MeasuredTaskRepricing.Zero.class);
            default -> throw new IllegalStateException("Unknown projected billing basis");
        };
        return new MeasuredTaskRepricing.Fact(operation,kind,component,evidenceSha256,q);
    }
}
