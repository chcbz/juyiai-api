package cn.jia.agent.service.usage;

import cn.jia.economy.usage.UsageCaptureJournal.*;
import tools.jackson.databind.ObjectMapper;
import java.math.BigInteger;
import java.util.List;
import java.util.function.Function;

/** Inductive, capture-scoped proof. A new node can point only to already associated nodes.
 * Lookup count is independent of depth. A missing dependency (including an unrooted cycle)
 * never grants authority to a response. The closing projection diagnoses incomplete graphs. */
public final class NativeSourceLineage {
    private static final ObjectMapper JSON=new ObjectMapper();
    private NativeSourceLineage(){}
    public static Link resolve(String source,Function<String,LinkedEvidence> first){
        var row=JSON.readTree(source);var o=row.get("observation");String cap=row.get("captureId").asText(),self=row.get("recordId").asText();
        var plan=first.apply(NativeCaptureEvidence.digest(List.of("USAGE_RECORD_V2",cap,"PLAN",List.of())));
        if(plan==null||!plan.associated())return Link.WAITING;
        String id=o.get("sourceId").asText(),epoch=o.get("epoch").asText();boolean root=id.equals(JSON.readTree(plan.canonical()).get("observation").get("rootSourceId").asText());
        var parent=o.get("parentSourceRecordId");var prev=o.get("previousEpochSourceRecordId");
        if(root!=parent.isNull()||epoch.equals("0")!=prev.isNull())return Link.CONFLICT;
        boolean missing=false;
        for(String edge:List.of("parentSourceRecordId","previousEpochSourceRecordId")){
            var ref=o.get(edge);if(ref.isNull())continue;
            if(self.equals(ref.asText()))return Link.CONFLICT;
            var dep=first.apply(ref.asText());if(dep==null){missing=true;continue;}
            var target=JSON.readTree(dep.canonical());if(!"SOURCE".equals(target.get("kind").asText()))return Link.CONFLICT;
            var facts=target.get("observation");boolean same=id.equals(facts.get("sourceId").asText());
            if(edge.equals("previousEpochSourceRecordId")){
                if(!same||new BigInteger(facts.get("epoch").asText()).compareTo(new BigInteger(epoch))>=0)return Link.CONFLICT;
            }else if(same)return Link.CONFLICT;
            if(!dep.associated())missing=true;
        }
        return missing?Link.WAITING:Link.ASSOCIATED;
    }
}
