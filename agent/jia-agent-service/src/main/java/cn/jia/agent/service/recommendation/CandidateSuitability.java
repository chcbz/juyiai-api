package cn.jia.agent.service.recommendation;

import cn.jia.agent.entity.AssigneeRecommendation.Evidence;
import cn.jia.agent.service.TaskFeatureEncoder.Identity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Loads an operator-pinned offline evaluation artifact once. No training, ES, price or model I/O on a request. */
@Component
final class CandidateSuitability {
    static final String VERSION="capability-level-v1";
    record Context(boolean truncated, Set<String> requirements, boolean materials, int candidates) {}
    record Floors(Double lexical,Double vector) {
        boolean valid(boolean l,boolean v){return l==(lexical!=null)&&v==(vector!=null)
            &&(!l||Double.isFinite(lexical)&&lexical>=0)&&(!v||Double.isFinite(vector)&&vector>=0&&vector<=2);}
        boolean meets(CandidateRankFusion.Ranked row){return (lexical==null||row.lexical().score()>=lexical)&&(vector==null||row.vector().score()>=vector);}
    }
    record Cell(String mode,String support,Boolean requirements,Boolean materials,int minCandidates,int maxCandidates,Floors medium,Floors high){
        boolean valid(){boolean l=!"VECTOR".equals(support),v=!"LEXICAL".equals(support);
            return ("HYBRID".equals(mode)||"LEXICAL_ONLY".equals(mode)&&!v)&&Set.of("LEXICAL","VECTOR","BOTH_SAME","BOTH_DISTINCT").contains(support)
                &&requirements!=null&&materials!=null&&minCandidates>=1&&maxCandidates>=minCandidates&&(medium!=null||high!=null)
                &&(medium==null||medium.valid(l,v))&&(high==null||high.valid(l,v))
                &&(medium==null||high==null||(!l||high.lexical>=medium.lexical)&&(!v||high.vector>=medium.vector));}
    }
    record Artifact(String schema,String ranking,String queryTemplate,String evidenceTemplate,String partition,Identity embedding,
                    String calibrationSha256,String holdoutSha256,String evaluationSha256,List<Cell> cells){}
    private final Artifact artifact;private final String ranking;
    private static boolean digest(String s){return s!=null&&s.matches("[a-f0-9]{64}");}
    CandidateSuitability(@Value("${jia.agent.recommendation.calibration-file:}") String file,
                         @Value("${jia.agent.recommendation.calibration-sha256:}") String pin){
        Artifact a=null;String fingerprint=CandidateRankFusion.VERSION;
        if(!file.isEmpty()||!pin.isEmpty())try{
            var bytes=Files.readAllBytes(Path.of(file));if(!digest(pin)||!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(pin))throw new IllegalArgumentException();
            var x=new ObjectMapper().readValue(bytes,Artifact.class);
            if(!VERSION.equals(x.schema)||!CandidateRankFusion.VERSION.equals(x.ranking)||!TaskRetrievalText.VERSION.equals(x.queryTemplate)
                ||!CapabilityDocuments.TEMPLATE.equals(x.evidenceTemplate)||!digest(x.partition)||x.embedding==null||x.embedding.providerConfigVersion()==null||x.embedding.model()==null||x.embedding.dimensions()<1
                ||!digest(x.calibrationSha256)||!digest(x.holdoutSha256)||x.calibrationSha256.equals(x.holdoutSha256)||!digest(x.evaluationSha256)||x.cells.isEmpty()
                ||x.cells.stream().anyMatch(c->!c.valid()))throw new IllegalArgumentException();
            for(int i=0;i<x.cells.size();i++){var c=x.cells.get(i);for(var d:x.cells.subList(0,i))if(c.mode.equals(d.mode)&&c.support.equals(d.support)&&c.requirements.equals(d.requirements)&&c.materials.equals(d.materials)&&c.minCandidates<=d.maxCandidates&&d.minCandidates<=c.maxCandidates)throw new IllegalArgumentException();}
            // Re-evaluation of identical frozen rules must not change their identity (no circular report hash).
            var mapper=new ObjectMapper();var rules=mapper.readValue(bytes,new tools.jackson.core.type.TypeReference<Map<String,Object>>(){});
            rules.remove("evaluationSha256");rules.remove("holdoutSha256");
            String canonical=cn.jia.agent.service.SkillCanonicalJson.write(rules);
            fingerprint=CandidateRankFusion.VERSION+":"+VERSION+":"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            a=x;
        }catch(Exception invalid){org.slf4j.LoggerFactory.getLogger(CandidateSuitability.class).warn("Recommendation calibration unavailable; levels remain UNKNOWN");}
        artifact=a;ranking=fingerprint;
    }
    String rankingVersion(){return ranking;}
    String level(String mode,CandidateRankFusion.Ranked row,Context context,Collection<Evidence> citations,CapabilityDocuments.Doc doc){
        if(artifact==null||doc==null||citations.isEmpty()||context.truncated||!artifact.partition.equals(doc.partition())||!artifact.embedding.equals(doc.embedding()))return "UNKNOWN";
        // A match on just one of several explicit requirements is not positive evidence for the entire task.
        if(!context.requirements.isEmpty()&&!citations.stream().map(Evidence::label).map(TaskRetrievalText::normalized).collect(java.util.stream.Collectors.toSet()).containsAll(context.requirements))return "UNKNOWN";
        var l=row.lexical();var v=row.vector();if(l==null&&v==null)return "UNKNOWN";
        String support=l==null?"VECTOR":v==null?"LEXICAL":l.evidenceKey().equals(v.evidenceKey())?"BOTH_SAME":"BOTH_DISTINCT";
        for(var c:artifact.cells)if(c.mode.equals(mode)&&c.support.equals(support)&&c.requirements==!context.requirements.isEmpty()&&c.materials==context.materials&&c.minCandidates<=context.candidates&&context.candidates<=c.maxCandidates){
            if(c.high!=null&&c.high.meets(row))return "HIGH";if(c.medium!=null&&c.medium.meets(row))return "MEDIUM";}
        return "UNKNOWN"; // Low similarity never proves inability.
    }
}
