package cn.jia.agent.security;

import cn.jia.agent.skill.quote.CostProfile;
import cn.jia.agent.skill.quote.CostProfileReport;
import cn.jia.core.util.CanonicalValueEncoding;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.*;

/** Private runtime-registration codec. No inferred skill capability, installation permission or quote authority. */
public final class CostProfileReportJson {
    public static final int MAX_BYTES=1048576;
    private static final JsonMapper JSON=JsonMapper.builder(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(12).maxStringLength(4096).maxNumberLength(20).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private CostProfileReportJson() { }
    public static CostProfileReport read(String raw) {
        try {
            if(raw==null || raw.length()>MAX_BYTES || CanonicalValueEncoding.utf8(raw).length>MAX_BYTES)throw invalid();
            var root=JSON.readTree(raw);keys(root,"schema","sequence","sha256","profiles");
            if(!CostProfile.SCHEMA.equals(text(root,"schema")))throw invalid();
            var profiles=new ArrayList<CostProfile>();
            for(var node:array(root.get("profiles"),CostProfile.MAX_PROFILES))profiles.add(profile(node));
            var report=new CostProfileReport(number(root,"sequence"),profiles);
            if(!report.sha256().equals(text(root,"sha256")))throw invalid();
            return report;
        } catch(Exception failure) {throw invalid();}
    }
    public static String write(CostProfileReport report) {
        try {
            String raw=JSON.writeValueAsString(report.wire());
            if(!report.equals(read(raw)))throw invalid();return raw;
        } catch(Exception failure) {throw invalid();}
    }
    /** Map-based callers have already passed the common transport duplicate-key boundary. */
    public static CostProfileReport readWire(Object wire) {
        try{return read(JSON.writeValueAsString(wire));}catch(Exception failure){throw invalid();}
    }
    private static CostProfile profile(JsonNode node) {
        keys(node,"profileId","revision","selectionPriority","scopePattern","executionStrategyVersion","contextPolicyId",
                "contextPolicyVersion","toolPolicyVersion","capturePolicyVersion","routes","configSha");
        var slots=new ArrayList<CostProfile.Slot>();
        for(var slot:array(node.get("scopePattern"),CostProfile.MAX_SLOTS)) {
            keys(slot,"slot","capabilityId","capabilityVersion","specSha","dependsOn","skillKey","skillVersion","packageSha","installId");
            var dependencies=new ArrayList<Integer>();
            for(var dependency:array(slot.get("dependsOn"),CostProfile.MAX_SLOTS))dependencies.add(integer(dependency));
            slots.add(new CostProfile.Slot(integer(slot.get("slot")),text(slot,"capabilityId"),text(slot,"capabilityVersion"),text(slot,"specSha"),dependencies,
                    text(slot,"skillKey"),text(slot,"skillVersion"),text(slot,"packageSha"),text(slot,"installId")));
        }
        var routes=new ArrayList<CostProfile.Route>();
        for(var route:array(node.get("routes"),CostProfile.MAX_ROUTES)) {
            keys(route,"kind","component","channel","modelOrTool","tier","normalizationBasis");
            routes.add(new CostProfile.Route(text(route,"kind"),text(route,"component"),text(route,"channel"),text(route,"modelOrTool"),text(route,"tier"),text(route,"normalizationBasis")));
        }
        var result=new CostProfile(text(node,"profileId"),number(node,"revision"),integer(node.get("selectionPriority")),slots,
                text(node,"executionStrategyVersion"),text(node,"contextPolicyId"),text(node,"contextPolicyVersion"),text(node,"toolPolicyVersion"),text(node,"capturePolicyVersion"),routes);
        if(!result.configSha().equals(text(node,"configSha")))throw invalid();return result;
    }
    private static long number(JsonNode node,String key) {return decimal(text(node,key));}
    private static long decimal(String value) {if(!value.matches("0|[1-9][0-9]{0,18}"))throw invalid();return Long.parseLong(value);}
    private static int integer(JsonNode value) {if(value==null || !value.isTextual())throw invalid();return Math.toIntExact(decimal(value.textValue()));}
    private static String text(JsonNode node,String key) {var value=node.get(key);if(value==null || !value.isTextual())throw invalid();String text=value.textValue();CanonicalValueEncoding.utf8(text);return text;}
    private static List<JsonNode> array(JsonNode node,int max) {if(node==null || !node.isArray() || node.size()>max)throw invalid();var result=new ArrayList<JsonNode>();node.forEach(result::add);return result;}
    private static void keys(JsonNode node,String... expected) {if(node==null || !node.isObject())throw invalid();var actual=new HashSet<String>();node.fieldNames().forEachRemaining(actual::add);if(!actual.equals(Set.of(expected)))throw invalid();}
    private static IllegalArgumentException invalid() {return new IllegalArgumentException("Invalid runtime cost profile declaration");}
}
