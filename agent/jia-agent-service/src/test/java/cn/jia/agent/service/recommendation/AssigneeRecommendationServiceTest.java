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
    final AssigneeRecommendationService service = new AssigneeRecommendationService(agents,skills,materials,vectors,search,tx,new CandidateSuitability("",""));
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
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;
    CandidateSuitability calibration(List<CandidateSuitability.Cell> rules,String forcedPin)throws Exception{
        var partition=CapabilityDocuments.observe(actor,row,List.of(),gen).getFirst().doc().partition();
        var a=new CandidateSuitability.Artifact(CandidateSuitability.VERSION,CandidateRankFusion.VERSION,TaskRetrievalText.VERSION,CapabilityDocuments.TEMPLATE,partition,gen,"a".repeat(64),"b".repeat(64),"c".repeat(64),rules);
        byte[] bytes=new tools.jackson.databind.ObjectMapper().writeValueAsBytes(a);var file=java.nio.file.Files.write(directory.resolve("test-calibration.json"),bytes);
        return new CandidateSuitability(file.toString(),forcedPin==null?HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)):forcedPin);
    }
    @Test void calibratedModePromotesExternalAboveUnratedOwnedCandidateWithoutBorrowingHybridPolicy()throws Exception{
        var other=new AgentTaskAssigneeCandidateDTO(new AgentTaskTargetSummaryDTO("b","Other",null,false,"online"),List.of("排版"),"other");
        when(agents.listTaskAssigneeCandidates("task")).thenReturn(List.of(row,other));when(skills.recommendationSkills(eq(actor),anyList())).thenReturn(Map.of("a",List.of(),"b",List.of()));
        var cut=new CandidateSuitability.Floors(1d,1.6d);var high=new CandidateSuitability.Cell("HYBRID","BOTH_SAME",false,false,2,2,null,cut);
        var config=calibration(List.of(high),null);var active=new AssigneeRecommendationService(agents,skills,materials,vectors,search,tx,config);
        when(search.retrieve(anySet(),anyList(),anyString(),nullable(TaskFeatureEncoder.Vector.class))).thenAnswer(i->{List<CapabilityDocuments.Doc> docs=i.getArgument(1);var h=new CandidateRankFusion.Hit("b",docs.stream().filter(d->d.agentId().equals("b")).findFirst().orElseThrow().evidenceKey(),1.7);
            boolean hybrid=i.getArgument(3)!=null;return new CapabilitySearch.Result(hybrid?"HYBRID":"LEXICAL_ONLY",CandidateRankFusion.fuse(Set.of("a","b"),List.of(h),hybrid?List.of(h):List.of()),hybrid?List.of():List.of("QUERY_VECTOR_UNAVAILABLE"));});
        var good=active.recommend(actor,"task",new Request("7"));assertEquals("READY",good.state());assertEquals(List.of("b","a"),good.recommendations().stream().map(r->r.agent().agentId()).toList());assertEquals("HIGH",good.recommendations().getFirst().recommendationLevel());assertNotEquals(CandidateRankFusion.VERSION,good.rankingVersion());
        doReturn(Mono.error(new TaskFeatureEncoder.Unavailable())).when(vectors).encode(eq(actor),any());var degraded=active.recommend(actor,"task",new Request("7"));assertEquals("DEGRADED",degraded.state());assertTrue(degraded.recommendations().stream().allMatch(r->r.recommendationLevel().equals("UNKNOWN")));
    }
    @Test void lexicalCalibrationRequiresExactEvidenceScopeAndVerifiedArtifact()throws Exception{
        var c=new CandidateSuitability.Cell("LEXICAL_ONLY","LEXICAL",true,false,1,3,new CandidateSuitability.Floors(1d,null),null);
        var doc=CapabilityDocuments.observe(actor,row,List.of(),gen).getFirst().doc();var hit=CandidateRankFusion.fuse(Set.of("a"),List.of(new CandidateRankFusion.Hit("a",doc.evidenceKey(),1)),List.of()).getFirst();
        var ev=List.of(new Evidence("ability:0","DECLARED_ABILITY","写作","",true));var ctx=new CandidateSuitability.Context(false,Set.of("写作"),false,1);var policy=calibration(List.of(c),null);
        assertEquals("MEDIUM",policy.level("LEXICAL_ONLY",hit,ctx,ev,doc));for(var other:List.of(new CandidateSuitability.Context(false,Set.of("写作","排版"),false,1),new CandidateSuitability.Context(true,Set.of("写作"),false,1),new CandidateSuitability.Context(false,Set.of("写作"),false,4)))assertEquals("UNKNOWN",policy.level("LEXICAL_ONLY",hit,other,ev,doc));
        var foreign=CapabilityDocuments.declared("other","client","owner","a",List.of("写作"),gen).getFirst().doc();assertEquals("UNKNOWN",policy.level("LEXICAL_ONLY",hit,ctx,ev,foreign));
        var drift=CapabilityDocuments.declared("0","client","owner","a",List.of("写作"),new TaskFeatureEncoder.Identity("changed","test",1)).getFirst().doc();assertEquals("UNKNOWN",policy.level("LEXICAL_ONLY",hit,ctx,ev,drift));
        for(var invalid:List.of(calibration(List.of(c),"d".repeat(64)),calibration(List.of(c,c),null)))assertEquals("UNKNOWN",invalid.level("LEXICAL_ONLY",hit,ctx,ev,doc));
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
