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
    final AgentTaskRootObservation tx = mock();
    final AssigneeRecommendationService service = new AssigneeRecommendationService(agents,skills,materials,vectors,search,tx);
    final TaskFeatureEncoder.Identity gen = new TaskFeatureEncoder.Identity("test","test",1);
    final AgentTaskDTO task = new AgentTaskDTO();
    final AgentTaskAssigneeCandidateDTO row = candidate("写作");
    boolean locked;
    final AgentTaskMetaEntity lockedRoot = new AgentTaskMetaEntity();
    static AgentTaskAssigneeCandidateDTO candidate(String ability) {
        return new AgentTaskAssigneeCandidateDTO(new AgentTaskTargetSummaryDTO("a","Agent",null,true,"online"),List.of(ability),"owner");
    }
    @BeforeEach void setup() {
        task.setTaskVersion("7"); task.setTitle("写说明"); task.setStatus("open");
        when(agents.getTaskRecommendationInputWithinLockedTask(eq("task"),same(lockedRoot))).thenAnswer(i->{assertTrue(locked);return task;});
        when(agents.listTaskAssigneeCandidates("task")).thenReturn(List.of(row));
        when(skills.recommendationSkills(eq(actor),anyList())).thenReturn(Map.of("a",List.of()));
        when(vectors.identity()).thenReturn(gen);
        when(vectors.encode(eq(actor),any())).thenAnswer(i->{assertFalse(locked);return Mono.just(new TaskFeatureEncoder.Vector(gen,new float[]{1}));});
        when(search.retrieve(anySet(),anyList(),anyString(),any())).thenAnswer(i->{
            assertFalse(locked); List<CapabilityDocuments.Doc> docs=i.getArgument(1);
            var h=new CandidateRankFusion.Hit("a",docs.getFirst().evidenceKey(),1,CapabilityIndexWriter.GENERATION);
            var query=i.<java.util.function.Supplier<CapabilitySearch.QueryVector>>getArgument(3).get();boolean lexical=query.value()==null;
            return new CapabilitySearch.Result(lexical?"LEXICAL_ONLY":"HYBRID",CandidateRankFusion.fuse(Set.of("a"),List.of(h),List.of()),lexical?List.of(query.missingReason()):List.of());
        });
        when(tx.observe(eq("0"),eq("client"),eq("owner"),eq("task"),any())).thenAnswer(i->{
            locked=true; try{return i.<java.util.function.Function<AgentTaskMetaEntity,?>>getArgument(4).apply(lockedRoot);} finally{locked=false;}
        });
    }
    @Test void evidenceHitOutranksOwnedCandidateWithoutEvidence()throws Exception{
        var other=new AgentTaskAssigneeCandidateDTO(new AgentTaskTargetSummaryDTO("b","Other",null,false,"online"),List.of("排版"),"other");
        when(agents.listTaskAssigneeCandidates("task")).thenReturn(List.of(row,other));when(skills.recommendationSkills(eq(actor),anyList())).thenReturn(Map.of("a",List.of(),"b",List.of()));
        org.mockito.Mockito.doAnswer(i->{List<CapabilityDocuments.Doc> docs=i.getArgument(1);var h=new CandidateRankFusion.Hit("b",docs.stream().filter(d->d.agentId().equals("b")).findFirst().orElseThrow().evidenceKey(),1.7,CapabilityIndexWriter.GENERATION);
            var query=i.<java.util.function.Supplier<CapabilitySearch.QueryVector>>getArgument(3).get();boolean hybrid=query.value()!=null;return new CapabilitySearch.Result(hybrid?"HYBRID":"LEXICAL_ONLY",CandidateRankFusion.fuse(Set.of("a","b"),List.of(h),hybrid?List.of(h):List.of()),hybrid?List.of():List.of(query.missingReason()));}).when(search).retrieve(anySet(),anyList(),anyString(),any());
        var good=service.recommend(actor,"task",new Request("7"));assertEquals("READY",good.state());assertEquals(List.of("b","a"),good.recommendations().stream().map(r->r.agent().agentId()).toList());assertEquals(CandidateRankFusion.VERSION,good.rankingVersion());
        doReturn(Mono.error(new TaskFeatureEncoder.Unavailable())).when(vectors).encode(eq(actor),any());var degraded=service.recommend(actor,"task",new Request("7"));assertEquals("DEGRADED",degraded.state());assertFalse(degraded.recommendations().getFirst().evidence().isEmpty());
    }
    @Test void candidateReadMeasuresOnlyActualReadsIncludingEmptyAndAuthorityFailure(){
        var registry=io.micrometer.observation.ObservationRegistry.create();
        var events=new ArrayList<String>();
        registry.observationConfig().observationHandler(new io.micrometer.observation.ObservationHandler<io.micrometer.observation.Observation.Context>(){
            public boolean supportsContext(io.micrometer.observation.Observation.Context context){return true;}
            public void onStop(io.micrometer.observation.Observation.Context context){
                assertEquals("cyf.task.recommendation.candidate.read",context.getName());
                var tags=new HashMap<String,String>();context.getLowCardinalityKeyValues().forEach(k->tags.put(k.getKey(),k.getValue()));
                assertEquals(Set.of("phase","outcome"),tags.keySet());assertFalse(context.getHighCardinalityKeyValues().iterator().hasNext());
                events.add(tags.get("phase")+":"+tags.get("outcome"));
            }
        });
        var active=new AssigneeRecommendationService(agents,skills,materials,vectors,search,tx,Optional.of(registry));
        assertThrows(Failure.class,()->active.recommend(actor,"task",new Request("wrong-version")));assertTrue(events.isEmpty());
        active.recommend(actor,"task",new Request("7"));assertEquals(List.of("initial:ok","recheck:ok"),events);events.clear();
        when(agents.listTaskAssigneeCandidates("task")).thenReturn(List.of());active.recommend(actor,"task",new Request("7"));
        assertEquals(List.of("initial:empty","recheck:empty"),events);events.clear();
        when(agents.listTaskAssigneeCandidates("task")).thenReturn(List.of(row));when(skills.recommendationSkills(eq(actor),anyList())).thenReturn(Map.of());
        assertThrows(Failure.class,()->active.recommend(actor,"task",new Request("7")));assertEquals(List.of("initial:error"),events);
    }
    Result recommend(){return service.recommend(actor,"task",new Request("7"));}
    @Test void retrievalHitCitesOnlyPublicReference() {
        var r=recommend(); assertEquals("READY",r.state()); assertEquals("HYBRID",r.retrievalMode()); assertTrue(r.candidateSetComplete());
        var item=r.recommendations().getFirst();  assertEquals("ability:0",item.evidence().getFirst().id());
        verify(materials).verifyUnchanged(actor,"task",List.of()); verify(agents,times(2)).listTaskAssigneeCandidates("task");
    }
    @Test void noCandidatesAvoidsRetrievalButRechecksAuthority() {
        when(agents.listTaskAssigneeCandidates("task")).thenReturn(List.of()); var r=recommend();
        assertEquals("NO_CANDIDATES",r.state()); assertEquals("NOT_NEEDED",r.retrievalMode()); assertTrue(r.recommendations().isEmpty());
        verifyNoInteractions(search,vectors,skills,materials); verify(agents,times(2)).listTaskAssigneeCandidates("task");
    }
    @Test void emptyAuthorizedEvidenceSkipsEmbeddingAndSearchButKeepsManualCandidate(){
        var empty=new AgentTaskAssigneeCandidateDTO(row.target(),List.of(),"owner");
        when(agents.listTaskAssigneeCandidates("task")).thenReturn(List.of(empty));var r=recommend();
        assertEquals("NO_MATCH",r.state());assertEquals("NOT_NEEDED",r.retrievalMode());assertTrue(r.degradedReasons().isEmpty());
        assertEquals(1,r.recommendations().size());var item=r.recommendations().getFirst();assertEquals("a",item.agent().agentId());
        assertTrue(item.evidence().isEmpty());assertTrue(r.candidateSetComplete());
        verifyNoInteractions(search);verify(vectors,never()).encode(any(),any());verify(materials).verifyUnchanged(actor,"task",List.of());
        when(agents.listTaskAssigneeCandidates("task")).thenReturn(List.of(empty),List.of(row));
        assertEquals(409,assertThrows(Failure.class,this::recommend).status());verifyNoInteractions(search);verify(vectors,never()).encode(any(),any());
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
        assertEquals("新名称",item.agent().name()); assertFalse(item.gaps().isEmpty());
    }
    @Test void materialConflictIsNotRetrievalDegradation(){doThrow(new Failure(409,"RECOMMENDATION_STALE")).when(materials).verifyUnchanged(actor,"task",List.of());assertEquals(409,assertThrows(Failure.class,this::recommend).status());}
}
