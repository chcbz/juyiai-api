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
    final AssigneeRecommendationService service = new AssigneeRecommendationService(agents,skills,materials,vectors,search,tx,new CandidateSuitability("",""));
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
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;
    CandidateSuitability calibration(List<CandidateSuitability.Cell> rules,String forcedPin)throws Exception{
        var partition=CapabilityDocuments.observe(actor,row,List.of(),gen).getFirst().doc().partition();
        var a=new CandidateSuitability.Artifact(CandidateSuitability.VERSION,CandidateRankFusion.VERSION,TaskRetrievalText.VERSION,CapabilityDocuments.TEMPLATE,CapabilityIndexWriter.GENERATION,partition,gen,"a".repeat(64),"b".repeat(64),"c".repeat(64),rules);
        byte[] bytes=new tools.jackson.databind.ObjectMapper().writeValueAsBytes(a);var file=java.nio.file.Files.write(directory.resolve("test-calibration.json"),bytes);
        return new CandidateSuitability(file.toString(),forcedPin==null?HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)):forcedPin);
    }
    @Test void missingCalibrationIsDisclosedWithoutClaimingNoAbilityOrReadiness() {
        var result=recommend();var item=result.recommendations().getFirst();
        assertEquals("HYBRID",result.retrievalMode());assertEquals("NO_MATCH",result.state());
        assertEquals("UNKNOWN",item.recommendationLevel());assertFalse(item.evidence().isEmpty());
        assertEquals("已找到能力依据，但匹配等级尚未完成校准，暂不判定是否适配。",item.reason());
        assertTrue(result.degradedReasons().isEmpty());
    }
    @Test void invalidCalibrationDisclosesUnavailableCalibrationButKeepsMissingEvidenceDistinct() throws Exception {
        var rule=new CandidateSuitability.Cell("HYBRID","LEXICAL",false,false,1,1,new CandidateSuitability.Floors(1d,null),null);
        var invalid=calibration(List.of(rule),"d".repeat(64));
        assertEquals(new CandidateSuitability("","").unknownReason(true),invalid.unknownReason(true));
        assertEquals("当前没有可用于判断匹配度的检索依据。",invalid.unknownReason(false));
        var valid=calibration(List.of(rule),null);
        assertEquals("现有依据不足以确定匹配等级。",valid.unknownReason(true));
    }

    @Test void calibratedModePromotesExternalAboveUnratedOwnedCandidateWithoutBorrowingHybridPolicy()throws Exception{
        var other=new AgentTaskAssigneeCandidateDTO(new AgentTaskTargetSummaryDTO("b","Other",null,false,"online"),List.of("排版"),"other");
        when(agents.listTaskAssigneeCandidates("task")).thenReturn(List.of(row,other));when(skills.recommendationSkills(eq(actor),anyList())).thenReturn(Map.of("a",List.of(),"b",List.of()));
        var cut=new CandidateSuitability.Floors(1d,1.6d);var high=new CandidateSuitability.Cell("HYBRID","BOTH_SAME",false,false,2,2,null,cut);
        var config=calibration(List.of(high),null);var active=new AssigneeRecommendationService(agents,skills,materials,vectors,search,tx,config);
        org.mockito.Mockito.doAnswer(i->{List<CapabilityDocuments.Doc> docs=i.getArgument(1);var h=new CandidateRankFusion.Hit("b",docs.stream().filter(d->d.agentId().equals("b")).findFirst().orElseThrow().evidenceKey(),1.7,CapabilityIndexWriter.GENERATION);
            var query=i.<java.util.function.Supplier<CapabilitySearch.QueryVector>>getArgument(3).get();boolean hybrid=query.value()!=null;return new CapabilitySearch.Result(hybrid?"HYBRID":"LEXICAL_ONLY",CandidateRankFusion.fuse(Set.of("a","b"),List.of(h),hybrid?List.of(h):List.of()),hybrid?List.of():List.of(query.missingReason()));}).when(search).retrieve(anySet(),anyList(),anyString(),any());
        var good=active.recommend(actor,"task",new Request("7"));assertEquals("READY",good.state());assertEquals(List.of("b","a"),good.recommendations().stream().map(r->r.agent().agentId()).toList());assertEquals("HIGH",good.recommendations().getFirst().recommendationLevel());assertNotEquals(CandidateRankFusion.VERSION,good.rankingVersion());
        doReturn(Mono.error(new TaskFeatureEncoder.Unavailable())).when(vectors).encode(eq(actor),any());var degraded=active.recommend(actor,"task",new Request("7"));assertEquals("DEGRADED",degraded.state());assertTrue(degraded.recommendations().stream().allMatch(r->r.recommendationLevel().equals("UNKNOWN")));
    }
    @Test void lexicalCalibrationRequiresExactEvidenceScopeAndVerifiedArtifact()throws Exception{
        var c=new CandidateSuitability.Cell("LEXICAL_ONLY","LEXICAL",true,false,1,3,new CandidateSuitability.Floors(1d,null),null);
        var doc=CapabilityDocuments.observe(actor,row,List.of(),gen).getFirst().doc();var hit=CandidateRankFusion.fuse(Set.of("a"),List.of(new CandidateRankFusion.Hit("a",doc.evidenceKey(),1,CapabilityIndexWriter.GENERATION)),List.of()).getFirst();
        var ev=List.of(new Evidence("ability:0","DECLARED_ABILITY","写作","",true));var ctx=new CandidateSuitability.Context(false,Set.of("写作"),false,1);var policy=calibration(List.of(c),null);
        assertEquals("MEDIUM",policy.level("LEXICAL_ONLY",hit,ctx,ev,doc));var stale=CandidateRankFusion.fuse(Set.of("a"),List.of(new CandidateRankFusion.Hit("a",doc.evidenceKey(),5,"different-index")),List.of()).getFirst();assertEquals("UNKNOWN",policy.level("LEXICAL_ONLY",stale,ctx,ev,doc));for(var other:List.of(new CandidateSuitability.Context(false,Set.of("写作","排版"),false,1),new CandidateSuitability.Context(true,Set.of("写作"),false,1),new CandidateSuitability.Context(false,Set.of("写作"),false,4)))assertEquals("UNKNOWN",policy.level("LEXICAL_ONLY",hit,other,ev,doc));
        var foreign=CapabilityDocuments.declared("other","client","owner","a",List.of("写作"),gen).getFirst().doc();assertEquals("UNKNOWN",policy.level("LEXICAL_ONLY",hit,ctx,ev,foreign));
        var drift=CapabilityDocuments.declared("0","client","owner","a",List.of("写作"),new TaskFeatureEncoder.Identity("changed","test",1)).getFirst().doc();assertEquals("UNKNOWN",policy.level("LEXICAL_ONLY",hit,ctx,ev,drift));
        for(var invalid:List.of(calibration(List.of(c),"d".repeat(64)),calibration(List.of(c,c),null)))assertEquals("UNKNOWN",invalid.level("LEXICAL_ONLY",hit,ctx,ev,doc));
    }

    @Test void calibratedLexicalOnlyMayRecommendButStillDisclosesDegradation()throws Exception{
        var c=new CandidateSuitability.Cell("LEXICAL_ONLY","LEXICAL",false,false,1,1,new CandidateSuitability.Floors(0.5,null),null);
        var calibrated=calibration(List.of(c),null);var active=new AssigneeRecommendationService(agents,skills,materials,vectors,search,tx,calibrated);
        doReturn(Mono.error(new TaskFeatureEncoder.Unavailable())).when(vectors).encode(eq(actor),any());var r=active.recommend(actor,"task",new Request("7"));
        assertEquals("DEGRADED",r.state());assertEquals("MEDIUM",r.recommendations().getFirst().recommendationLevel());assertEquals(List.of("QUERY_VECTOR_UNAVAILABLE"),r.degradedReasons());
        var file=directory.resolve("test-calibration.json");String good=java.nio.file.Files.readString(file);
        for(String bad:List.of(good.replaceFirst("[{]","{\"unknownSelector\":1,"),good.replaceFirst("[{]","{\"schema\":\"incompatible\","),good+" {}")){
            var bytes=bad.getBytes(java.nio.charset.StandardCharsets.UTF_8);java.nio.file.Files.write(file,bytes);String pin=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
            assertEquals(CandidateRankFusion.VERSION,new CandidateSuitability(file.toString(),pin).rankingVersion());}
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
        var active=new AssigneeRecommendationService(agents,skills,materials,vectors,search,tx,new CandidateSuitability("",""),Optional.of(registry));
        assertThrows(Failure.class,()->active.recommend(actor,"task",new Request("wrong-version")));assertTrue(events.isEmpty());
        active.recommend(actor,"task",new Request("7"));assertEquals(List.of("initial:ok","recheck:ok"),events);events.clear();
        when(agents.listTaskAssigneeCandidates("task")).thenReturn(List.of());active.recommend(actor,"task",new Request("7"));
        assertEquals(List.of("initial:empty","recheck:empty"),events);events.clear();
        when(agents.listTaskAssigneeCandidates("task")).thenReturn(List.of(row));when(skills.recommendationSkills(eq(actor),anyList())).thenReturn(Map.of());
        assertThrows(Failure.class,()->active.recommend(actor,"task",new Request("7")));assertEquals(List.of("initial:error"),events);
    }
    @Test void projectionUsesTheVerifiedSnapshotAfterReleasingTheTaskLock(){
        var policy=mock(CandidateSuitability.class);
        when(policy.level(anyString(),any(),any(),any(),any())).thenAnswer(i->{assertFalse(locked);return "UNKNOWN";});
        doAnswer(i->{assertTrue(locked);return null;}).when(materials).verifyUnchanged(actor,"task",List.of());
        var active=new AssigneeRecommendationService(agents,skills,materials,vectors,search,tx,policy);
        var result=active.recommend(actor,"task",new Request("7"));assertEquals("NO_MATCH",result.state());
        var order=inOrder(materials,policy);order.verify(materials).verifyUnchanged(actor,"task",List.of());order.verify(policy).level(anyString(),any(),any(),any(),any());
    }
    @Test void knownMismatchNeverMeansReadyOrOutranksASuitableExternalAgent(){
        // The current calibration does not infer LOW from a score. Exercise the projection contract separately.
        var policy=mock(CandidateSuitability.class);
        when(policy.level(anyString(),any(),any(),any(),any())).thenReturn("LOW");
        var active=new AssigneeRecommendationService(agents,skills,materials,vectors,search,tx,policy);
        var mismatch=active.recommend(actor,"task",new Request("7"));
        assertEquals("NO_MATCH",mismatch.state());assertFalse(mismatch.recommendations().getFirst().evidence().isEmpty());
        assertTrue(mismatch.recommendations().getFirst().reason().contains("不匹配"));
        var other=new AgentTaskAssigneeCandidateDTO(new AgentTaskTargetSummaryDTO("b","Other",null,false,"online"),List.of("排版"),"other");
        when(agents.listTaskAssigneeCandidates("task")).thenReturn(List.of(row,other));
        when(skills.recommendationSkills(eq(actor),anyList())).thenReturn(Map.of("a",List.of(),"b",List.of()));
        doAnswer(i->{List<CapabilityDocuments.Doc> docs=i.getArgument(1);var hits=docs.stream().map(d->new CandidateRankFusion.Hit(d.agentId(),d.evidenceKey(),1,CapabilityIndexWriter.GENERATION)).toList();
            return new CapabilitySearch.Result("HYBRID",CandidateRankFusion.fuse(Set.of("a","b"),hits,List.of()),List.of());}).when(search).retrieve(anySet(),anyList(),anyString(),any());
        doAnswer(i->"b".equals(i.<CapabilityDocuments.Doc>getArgument(4).agentId())?"MEDIUM":"LOW").when(policy).level(anyString(),any(),any(),any(),any());
        var mixed=active.recommend(actor,"task",new Request("7"));assertEquals("READY",mixed.state());
        assertEquals(List.of("b","a"),mixed.recommendations().stream().map(r->r.agent().agentId()).toList());
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
    @Test void emptyAuthorizedEvidenceSkipsEmbeddingAndSearchButKeepsManualCandidate(){
        var empty=new AgentTaskAssigneeCandidateDTO(row.target(),List.of(),"owner");
        when(agents.listTaskAssigneeCandidates("task")).thenReturn(List.of(empty));var r=recommend();
        assertEquals("NO_MATCH",r.state());assertEquals("NOT_NEEDED",r.retrievalMode());assertTrue(r.degradedReasons().isEmpty());
        assertEquals(1,r.recommendations().size());var item=r.recommendations().getFirst();assertEquals("a",item.agent().agentId());
        assertEquals("UNKNOWN",item.recommendationLevel());assertTrue(item.evidence().isEmpty());assertTrue(r.candidateSetComplete());
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
        assertEquals("新名称",item.agent().name()); assertFalse(item.gaps().isEmpty()); assertEquals("UNKNOWN",item.recommendationLevel());
    }
    @Test void materialConflictIsNotRetrievalDegradation(){doThrow(new Failure(409,"RECOMMENDATION_STALE")).when(materials).verifyUnchanged(actor,"task",List.of());assertEquals(409,assertThrows(Failure.class,this::recommend).status());}
}
