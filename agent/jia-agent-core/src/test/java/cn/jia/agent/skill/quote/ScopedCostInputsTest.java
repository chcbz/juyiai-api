package cn.jia.agent.skill.quote;

import cn.jia.agent.requirement.ConfirmedScope;
import cn.jia.agent.requirement.ConfirmedScope.*;
import cn.jia.economy.pricing.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScopedCostInputsTest {
    private static final String H="a".repeat(64);
    private static final CostRouteReference REF=new CostRouteReference("MODEL_RESPONSE","INFERENCE","provider","model","TOKENS_V1");
    private static CapabilityQuoteSpec spec(String capability,boolean metadata){
        return new CapabilityQuoteSpec(CapabilityQuoteSpec.SCHEMA,capability,"1",
            Map.of("deliverables.count",new CapabilityQuoteSpec.Field(ValueType.INTEGER,"unit/items",true,"1","100",Set.of(),null)),
            List.of(new CapabilityQuoteSpec.CostDimension("size",metadata?CapabilityQuoteSpec.Source.INPUT_SUM:CapabilityQuoteSpec.Source.DELIVERABLE,
                metadata?"bytes":"count",ValueType.INTEGER,metadata?"unit/bytes":"unit/items",metadata?"meta-1":null,Set.of())),
            List.of(new CapabilityQuoteSpec.Bucket("small",Map.of("size",new CapabilityQuoteSpec.Range("0",metadata?"4096":"3",Set.of())))));
    }
    private static WorkItem item(String id,String capability,String count,List<String> inputs,List<String> deps){
        return new WorkItem(id,capability,"1",Map.of(),Map.of("count",new Scalar(ValueType.INTEGER,"unit/items",count)),inputs,deps);
    }
    private static ConfirmedScope scope(List<WorkItem> items,String acceptance){
        var payload=new ConfirmedScope.Payload(ConfirmedScope.VERSION,items,acceptance);
        return new ConfirmedScope(1,H,payload.digest(H),H,ConfirmedScope.VERSION,0,items,acceptance);
    }
    private static Map<ScopedCostInputs.Capability,CapabilityQuoteSpec> specs(CapabilityQuoteSpec... values){
        var result=new HashMap<ScopedCostInputs.Capability,CapabilityQuoteSpec>();
        for(var s:values)result.put(new ScopedCostInputs.Capability(s.capabilityId(),s.version()),s);return result;
    }
    private static MeasuredCostProfile measured(ConfirmedScope scope,Map<ScopedCostInputs.Capability,CapabilityQuoteSpec> specs,String install,String context,int priority){
        var slots=new ArrayList<MeasuredCostProfile.Slot>();var ordinals=new HashMap<String,Integer>();
        for(var item:scope.workItems()){
            int i=slots.size();var spec=specs.get(new ScopedCostInputs.Capability(item.capabilityId(),item.capabilityVersion()));
            slots.add(new MeasuredCostProfile.Slot(i,item.capabilityId(),item.capabilityVersion(),spec.specSha(),item.dependsOn().stream().map(ordinals::get).toList(),
                "skill-"+i,"1",H,install+"-"+i));ordinals.put(item.itemId(),i);
        }
        return new MeasuredCostProfile("p"+priority,1,priority,slots,"native-1","context",context,H,"capture-1",List.of(REF));
    }
    private static CostProfile resolved(MeasuredCostProfile m){return new CostProfile(m,List.of(new CostProfile.Route(REF,new CostPricingRoute.TokenRoute("channel","model","tier","TOKENS_V1"))));}

    @Test void threeBusinessKindsUseTheSameDeclarationAndWholeScopeClassifier(){
        for(String capability:List.of("text.summary","visual.asset","audio.transcribe")){
            var specs=specs(spec(capability,false));var scope=scope(List.of(item("private-item",capability,"2",List.of(),List.of())),"private acceptance");
            var input=ScopedCostInputs.prepare(scope,specs,Map.of());var m=measured(scope,specs,"install",H,0);
            assertEquals(CapabilityQuoteSpec.MatchStatus.MATCHED,input.status());assertEquals(m,input.select(List.of(m)).orElseThrow());
            var group=input.group("scope-features-1",H,resolved(m)).orElseThrow();
            assertEquals(group.input(),input.input(resolved(m)).orElseThrow());
            assertEquals(List.of("small"),group.bucketIds());assertEquals(m.scopeShapeSha(),group.scopeShapeSha());
            assertEquals(resolved(m).configSemanticSha(),group.configSemanticSha());
            assertEquals("ScopedCostInputs[private]",input.toString());
        }
    }
    @Test void privateNamesAndInstallationsDoNotSplitSharedGroupsButActualContextDoes(){
        var specs=specs(spec("text.summary",false));
        var a=scope(List.of(item("first","text.summary","1",List.of(),List.of())),"first private acceptance");
        var b=scope(List.of(item("second","text.summary","2",List.of(),List.of())),"second private acceptance");
        var ia=ScopedCostInputs.prepare(a,specs,Map.of());var ib=ScopedCostInputs.prepare(b,specs,Map.of());
        String key=ia.group("f",H,resolved(measured(a,specs,"a",H,0))).orElseThrow().key();
        assertNotEquals(a.scopeSha256(),b.scopeSha256());
        assertEquals(key,ib.group("f",H,resolved(measured(b,specs,"b",H,0))).orElseThrow().key());
        assertNotEquals(key,ib.group("f",H,resolved(measured(b,specs,"b","b".repeat(64),0))).orElseThrow().key());
    }
    @Test void metadataMissingAndOutsideBucketRemainDistinctAndDoNotInventZeros(){
        var specs=specs(spec("document.extract",true));var scope=scope(List.of(item("a","document.extract","1",List.of("file@1"),List.of())),"deliver");
        var m=resolved(measured(scope,specs,"i",H,0));var missing=ScopedCostInputs.prepare(scope,specs,Map.of());
        assertEquals(CapabilityQuoteSpec.MatchStatus.COST_INPUT_MISSING,missing.status());assertTrue(missing.group("f",H,m).isEmpty());assertTrue(missing.input(m).isEmpty());
        var metadata=new CapabilityQuoteSpec.InputMetadata("file@1",1,"application/pdf",H,"meta-1","parser-1",Map.of("bytes",new Scalar(ValueType.INTEGER,"unit/bytes","4096")));
        var outside=ScopedCostInputs.prepare(scope,specs,Map.of("file@1",metadata));
        assertEquals(CapabilityQuoteSpec.MatchStatus.OUT_OF_DISTRIBUTION,outside.status());assertTrue(outside.group("f",H,m).isEmpty());assertTrue(outside.input(m).isEmpty());
    }
    @Test void missingCostMetadataCannotHideInvalidBusinessFieldsLaterInTheScope(){
        var specs=specs(spec("document.extract",true),spec("text.summary",false));
        var scope=scope(List.of(item("a","document.extract","1",List.of("file@1"),List.of()),item("b","text.summary","101",List.of(),List.of("a"))),"deliver");
        assertThrows(IllegalArgumentException.class,()->ScopedCostInputs.prepare(scope,specs,Map.of()));
    }
    @Test void combinationRequiresExactCompletePatternAndCannotUseUnionOfSingleProfiles(){
        var specs=specs(spec("text.summary",false),spec("document.extract",false));
        var first=item("a","document.extract","1",List.of(),List.of());var second=item("b","text.summary","1",List.of(),List.of("a"));
        var scope=scope(List.of(first,second),"deliver");var input=ScopedCostInputs.prepare(scope,specs,Map.of());
        var single=measured(scope(List.of(first),"single"),specs,"i",H,0);
        var other=measured(scope(List.of(item("b","text.summary","1",List.of(),List.of())),"single"),specs,"j",H,1);
        assertTrue(input.select(List.of(single,other)).isEmpty());
        var complete=measured(scope,specs,"k",H,2);assertEquals(complete,input.select(List.of(single,other,complete)).orElseThrow());
        var group=input.group("f",H,resolved(complete)).orElseThrow();assertEquals(2,group.bucketIds().size());
        assertThrows(IllegalArgumentException.class,()->input.group("f",H,resolved(single)));
        var independent=scope(List.of(first,item("b","text.summary","1",List.of(),List.of())),"deliver");
        assertNotEquals(group.key(),ScopedCostInputs.prepare(independent,specs,Map.of()).group("f",H,resolved(measured(independent,specs,"k",H,2))).orElseThrow().key());
    }
    @Test void selectionUsesPriorityNotAvailablePriceAndRejectsChangedCapabilitySemantics(){
        var spec=spec("text.summary",false);var specs=specs(spec);var scope=scope(List.of(item("a","text.summary","1",List.of(),List.of())),"deliver");
        var input=ScopedCostInputs.prepare(scope,specs,Map.of());var preferred=measured(scope,specs,"a",H,0);var other=measured(scope,specs,"b",H,1);
        assertEquals(preferred,input.select(List.of(other,preferred)).orElseThrow());
        var slot=preferred.scopePattern().getFirst();
        var wrong=new MeasuredCostProfile("wrong",1,0,List.of(new MeasuredCostProfile.Slot(0,slot.capabilityId(),"1",H,List.of(),slot.skillKey(),"1",H,"a")),"native-1","context",H,H,"capture-1",List.of(REF));
        assertThrows(IllegalArgumentException.class,()->input.select(List.of(wrong)));
        assertThrows(IllegalArgumentException.class,()->ScopedCostInputs.prepare(scope,Map.of(),Map.of()));
    }
}
