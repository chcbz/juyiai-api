package cn.jia.agent.service.recommendation;

import cn.jia.agent.entity.AgentTaskAssigneeCandidateDTO;
import cn.jia.agent.hosting.HostingRentHttp.Actor;
import cn.jia.agent.service.TaskFeatureEncoder.Identity;
import cn.jia.agent.skill.SkillManagementService.RecommendationSkill;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;

/** Index only disclosed evidence from candidate discovery and the skill authority, never task text.
 * Public citation numbers belong to the observation and are not part of the ES document. */
final class CapabilityDocuments {
    static final String TEMPLATE = "ability-purpose-v1";
    record Doc(String id, String partition, String agentId, String evidenceKey, String factDigest,
               String text, String textSha256, Identity embedding, String templateVersion) { }
    record Citation(Doc doc, String displayRef) { }

    static List<Citation> observe(Actor actor, AgentTaskAssigneeCandidateDTO candidate,
                                  List<RecommendationSkill> skills, Identity generation) {
        String agent = candidate.target().agentId();
        var docs = new LinkedHashMap<String,Citation>();
        for(var c:declared(actor.tenantId(),actor.clientId(),candidate.skillProviderOwnerJiacn(),agent,candidate.abilities(),generation))docs.put(c.doc().id(),c);
        for(var c:purposes(actor.tenantId(),actor.clientId(),agent,skills,generation))docs.put(c.doc().id(),c);
        return List.copyOf(docs.values());
    }
    static List<Citation> purposes(String tenant,String client,String agent,List<RecommendationSkill> skills,Identity generation){
        var docs=new LinkedHashMap<String,Citation>();String partition=digest("partition-v1",tenant,client);
        for (int i = 0; i < skills.size(); i++) {
            var skill = skills.get(i);
            if (!skill.available()) continue;
            String title = skill.title() == null ? "" : TaskRetrievalText.normalized(skill.title());
            String purpose = skill.description() == null ? "" : TaskRetrievalText.normalized(skill.description());
            String text = (title + "\n" + purpose).strip();
            if (!text.isEmpty()) add(docs, partition, agent, skill.evidenceKey(), skill.factDigest(), text,
                    generation, "public-skill:" + (i + 1));
        }
        return List.copyOf(docs.values());
    }
    static List<Citation> declared(String tenant,String client,String owner,String agent,List<String> abilities,Identity generation){
        String partition=digest("partition-v1",tenant,client);
        var docs=new LinkedHashMap<String,Citation>();
        for (int i = 0; i < abilities.size(); i++) {
            String text = TaskRetrievalText.normalized(abilities.get(i));
            if (text.isEmpty()) continue;
            String source = digest("declared-ability-v1", partition, agent, owner, text);
            add(docs, partition, agent, source, digest("disclosed-ability-v1", source, text), text,
                    generation, "ability:" + i);
        }
        return List.copyOf(docs.values());
    }
    private static void add(LinkedHashMap<String,Citation> docs, String partition, String agent, String key,
                            String facts, String text, Identity generation, String ref) {
        String textSha = sha(text.getBytes(StandardCharsets.UTF_8));
        String id = digest("capability-doc-v1", partition, agent, key, facts, textSha, TEMPLATE,
                generation.providerConfigVersion(), generation.model(), Integer.toString(generation.dimensions()));
        docs.putIfAbsent(id, new Citation(new Doc(id, partition, agent, key, facts, text, textSha, generation, TEMPLATE), ref));
    }
    private static String digest(String domain, String... fields) {
        var md = sha256(); md.update(domain.getBytes(StandardCharsets.US_ASCII)); md.update((byte)0);
        for (String field : fields) {
            byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
            for (int shift = 24; shift >= 0; shift -= 8) md.update((byte)(bytes.length >>> shift));
            md.update(bytes);
        }
        return HexFormat.of().formatHex(md.digest());
    }
    private static String sha(byte[] bytes) { return HexFormat.of().formatHex(sha256().digest(bytes)); }
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private CapabilityDocuments() { }
}
