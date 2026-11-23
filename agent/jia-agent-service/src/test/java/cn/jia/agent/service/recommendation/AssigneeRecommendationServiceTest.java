package cn.jia.agent.service.recommendation;

import cn.jia.agent.entity.*;
import cn.jia.agent.hosting.HostingRentHttp.Actor;
import cn.jia.agent.service.*;
import cn.jia.agent.skill.SkillManagementService;
import org.junit.jupiter.api.*;
import reactor.core.publisher.Mono;
import java.util.*;
import static cn.jia.agent.entity.AssigneeRecommendation.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AssigneeRecommendationServiceTest {
    final Actor actor = new Actor("user", "0", "client", "owner");
    final AgentService agents = mock();
    final SkillManagementService skills = mock();
    final RecommendationMaterials materials = mock();
    final TaskQueryVectors vectors = mock();
    final CapabilitySearch search = mock();
    final AgentTaskMutationTransaction tx = mock();
    final AssigneeRecommendationService service = new AssigneeRecommendationService(agents,skills,materials,vectors,search,tx);
    final TaskFeatureEncoder.Identity gen = new TaskFeatureEncoder.Identity("test","test",1);
    final AgentTaskDTO task = new AgentTaskDTO();
    final AgentTaskAssigneeCandidateDTO row = candidate("写作");
    boolean locked;
    static AgentTaskAssigneeCandidateDTO candidate(String ability) {
        return new AgentTaskAssigneeCandidateDTO(new AgentTaskTargetSummaryDTO("a","Agent",null,true,"online"),List.of(ability),"owner");
    }
    @BeforeEach void setup() {
        task.setTaskVersion("7"); task.setTitle("写说明"); task.setStatus("open");
        when(agents.getTaskRecommendationInput("task")).thenReturn(task);
        when(agents.listTaskAssigneeCandidates("task")).thenReturn(List.of(row));
        when(skills.recommendationSkills(eq(actor),anyList())).thenReturn(Map.of("a",List.of()));
        when(vectors.identity()).thenReturn(gen);
        when(vectors.encode(eq(actor),any())).thenAnswer(i->{assertFalse(locked);return Mono.just(new TaskFeatureEncoder.Vector(gen,new float[]{1}));});
        when(search.retrieve(anySet(),anyList(),anyString(),nullable(TaskFeatureEncoder.Vector.class))).thenAnswer(i->{
            assertFalse(locked); List<CapabilityDocuments.Doc> docs=i.getArgument(1);
            var h=new CandidateRankFusion.Hit("a",docs.getFirst().evidenceKey(),1);
            boolean lexical=i.getArgument(3)==null;
            return new CapabilitySearch.Result(lexical?"LEXICAL_ONLY":"HYBRID",CandidateRankFusion.fuse(Set.of("a"),List.of(h),List.of()),lexical?List.of("QUERY_VECTOR_UNAVAILABLE"):List.of());
        });
        when(tx.executeWithLockedTaskRootInOwnerScope(eq("0"),eq("client"),eq("owner"),eq("task"),any())).thenAnswer(i->{
            locked=true; try{return i.<AgentTaskMutationTransaction.LockedTaskMutation<Result>>getArgument(4).apply(null);} finally{locked=false;}
        });
    }
    Result recommend(){return service.recommend(actor,"task",new Request("7"));}
    @Test void uncalibratedHitCitesOnlyPublicReference() {
        var r=recommend(); assertEquals("NO_MATCH",r.state()); assertEquals("HYBRID",r.retrievalMode()); assertTrue(r.candidateSetComplete());
        var item=r.recommendations().getFirst(); assertEquals("UNKNOWN",item.recommendationLevel()); assertEquals("ability:0",item.evidence().getFirst().id());
        verify(materials).verifyUnchanged(actor,"task",List.of()); verify(agents,times(2)).listTaskAssigneeCandidates("task");
    }
    @Test void noCandidatesAvoidsRetrievalButRechecksAuthority() {
        when(agents.listTaskAssigneeCandidates("task")).thenReturn(List.of()); var r=recommend();
        assertEquals("NO_CANDIDATES",r.state()); assertEquals("NOT_NEEDED",r.retrievalMode()); assertTrue(r.recommendations().isEmpty());
        verifyNoInteractions(search,vectors,skills,materials); verify(agents,times(2)).listTaskAssigneeCandidates("task");
    }
    @Test void vectorFailureKeepsLexicalEvidenceWithoutRetry(){
        doReturn(Mono.error(new TaskFeatureEncoder.InvalidResult())).when(vectors).encode(eq(actor),any()); var r=recommend();
        assertEquals("DEGRADED",r.state()); assertEquals("LEXICAL_ONLY",r.retrievalMode()); assertEquals(List.of("QUERY_VECTOR_INVALID"),r.degradedReasons());
        verify(vectors).encode(eq(actor),any());
    }
    @Test void staleVersionDoesNotRetrieve(){task.setTaskVersion("8");assertEquals(409,assertThrows(Failure.class,this::recommend).status());verifyNoInteractions(search,vectors);}
    @Test void missingSkillSnapshotIsNotEmptySuccess(){when(skills.recommendationSkills(eq(actor),anyList())).thenReturn(Map.of());assertEquals(503,assertThrows(Failure.class,this::recommend).status());verifyNoInteractions(search);}
    @Test void candidateRevokedDuringRetrievalCannotLeakPriorResult(){when(agents.listTaskAssigneeCandidates("task")).thenReturn(List.of(row),List.of());assertEquals(409,assertThrows(Failure.class,this::recommend).status());}
    @Test void changedAbilityInvalidatesEvidence(){when(agents.listTaskAssigneeCandidates("task")).thenReturn(List.of(row),List.of(candidate("绘图")));assertEquals(409,assertThrows(Failure.class,this::recommend).status());}
    @Test void reorderingPublicNumbersUsesCurrentCitationAndOfflineStatusWithoutRejecting() {
        var initial=new AgentTaskAssigneeCandidateDTO(row.target(),List.of("写作","排版"),"owner");
        var changed=new AgentTaskAssigneeCandidateDTO(new AgentTaskTargetSummaryDTO("a","新名称",null,true,"offline"),List.of("排版","写作"),"owner");
        when(agents.listTaskAssigneeCandidates("task")).thenReturn(List.of(initial),List.of(changed));
        var item=recommend().recommendations().getFirst(); var citation=item.evidence().getFirst();
        assertEquals(changed.abilities().get(Integer.parseInt(citation.id().substring(8))),citation.label());
        assertEquals("新名称",item.agent().name()); assertFalse(item.gaps().isEmpty()); assertEquals("UNKNOWN",item.recommendationLevel());
    }
    @Test void materialConflictIsNotRetrievalDegradation(){doThrow(new Failure(409,"RECOMMENDATION_STALE")).when(materials).verifyUnchanged(actor,"task",List.of());assertEquals(409,assertThrows(Failure.class,this::recommend).status());}
}
