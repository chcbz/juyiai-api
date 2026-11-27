package cn.jia.agent.skill.quote;

import cn.jia.agent.requirement.ConfirmedScope.WorkItem;
import org.junit.jupiter.api.Test;
import java.util.*;
import cn.jia.economy.pricing.CostRouteReference;
import static org.junit.jupiter.api.Assertions.*;

class MeasuredCostProfileTest {
    private static final String H="a".repeat(64), OTHER="b".repeat(64);
    private static CostRouteReference route(String model) {return new CostRouteReference("MODEL","OUTPUT","namespace",model,"TOKENS_V1");}
    private static MeasuredCostProfile.Slot slot(int n,String cap,List<Integer> dependencies,String install,String pack) {
        return new MeasuredCostProfile.Slot(n,cap,"1",H,dependencies,"skill-"+cap,"1",pack,install);
    }
    private static MeasuredCostProfile profile(String id,int priority,List<MeasuredCostProfile.Slot> slots,List<CostRouteReference> routes,String context,String tools) {
        return new MeasuredCostProfile(id,1,priority,slots,"native-1","context-1",context,tools,"capture-1",routes);
    }
    private static MeasuredCostProfile single(String cap,int priority) {return profile(cap,priority,List.of(slot(0,cap,List.of(),"install",H)),List.of(route("model")),H,H);}
    private static WorkItem item(String id,String cap,List<String> deps) {return new WorkItem(id,cap,"1",Map.of(),Map.of(),List.of(),deps);}

    @Test void semanticIdentityExcludesInstallAndProfileIdentityButPrivateDigestBindsThem() {
        var a=single("summary",1);
        var b=profile("another",9,List.of(slot(0,"summary",List.of(),"another-install",H)),a.routeRefs(),H,H);
        assertEquals(a.measurementSemanticSha(),b.measurementSemanticSha());assertEquals(a.scopeShapeSha(),b.scopeShapeSha());assertNotEquals(a.measurementSha(),b.measurementSha());
        assertEquals("MeasuredCostProfile[private]",a.toString());
    }
    @Test void actualPackageContextToolAndRouteChangesCannotShareCostConfiguration() {
        var a=single("summary",1);
        for(var b:List.of(profile("summary",1,List.of(slot(0,"summary",List.of(),"install",OTHER)),a.routeRefs(),H,H),
                profile("summary",1,a.scopePattern(),a.routeRefs(),OTHER,H),profile("summary",1,a.scopePattern(),a.routeRefs(),H,OTHER),
                profile("summary",1,a.scopePattern(),List.of(route("different")),H,H)))assertNotEquals(a.measurementSemanticSha(),b.measurementSemanticSha());
    }
    @Test void exactWholeScopeMatchesDependenciesAndNotItemIdentityOrSingleSkillUnion() {
        var combined=profile("combo",2,List.of(slot(0,"extract",List.of(),"i1",H),slot(1,"summary",List.of(0),"i2",H)),List.of(route("model")),H,H);
        assertTrue(combined.matches(List.of(item("first","extract",List.of()),item("next","summary",List.of("first")))));
        assertTrue(combined.matches(List.of(item("x","extract",List.of()),item("y","summary",List.of("x")))));
        assertFalse(combined.matches(List.of(item("x","extract",List.of()),item("y","summary",List.of()))));
        assertFalse(combined.matches(List.of(item("x","summary",List.of()),item("y","extract",List.of("x")))));
        assertTrue(MeasuredCostProfile.select(List.of(single("extract",0),single("summary",1)),List.of(item("x","extract",List.of()),item("y","summary",List.of("x")))).isEmpty());
    }
    @Test void priorityIsAppliedOnlyAfterExactScopeMatching() {
        var matching=single("summary",9);var preferred=profile("preferred",8,matching.scopePattern(),matching.routeRefs(),H,H);
        assertEquals(preferred,MeasuredCostProfile.select(List.of(single("unrelated",0),matching,preferred),List.of(item("w","summary",List.of()))).orElseThrow());
    }
    @Test void canonicalRouteOrderingAndDependencySetsAreStable() {
        var slots=List.of(slot(0,"a",List.of(),"i0",H),slot(1,"b",List.of(),"i1",H),slot(2,"c",List.of(1,0),"i2",H));
        var a=profile("p",0,slots,List.of(route("z"),route("a")),H,H);
        var reordered=List.of(slots.get(0),slots.get(1),slot(2,"c",List.of(0,1),"i2",H));
        var b=profile("p",0,reordered,List.of(route("a"),route("z")),H,H);
        assertEquals(a.measurementSha(),b.measurementSha());assertEquals(a,b);
    }
    @Test void malformedAndUnboundedDeclarationsAreRejected() {
        var a=single("summary",1);
        assertThrows(IllegalArgumentException.class,()->MeasuredCostProfile.validateProfiles(List.of(a,a)));
        assertThrows(IllegalArgumentException.class,()->MeasuredCostProfile.validateProfiles(List.of(a,profile("other",1,a.scopePattern(),a.routeRefs(),H,H))));
        assertThrows(IllegalArgumentException.class,()->slot(0,"a",List.of(0),"i",H));
        assertThrows(IllegalArgumentException.class,()->slot(1,"a",List.of(0,0),"i",H));
        assertThrows(IllegalArgumentException.class,()->profile("p",0,List.of(slot(1,"a",List.of(),"i",H)),a.routeRefs(),H,H));
        assertThrows(IllegalArgumentException.class,()->profile("p",0,a.scopePattern(),List.of(route("model"),route("model")),H,H));
        assertThrows(IllegalArgumentException.class,()->profile("p",0,a.scopePattern(),a.routeRefs(),"label-not-measured",H));
        assertThrows(IllegalArgumentException.class,()->single("\ud800",0));
        assertThrows(IllegalArgumentException.class,()->MeasuredCostProfile.validateProfiles(Collections.nCopies(33,a)));
    }
}
