package cn.jia.agent.service.recommendation;

import cn.jia.agent.entity.AssigneeRecommendation.Evidence;
import cn.jia.agent.service.TaskFeatureEncoder.Identity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;
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
    record Artifact(String schema,String ranking,String queryTemplate,String evidenceTemplate,String physicalIndex,String partition,Identity embedding,
                    String calibrationSha256,String holdoutSha256,String evaluationSha256,List<Cell> cells){}
    private record Key(String mode,String support,boolean explicit,boolean materials){}
    private final Map<Key,NavigableMap<Integer,Cell>> lookup;
    private final Artifact artifact;private final String ranking;
    private static boolean digest(String s){return s!=null&&s.matches("[a-f0-9]{64}");}
    CandidateSuitability(@Value("${jia.agent.recommendation.calibration-file:}") String file,
                         @Value("${jia.agent.recommendation.calibration-sha256:}") String pin){
        Map<Key,NavigableMap<Integer,Cell>> groups=new HashMap<>();Artifact a=null;String fingerprint=CandidateRankFusion.VERSION;
        if(!file.isEmpty()||!pin.isEmpty())try{
            var bytes=Files.readAllBytes(Path.of(file));if(!digest(pin)||!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(pin))throw new IllegalArgumentException();
            var mapper=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,DeserializationFeature.FAIL_ON_TRAILING_TOKENS,DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
            var x=mapper.readValue(bytes,Artifact.class);
            if(!VERSION.equals(x.schema)||!CandidateRankFusion.VERSION.equals(x.ranking)||!TaskRetrievalText.VERSION.equals(x.queryTemplate)
                ||!CapabilityDocuments.TEMPLATE.equals(x.evidenceTemplate)||!CapabilityIndexWriter.GENERATION.equals(x.physicalIndex)||!digest(x.partition)||x.embedding==null||x.embedding.providerConfigVersion()==null||x.embedding.model()==null||x.embedding.dimensions()<1
                ||!digest(x.calibrationSha256)||!digest(x.holdoutSha256)||x.calibrationSha256.equals(x.holdoutSha256)||!digest(x.evaluationSha256)||x.cells.isEmpty()
                ||x.cells.stream().anyMatch(c->!c.valid()))throw new IllegalArgumentException();
            for(var c:x.cells){var ranges=groups.computeIfAbsent(new Key(c.mode,c.support,c.requirements,c.materials),unused->new TreeMap<>());
                var prior=ranges.floorEntry(c.maxCandidates);if(prior!=null&&prior.getValue().maxCandidates>=c.minCandidates)throw new IllegalArgumentException();
                ranges.put(c.minCandidates,c);}
            // Re-evaluation of identical frozen rules must not change their identity (no circular report hash).
            var rules=mapper.readValue(bytes,new tools.jackson.core.type.TypeReference<Map<String,Object>>(){});
            rules.remove("evaluationSha256");rules.remove("holdoutSha256");
            String canonical=cn.jia.agent.service.SkillCanonicalJson.write(rules);
            fingerprint=CandidateRankFusion.VERSION+":"+VERSION+":"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            a=x;
        }catch(Exception invalid){org.slf4j.LoggerFactory.getLogger(CandidateSuitability.class).warn("Recommendation calibration unavailable; levels remain UNKNOWN");}
        artifact=a;ranking=fingerprint;lookup=a==null?Map.of():Map.copyOf(groups);
    }
    String rankingVersion(){return ranking;}
    String level(String mode,CandidateRankFusion.Ranked row,Context context,Collection<Evidence> citations,CapabilityDocuments.Doc doc){
        if(artifact==null||doc==null||citations.isEmpty()||context.truncated||!artifact.partition.equals(doc.partition())||!artifact.embedding.equals(doc.embedding()))return "UNKNOWN";
        // A match on just one of several explicit requirements is not positive evidence for the entire task.
        if(!context.requirements.isEmpty()&&!citations.stream().map(Evidence::label).map(TaskRetrievalText::normalized).collect(java.util.stream.Collectors.toSet()).containsAll(context.requirements))return "UNKNOWN";
        var l=row.lexical();var v=row.vector();if(l==null&&v==null||l!=null&&!artifact.physicalIndex.equals(l.physicalIndex())||v!=null&&!artifact.physicalIndex.equals(v.physicalIndex()))return "UNKNOWN";
        String support=l==null?"VECTOR":v==null?"LEXICAL":l.evidenceKey().equals(v.evidenceKey())?"BOTH_SAME":"BOTH_DISTINCT";
        var ranges=lookup.get(new Key(mode,support,!context.requirements.isEmpty(),context.materials));
        var rowAt=ranges==null?null:ranges.floorEntry(context.candidates);
        if(rowAt!=null){var c=rowAt.getValue();if(context.candidates<=c.maxCandidates){
            if(c.high!=null&&c.high.meets(row))return "HIGH";if(c.medium!=null&&c.medium.meets(row))return "MEDIUM";}}
        return "UNKNOWN"; // Low similarity never proves inability.
    }
}
