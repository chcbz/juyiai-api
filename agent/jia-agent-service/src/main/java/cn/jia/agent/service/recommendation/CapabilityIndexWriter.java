package cn.jia.agent.service.recommendation;

import cn.jia.agent.service.TaskFeatureEncoder.Vector;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.OpType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Background repair writes to the fixed physical generation, not a re-resolved alias.
 * No automatic index creation, refresh, or mutation of chat-memory data. */
@Component
final class CapabilityIndexWriter {
    static final String GENERATION = "agent_capability_evidence_v1";
    private final ObjectProvider<ElasticsearchClient> clients;
    CapabilityIndexWriter(ObjectProvider<ElasticsearchClient> clients) { this.clients = clients; }
    private static Map<String,Object> source(CapabilityDocuments.Doc d) {
        return Map.of("partition",d.partition(),"agent_id",d.agentId(),"evidence_key",d.evidenceKey(),
                "fact_digest",d.factDigest(),"text",d.text(),"text_sha256",d.textSha256(),
                "provider_version",d.embedding().providerConfigVersion(),"model",d.embedding().model(),
                "dimensions",d.embedding().dimensions(),"template_version",d.templateVersion());
    }
    void publishText(CapabilityDocuments.Doc doc) throws IOException {
        // require_alias protects against ES auto-creating an unmapped physical index if provisioning was skipped.
        // The generation-specific write alias is provisioned once and must not be retargeted.
        try {
            clients.getObject().index(b -> b.index(GENERATION+"_write").requireAlias(true).id(doc.id())
                    .opType(OpType.Create).document(source(doc)));
        } catch (ElasticsearchException e) {
            if (e.status() != 409 || !"version_conflict_engine_exception".equals(e.error().type())) throw e;
        }
    }
    boolean encoded(CapabilityDocuments.Doc doc) throws IOException {
        // Dense vectors may be excluded from ES _source. Atomically store a small completion marker with the vector.
        var r=clients.getObject().get(g->g.index(GENERATION+"_write").id(doc.id()).sourceIncludes("encoded"),Map.class);
        return r.found()&&r.source()!=null&&Boolean.TRUE.equals(r.source().get("encoded"));
    }
    void publishVector(CapabilityDocuments.Doc doc, Vector result) throws IOException {
        if (!doc.embedding().equals(result.identity())) throw new IllegalArgumentException("wrong capability encoding generation");
        var source = new LinkedHashMap<>(source(doc)); source.put("vector",result.values()); source.put("encoded",true);
        clients.getObject().index(b -> b.index(GENERATION+"_write").requireAlias(true).id(doc.id()).document(source));
    }
}
