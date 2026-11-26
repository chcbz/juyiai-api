package cn.jia.agent.service.recommendation;

import cn.jia.agent.entity.AgentTaskDTO;
import cn.jia.agent.hosting.HostingRentHttp.Actor;
import cn.jia.agent.service.AgentService;
import cn.jia.agent.service.AgentTaskRootObservation;
import cn.jia.agent.service.TaskFeatureEncoder;
import cn.jia.agent.skill.SkillManagementService;
import org.springframework.stereotype.Service;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.*;
import static cn.jia.agent.entity.AssigneeRecommendation.*;

/** Task recommendation reads facts, retrieves evidence outside the task lock and rechecks before return.
 * Never assigns the task, generates a judgement, quotes a price or reads a wallet. */
@Service
public final class AssigneeRecommendationService {
    private final AgentService agents;
    private final SkillManagementService skills;
    private final RecommendationMaterials materials;
    private final TaskQueryVectors vectors;
    private final CapabilitySearch search;
    private final AgentTaskRootObservation transactions;
    private final ObservationRegistry observations;
    AssigneeRecommendationService(AgentService agents, SkillManagementService skills, RecommendationMaterials materials,
                                 TaskQueryVectors vectors, CapabilitySearch search, AgentTaskRootObservation transactions) {
        this(agents,skills,materials,vectors,search,transactions,Optional.empty());
    }
    @org.springframework.beans.factory.annotation.Autowired
    AssigneeRecommendationService(AgentService agents, SkillManagementService skills, RecommendationMaterials materials,
                                 TaskQueryVectors vectors, CapabilitySearch search, AgentTaskRootObservation transactions, Optional<ObservationRegistry> registry) {
        this.observations=registry.orElse(ObservationRegistry.NOOP);
        this.agents=agents; this.skills=skills; this.materials=materials; this.vectors=vectors;
        this.search=search; this.transactions=transactions;
    }
    public Result recommend(Actor actor, String taskId, Request request) {
        if(request==null || request.expectedTaskVersion()==null || request.expectedTaskVersion().isBlank()) throw new Failure(400,"BAD_REQUEST");
        var task = transactions.observe(actor.tenantId(),actor.clientId(),actor.ownerJiacn(),taskId,
                root->{
                    // Recommendation is a read, not admission. Keep owner/version checks while
                    // Assignment and quote issuance enforce their own authorization and data requirements.
                    return task(agents.getTaskRecommendationInputWithinLockedTask(taskId,root),request.expectedTaskVersion());
                });
        var observed = observe(actor, taskId,"initial");
        var inputs = observed.isEmpty() ? List.<RecommendationMaterials.Observation>of() : materials.observe(actor,taskId);
        var time = System.currentTimeMillis();
        CapabilitySearch.Result found;
        var docs=observed.values().stream().flatMap(c->c.citations().values().stream()).map(CapabilityDocuments.Citation::doc).toList();
        if(docs.isEmpty()) {
            found=new CapabilitySearch.Result("NOT_NEEDED",CandidateRankFusion.measured(observations,observed.keySet(),List.of(),List.of()),List.of());
        }
        else {
            var text = TaskRetrievalText.from(task,inputs);
            found=search.retrieve(observed.keySet(),docs,text.text(),()->{
                try { return new CapabilitySearch.QueryVector(vectors.encode(actor,text).block(),"QUERY_VECTOR_UNAVAILABLE"); }
                catch(TaskFeatureEncoder.Unavailable e) { return new CapabilitySearch.QueryVector(null,"QUERY_VECTOR_UNAVAILABLE"); }
                catch(TaskFeatureEncoder.InvalidResult e) { return new CapabilitySearch.QueryVector(null,"QUERY_VECTOR_INVALID"); }
            });
        }
        var verified=transactions.observe(actor.tenantId(),actor.clientId(),actor.ownerJiacn(),taskId,root->{
            var current=task(agents.getTaskRecommendationInputWithinLockedTask(taskId,root),request.expectedTaskVersion());
            if(!Objects.equals(task.getTitle(),current.getTitle()) || !Objects.equals(task.getDescription(),current.getDescription())
                    || !Objects.equals(task.getRequiredAbilities(),current.getRequiredAbilities())) throw stale();
            var after=observe(actor,taskId,"recheck");
            if(!observed.keySet().equals(after.keySet())) throw stale();
            for(var id:observed.keySet()) if(!observed.get(id).sameFacts(after.get(id))) throw stale();
            if(!after.isEmpty()) materials.verifyUnchanged(actor,taskId,inputs);
            return after;
        });
        // Use only the final authorized observation; formatting and ranking do not need the task lock.
        return project(taskId,request.expectedTaskVersion(),time,found,verified);
    }
    private static AgentTaskDTO task(AgentTaskDTO task,String version) {
        if(!version.equals(task.getTaskVersion()) || !"open".equalsIgnoreCase(task.getStatus())) throw stale();
        return task;
    }
    private static Failure stale(){return new Failure(409,"RECOMMENDATION_STALE");}
    private record CandidateFacts(Agent agent,String provider, Map<String,CapabilityDocuments.Citation> citations) {
        boolean sameFacts(CandidateFacts now) {
            return agent.boundToMe()==now.agent.boundToMe() && Objects.equals(provider,now.provider)
                    && citations.size()==now.citations.size() && citations.entrySet().stream().allMatch(e -> {
                        var current=now.citations.get(e.getKey());
                        return current!=null && current.doc().equals(e.getValue().doc());
                    });
        }
    }
    private Map<String,CandidateFacts> observe(Actor actor,String taskId,String phase) {
        var span=Observation.createNotStarted("cyf.task.recommendation.candidate.read",observations)
                .lowCardinalityKeyValue("phase",phase).start();
        String outcome="error";
        try {
            var facts=readCandidates(actor,taskId);
            outcome=facts.isEmpty()?"empty":"ok";
            return facts;
        } finally { span.lowCardinalityKeyValue("outcome",outcome).stop(); }
    }
    private Map<String,CandidateFacts> readCandidates(Actor actor,String taskId) {
        var rows=agents.listTaskAssigneeCandidates(taskId);
        if(rows.isEmpty()) return Map.of();
        var facts=skills.recommendationSkills(actor,rows);
        var generation=vectors.identity();
        var observed=new LinkedHashMap<String,CandidateFacts>();
        for(var row:rows){
            var t=row.target(); var skillRows=facts.get(t.agentId());
            if(skillRows==null) throw new Failure(503,"RECOMMENDATION_UNAVAILABLE");
            var citations=new LinkedHashMap<String,CapabilityDocuments.Citation>();
            for(var c:CapabilityDocuments.observe(actor,row,skillRows,generation)) citations.put(c.doc().evidenceKey(),c);
            var a=new Agent(t.agentId(),t.displayName(),t.availability(),row.abilities(),true,t.isMine(),t.providerDisplayName());
            if(observed.put(t.agentId(),new CandidateFacts(a,row.skillProviderOwnerJiacn(),Map.copyOf(citations)))!=null)
                throw new Failure(503,"RECOMMENDATION_UNAVAILABLE");
        }
        return observed;
    }
    private Result project(String task,String version,long time,CapabilitySearch.Result found,Map<String,CandidateFacts> facts) {
        var rows=new ArrayList<Recommendation>();
        for(var hit:found.rows()) {
            var c=facts.get(hit.agentId()); var evidence=new LinkedHashMap<String,Evidence>();
            for(var h:Arrays.asList(hit.lexical(),hit.vector())) if(h!=null){
                var ref=c.citations.get(h.evidenceKey()); if(ref==null) throw stale();
                boolean ability=ref.displayRef().startsWith("ability:");
                evidence.putIfAbsent(ref.displayRef(),new Evidence(ref.displayRef(),ability?"DECLARED_ABILITY":"INSTALLED_SKILL",
                        ref.doc().text().lines().findFirst().orElseThrow(),ability?"已登记本领，不代表执行准入。":ref.doc().text(),true));
            }
            String reason=evidence.isEmpty()?"未检索到该候选的相关能力依据。":"检索命中以下能力，尚未核实本事项的可执行性。";
            rows.add(new Recommendation(c.agent,reason,List.copyOf(evidence.values()),
                    "online".equalsIgnoreCase(c.agent.status()) ? List.of() : List.of("当前可能需要等待承办人就绪。")));
        }
        String state=switch(found.mode()){case "NOT_NEEDED"->rows.isEmpty()?"NO_CANDIDATES":"NO_MATCH";case "HYBRID"->rows.stream().anyMatch(r -> !r.evidence().isEmpty())?"READY":"NO_MATCH";default->"DEGRADED";};
        return new Result(task,version,time,state,List.copyOf(rows),true,found.mode(),CandidateRankFusion.VERSION,found.reasons());
    }
}
