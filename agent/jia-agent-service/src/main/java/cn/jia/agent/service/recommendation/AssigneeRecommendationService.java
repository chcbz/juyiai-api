package cn.jia.agent.service.recommendation;

import cn.jia.agent.entity.AgentTaskDTO;
import cn.jia.agent.hosting.HostingRentHttp.Actor;
import cn.jia.agent.service.AgentService;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.agent.service.TaskFeatureEncoder;
import cn.jia.agent.skill.SkillManagementService;
import org.springframework.stereotype.Service;
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
    private final AgentTaskMutationTransaction transactions;
    AssigneeRecommendationService(AgentService agents, SkillManagementService skills, RecommendationMaterials materials,
                                 TaskQueryVectors vectors, CapabilitySearch search, AgentTaskMutationTransaction transactions) {
        this.agents=agents; this.skills=skills; this.materials=materials; this.vectors=vectors;
        this.search=search; this.transactions=transactions;
    }
    public Result recommend(Actor actor, String taskId, Request request) {
        if(request==null || request.expectedTaskVersion()==null || request.expectedTaskVersion().isBlank()) throw new Failure(400,"BAD_REQUEST");
        var task = task(taskId, request.expectedTaskVersion());
        var observed = observe(actor, taskId);
        var inputs = observed.isEmpty() ? List.<RecommendationMaterials.Observation>of() : materials.observe(actor,taskId);
        var time = System.currentTimeMillis();
        CapabilitySearch.Result found;
        if(observed.isEmpty()) found = new CapabilitySearch.Result("NOT_NEEDED",List.of(),List.of());
        else {
            var text = TaskRetrievalText.from(task,inputs);
            TaskFeatureEncoder.Vector v=null; String vectorFailure=null;
            try { v=vectors.encode(actor,text).block(); }
            catch(TaskFeatureEncoder.Unavailable e) { vectorFailure="QUERY_VECTOR_UNAVAILABLE"; }
            catch(TaskFeatureEncoder.InvalidResult e) { vectorFailure="QUERY_VECTOR_INVALID"; }
            var docs=observed.values().stream().flatMap(c->c.citations().values().stream()).map(CapabilityDocuments.Citation::doc).toList();
            var retrieval=search.retrieve(observed.keySet(),docs,text.text(),v);
            if(vectorFailure!=null && !retrieval.reasons().contains(vectorFailure)) {
                var reasons=new LinkedHashSet<>(retrieval.reasons()); reasons.remove("QUERY_VECTOR_UNAVAILABLE"); reasons.add(vectorFailure);
                retrieval=new CapabilitySearch.Result(retrieval.mode(),retrieval.rows(),List.copyOf(reasons));
            }
            found=retrieval;
        }
        return transactions.executeWithLockedTaskRootInOwnerScope(actor.tenantId(),actor.clientId(),actor.ownerJiacn(),taskId,root->{
            var current=task(taskId,request.expectedTaskVersion());
            if(!Objects.equals(task.getTitle(),current.getTitle()) || !Objects.equals(task.getDescription(),current.getDescription())
                    || !Objects.equals(task.getRequiredAbilities(),current.getRequiredAbilities())) throw stale();
            var after=observe(actor,taskId);
            if(!observed.keySet().equals(after.keySet())) throw stale();
            for(var id:observed.keySet()) if(!observed.get(id).sameFacts(after.get(id))) throw stale();
            if(!after.isEmpty()) materials.verifyUnchanged(actor,taskId,inputs);
            return project(taskId,request.expectedTaskVersion(),time,found,after);
        });
    }
    private AgentTaskDTO task(String id,String version) {
        var task=agents.getTaskRecommendationInput(id);
        if(!version.equals(task.getTaskVersion()) || !"open".equalsIgnoreCase(task.getStatus())) throw stale();
        return task;
    }
    private static Failure stale(){return new Failure(409,"RECOMMENDATION_STALE");}
    private record CandidateFacts(Agent agent,String provider, Map<String,CapabilityDocuments.Citation> citations) {
        boolean sameFacts(CandidateFacts now) {
            return agent.boundToMe()==now.agent.boundToMe() && Objects.equals(provider,now.provider)
                    && agent.abilities().equals(now.agent.abilities()) && citations.equals(now.citations);
        }
    }
    private Map<String,CandidateFacts> observe(Actor actor,String taskId) {
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
    private static Result project(String task,String version,long time,CapabilitySearch.Result found,Map<String,CandidateFacts> facts) {
        var rows=new ArrayList<Recommendation>();
        for(var hit:found.rows()) {
            var c=facts.get(hit.agentId()); var evidence=new LinkedHashMap<String,Evidence>();
            for(var h:Arrays.asList(hit.lexical(),hit.vector())) if(h!=null){
                var ref=c.citations.get(h.evidenceKey()); if(ref==null) throw stale();
                boolean ability=ref.displayRef().startsWith("ability:");
                evidence.putIfAbsent(ref.displayRef(),new Evidence(ref.displayRef(),ability?"DECLARED_ABILITY":"INSTALLED_SKILL",
                        ref.doc().text().lines().findFirst().orElseThrow(),ability?"已登记本领，不代表执行准入。":ref.doc().text(),true));
            }
            // No calibrated thresholds yet. A retrieval hit is a citation, not a capability verdict.
            String reason=evidence.isEmpty()?"当前没有可用于判断匹配度的检索依据。":"检索到相关能力声明，匹配等级尚未校准。";
            rows.add(new Recommendation(c.agent,"UNKNOWN",reason,List.copyOf(evidence.values()),List.of()));
        }
        rows.sort(Comparator.comparing((Recommendation r)->!r.agent().boundToMe())); // stable within each group
        String state=switch(found.mode()){case "NOT_NEEDED"->"NO_CANDIDATES";case "HYBRID"->"NO_MATCH";default->"DEGRADED";};
        return new Result(task,version,time,state,List.copyOf(rows),true,found.mode(),CandidateRankFusion.VERSION,found.reasons());
    }
}
