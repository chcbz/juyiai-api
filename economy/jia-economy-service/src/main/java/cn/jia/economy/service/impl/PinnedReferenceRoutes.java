package cn.jia.economy.service.impl;

import cn.jia.economy.pricing.CostPricingRoute;
import cn.jia.economy.pricing.CostPricingRoute.TokenRoute;
import cn.jia.economy.task.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;

/** Startup-only private mapping. No HTTP writer, prices, model calls, tenant fallbacks or generation inferred from a heartbeat. */
@Service
public final class PinnedReferenceRoutes implements PreparedReferenceRoutes {
    private record Key(String tenant,String client,String owner,Configuration configuration) {}
    private record Image(String operation,String channel,String model,String tier) {}
    private record Entry(Key key,String channel,String model,String tier,Image image) {}
    private record Manifest(String schema,List<Entry> entries) {}
    private final Map<Key,TaskBudgetEstimator.Terms> index;
    public PinnedReferenceRoutes(@Value("${jia.economy.task-budget.reference-routes-file:}") String file,
                                 @Value("${jia.economy.task-budget.reference-routes-sha256:}") String pin){
        if(file.isEmpty()&&pin.isEmpty()){index=Map.of();return;}
        Map<Key,TaskBudgetEstimator.Terms> loaded=new HashMap<>();
        try {
            var bytes=Files.readAllBytes(Path.of(file));if(!sha(bytes).equals(pin))throw new IllegalArgumentException();
            var json=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
            var manifest=json.readValue(bytes,Manifest.class);
            boolean composite="prepared-reference-routes-v2".equals(manifest.schema());
            if(!composite&&!"prepared-reference-routes-v1".equals(manifest.schema()))throw new IllegalArgumentException();
            for(var entry:manifest.entries()){
                var key=entry.key();var c=key.configuration();
                boolean image="NATIVE_PINNED_IMAGE_V1".equals(c.capturePolicy());
                if(!"NATIVE_DISPATCH_GROUP_V1".equals(c.version()) || (image ? !composite || entry.image()==null : !"NATIVE_LOCAL_ARTIFACT_TEXT_V1".equals(c.capturePolicy()) || entry.image()!=null))throw new IllegalArgumentException();
                requireIds(key.tenant(),key.client(),key.owner(),entry.channel(),entry.model(),entry.tier());
                var parent=new TokenRoute(entry.channel(),entry.model(),entry.tier(),"RESPONSES_TEXT_TOKENS_V1");
                Map<String,Map<String,CostPricingRoute>> routes=new HashMap<>();routes.put("MODEL_RESPONSE",Map.of("INFERENCE",parent));
                var parentIdentity=List.of("native-reference-route-v1","MODEL_RESPONSE","INFERENCE",parent.channel(),parent.model(),parent.tier(),parent.normalizationBasis());
                // Preserve text group identity. A different child operation or tariff key requires a new group,
                // but changing numerical prices alone does not. Presence of a route never proves usage.
                Object identity=parentIdentity;
                if(image){
                    var child=entry.image();requireIds(child.channel(),child.model(),child.tier());
                    if(!Set.of("generate","edit").contains(child.operation()))throw new IllegalArgumentException();
                    var route=new TokenRoute(child.channel(),child.model(),child.tier(),"openai-images-modalities-v1");
                    routes.put("NATIVE_PROVIDER_CALL",Map.of(child.operation(),route));
                    identity=List.of("native-reference-route-v2",parentIdentity,"NATIVE_PROVIDER_CALL",child.operation(),route.channel(),route.model(),route.tier(),route.normalizationBasis());
                }
                var version=sha(json.writeValueAsBytes(identity));
                var group=new TaskBillingService.MeasuredGroup(c.version(),c.executionSha(),c.localRouteSha(),version,c.toolSha(),c.capturePolicy());
                var terms=new TaskBudgetEstimator.Terms(group,routes);
                if(loaded.putIfAbsent(key,terms)!=null)throw new IllegalArgumentException();
            }
        } catch(Exception invalid){loaded.clear();org.slf4j.LoggerFactory.getLogger(PinnedReferenceRoutes.class).error("Invalid private reference-route manifest; task reference routes disabled");}
        index=Map.copyOf(loaded);
    }
    private static void requireIds(String... ids){for(String id:ids)if(id==null||id.isBlank()||!id.equals(id.strip()))throw new IllegalArgumentException();}
    private static String sha(byte[] bytes)throws java.security.NoSuchAlgorithmException{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    @Override public TaskBudgetEstimator.Terms resolve(TaskBillingService.Task task,Configuration c){
        return index.get(new Key(task.scope().tenantId(),task.scope().clientId(),task.ownerJiacn(),c));
    }
}
