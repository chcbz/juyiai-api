package cn.jia.agent.skill.quote;

import cn.jia.agent.requirement.ConfirmedScope.WorkItem;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CostProfileTest {
    private static final String H="a".repeat(64), OTHER="b".repeat(64);
    private static CostProfile.Route route(String model) {return new CostProfile.Route("MODEL","OUTPUT","channel",model,"default","TOKENS_V1");}
    private static CostProfile.Slot slot(int n,String cap,List<Integer> dependencies,String install,String pack) {
        return new CostProfile.Slot(n,cap,"1",H,dependencies,"skill-"+cap,"1",pack,install);
    }
    private static CostProfile profile(String id,int priority,List<CostProfile.Slot> slots,List<CostProfile.Route> routes,String context,String tools) {
        return new CostProfile(id,1,priority,slots,"native-1","context-1",context,tools,"capture-1",routes);
    }
    private static CostProfile single(String cap,int priority) {return profile(cap,priority,List.of(slot(0,cap,List.of(),"install",H)),List.of(route("model")),H,H);}
    private static WorkItem item(String id,String cap,List<String> deps) {return new WorkItem(id,cap,"1",Map.of(),Map.of(),List.of(),deps);}

    @Test void semanticIdentityExcludesInstallAndProfileIdentityButPrivateDigestBindsThem() {
        var a=single("summary",1);
        var b=profile("another",9,List.of(slot(0,"summary",List.of(),"another-install",H)),a.routes(),H,H);
        assertEquals(a.configSemanticSha(),b.configSemanticSha());assertEquals(a.scopeShapeSha(),b.scopeShapeSha());assertNotEquals(a.configSha(),b.configSha());
        assertEquals("CostProfile[private]",a.toString());
    }
    @Test void actualPackageContextToolAndRouteChangesCannotShareCostConfiguration() {
        var a=single("summary",1);
        for(var b:List.of(profile("summary",1,List.of(slot(0,"summary",List.of(),"install",OTHER)),a.routes(),H,H),
                profile("summary",1,a.scopePattern(),a.routes(),OTHER,H),profile("summary",1,a.scopePattern(),a.routes(),H,OTHER),
                profile("summary",1,a.scopePattern(),List.of(route("different")),H,H)))assertNotEquals(a.configSemanticSha(),b.configSemanticSha());
    }
    @Test void exactWholeScopeMatchesDependenciesAndNotItemIdentityOrSingleSkillUnion() {
        var combined=profile("combo",2,List.of(slot(0,"extract",List.of(),"i1",H),slot(1,"summary",List.of(0),"i2",H)),List.of(route("model")),H,H);
        assertTrue(combined.matches(List.of(item("first","extract",List.of()),item("next","summary",List.of("first")))));
        assertTrue(combined.matches(List.of(item("x","extract",List.of()),item("y","summary",List.of("x")))));
        assertFalse(combined.matches(List.of(item("x","extract",List.of()),item("y","summary",List.of()))));
        assertFalse(combined.matches(List.of(item("x","summary",List.of()),item("y","extract",List.of("x")))));
        assertTrue(CostProfile.select(List.of(single("extract",0),single("summary",1)),List.of(item("x","extract",List.of()),item("y","summary",List.of("x")))).isEmpty());
    }
    @Test void priorityIsAppliedOnlyAfterExactScopeMatching() {
        var matching=single("summary",9);var preferred=profile("preferred",8,matching.scopePattern(),matching.routes(),H,H);
        assertEquals(preferred,CostProfile.select(List.of(single("unrelated",0),matching,preferred),List.of(item("w","summary",List.of()))).orElseThrow());
    }
    @Test void canonicalRouteOrderingAndDependencySetsAreStable() {
        var slots=List.of(slot(0,"a",List.of(),"i0",H),slot(1,"b",List.of(),"i1",H),slot(2,"c",List.of(1,0),"i2",H));
        var a=profile("p",0,slots,List.of(route("z"),route("a")),H,H);
        var reordered=List.of(slots.get(0),slots.get(1),slot(2,"c",List.of(0,1),"i2",H));
        var b=profile("p",0,reordered,List.of(route("a"),route("z")),H,H);
        assertEquals(a.configSha(),b.configSha());assertEquals(a,b);
    }
    @Test void malformedAndUnboundedDeclarationsAreRejected() {
        var a=single("summary",1);
        assertThrows(IllegalArgumentException.class,()->CostProfile.validateProfiles(List.of(a,a)));
        assertThrows(IllegalArgumentException.class,()->CostProfile.validateProfiles(List.of(a,profile("other",1,a.scopePattern(),a.routes(),H,H))));
        assertThrows(IllegalArgumentException.class,()->slot(0,"a",List.of(0),"i",H));
        assertThrows(IllegalArgumentException.class,()->slot(1,"a",List.of(0,0),"i",H));
        assertThrows(IllegalArgumentException.class,()->profile("p",0,List.of(slot(1,"a",List.of(),"i",H)),a.routes(),H,H));
        assertThrows(IllegalArgumentException.class,()->profile("p",0,a.scopePattern(),List.of(route("model"),route("model")),H,H));
        assertThrows(IllegalArgumentException.class,()->profile("p",0,a.scopePattern(),a.routes(),"label-not-measured",H));
        assertThrows(IllegalArgumentException.class,()->single("\ud800",0));
        assertThrows(IllegalArgumentException.class,()->CostProfile.validateProfiles(Collections.nCopies(33,a)));
    }
}
