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
    /** Advance a revision head, not the coverage projection. FINAL digest/count checks span multiple records. */
    public static Link metadata(String evidence,String previous,Function<String,LinkedEvidence> first){
        var row=JSON.readTree(evidence);String kind=row.get("kind").asText(),cap=row.get("captureId").asText();
        if(kind.equals("PLAN"))return Link.ASSOCIATED;
        if(kind.equals("SOURCE"))return resolve(evidence,first);
        if(kind.equals("CAPTURE_FINAL"))return associated(first,cap,"PLAN",List.of());
        var o=row.get("observation");String source=o.get("sourceId").asText(),epoch=o.get("epoch").asText();
        var owner=associated(first,cap,"SOURCE",List.of(source,epoch));
        if(!kind.equals("REQUEST"))return kind.equals("SOURCE_FINAL")?owner:Link.CONFLICT;
        String resolution=o.get("resolution").asText();
        if(previous!=null){
            var old=JSON.readTree(previous).get("observation");String before=old.get("resolution").asText();
            if(!before.equals("PENDING")&&resolution.equals("PENDING"))return Link.CONFLICT;
            if(before.equals("RESPONSE")&&(!resolution.equals(before)||!old.get("sourceAttemptId").equals(o.get("sourceAttemptId"))))return Link.CONFLICT;
        }
        if(owner!=Link.ASSOCIATED||!resolution.equals("RESPONSE"))return owner;
        var response=first.apply(recordId(cap,"RESPONSE",List.of(o.get("sourceAttemptId").asText())));
        if(response==null)return Link.WAITING;
        var origin=JSON.readTree(response.canonical()).get("observation");
        if(!source.equals(origin.get("sourceId").asText())||!epoch.equals(origin.get("epoch").asText()))return Link.CONFLICT;
        return response.associated()?Link.ASSOCIATED:Link.WAITING;
    }
    private static String recordId(String cap,String kind,List<String> identity){
        return NativeCaptureEvidence.digest(List.of("USAGE_RECORD_V2",cap,kind,identity));
    }
    private static Link associated(Function<String,LinkedEvidence> first,String cap,String kind,List<String> identity){
        var item=first.apply(recordId(cap,kind,identity));return item!=null&&item.associated()?Link.ASSOCIATED:Link.WAITING;
    }
    public static Link resolve(String source,Function<String,LinkedEvidence> first){
        var row=JSON.readTree(source);var o=row.get("observation");String cap=row.get("captureId").asText(),self=row.get("recordId").asText();
        var plan=first.apply(recordId(cap,"PLAN",List.of()));
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
