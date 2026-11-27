package cn.jia.agent.skill.quote;

import cn.jia.agent.requirement.ConfirmedScope.WorkItem;
import cn.jia.core.util.CanonicalValueEncoding;
import cn.jia.economy.pricing.CostRouteReference;
import java.util.*;

/** A measured configuration, not execution authority or a price. Installation/spec checks belong to Agent service. */
public record MeasuredCostProfile(String profileId, long revision, int selectionPriority,
        List<Slot> scopePattern, String executionStrategyVersion, String contextPolicyId,
        String contextPolicyVersion, String toolPolicyVersion, String capturePolicyVersion, List<CostRouteReference> routeRefs) {
    public static final String SCHEMA="COST_PROFILES_V1";
    public static final int MAX_PROFILES=32, MAX_SLOTS=128, MAX_ROUTES=128;

    public MeasuredCostProfile {
        id(profileId,128);require(revision>0 && selectionPriority>=0,"Invalid profile revision/priority");
        var configuration=new CostConfigurationObservation(executionStrategyVersion,contextPolicyId,contextPolicyVersion,toolPolicyVersion,capturePolicyVersion,routeRefs);
        scopePattern=List.copyOf(Objects.requireNonNull(scopePattern));
        require(!scopePattern.isEmpty() && scopePattern.size()<=MAX_SLOTS,"Invalid profile scope size");
        for(int i=0;i<scopePattern.size();i++)require(scopePattern.get(i).slot()==i,"Scope slots must be ordered and contiguous");
        routeRefs=configuration.routeRefs();
    }

    public record Slot(int slot,String capabilityId,String capabilityVersion,String specSha,List<Integer> dependsOn,
            String skillKey,String skillVersion,String packageSha,String installId) {
        public Slot {
            require(slot>=0 && slot<MAX_SLOTS,"Invalid scope slot");id(capabilityId,128);id(capabilityVersion,64);sha(specSha);
            id(skillKey,128);id(skillVersion,64);sha(packageSha);id(installId,128);
            dependsOn=List.copyOf(Objects.requireNonNull(dependsOn));
            require(new HashSet<>(dependsOn).size()==dependsOn.size(),"Duplicate slot dependency");
            for(int dependency:dependsOn)require(dependency>=0 && dependency<slot,"Dependency must precede its slot");
            dependsOn=dependsOn.stream().sorted().toList();
        }
        public Map<String,Object> shape() {
            return Map.of("slot",Integer.toString(slot),"capabilityId",capabilityId,"capabilityVersion",capabilityVersion,
                    "specSha",specSha,"dependsOn",dependsOn.stream().map(Object::toString).toList());
        }
        public Map<String,Object> canonical() {
            var result=new HashMap<>(shape());result.put("skillKey",skillKey);result.put("skillVersion",skillVersion);
            result.put("packageSha",packageSha);result.put("installId",installId);return Map.copyOf(result);
        }
    }

    /** Inputs have already passed confirmation validation; item IDs are mapped to ordinal slots, never put in a cost identity. */
    public boolean matches(List<WorkItem> items) {
        if(items==null || items.size()!=scopePattern.size())return false;
        var ordinals=new HashMap<String,Integer>();
        for(int i=0;i<items.size();i++) {
            var item=items.get(i);var slot=scopePattern.get(i);
            if(ordinals.containsKey(item.itemId()) || !slot.capabilityId().equals(item.capabilityId())
                    || !slot.capabilityVersion().equals(item.capabilityVersion()))return false;
            var dependencies=new ArrayList<Integer>();
            for(String id:item.dependsOn()) {var ordinal=ordinals.get(id);if(ordinal==null)return false;dependencies.add(ordinal);}
            dependencies.sort(Integer::compareTo);if(!slot.dependsOn().equals(dependencies))return false;
            ordinals.put(item.itemId(),i);
        }
        return true;
    }

    /** Caller supplies only profiles whose actual installations/spec bindings are valid. Never selects a cheaper non-executed plan. */
    public static Optional<MeasuredCostProfile> select(List<MeasuredCostProfile> eligible,List<WorkItem> items) {
        var profiles=validateProfiles(eligible);
        return profiles.stream().filter(p->p.matches(items)).min(Comparator.comparingInt(MeasuredCostProfile::selectionPriority));
    }
    public static List<MeasuredCostProfile> validateProfiles(List<MeasuredCostProfile> profiles) {
        require(profiles!=null && profiles.size()<=MAX_PROFILES,"Too many cost profiles");
        var ids=new HashSet<String>();var priorities=new HashSet<Integer>();
        for(var profile:profiles)require(profile!=null && ids.add(profile.profileId()) && priorities.add(profile.selectionPriority()),"Duplicate profile ID/priority");
        return profiles.stream().sorted((a,b)->CanonicalValueEncoding.compareUtf8(a.profileId(),b.profileId())).toList();
    }

    public List<Map<String,Object>> scopeShape() {return scopePattern.stream().map(Slot::shape).toList();}
    public String scopeShapeSha() {return CanonicalValueEncoding.digest(scopeShape());}
    public Map<String,Object> compatibleMeasurement() {
        // Include package content independently of install IDs. A producer cannot accidentally collapse changed bytes using a stale policy label.
        var packages=scopePattern.stream().map(s->Map.of("skillKey",s.skillKey(),"skillVersion",s.skillVersion(),"packageSha",s.packageSha()))
                .distinct().sorted((a,b)->{for(String k:List.of("skillKey","skillVersion","packageSha")){int c=CanonicalValueEncoding.compareUtf8(a.get(k),b.get(k));if(c!=0)return c;}return 0;}).toList();
        var result=new HashMap<>(configuration().canonical());result.put("skillPackages",packages);return Map.copyOf(result);
    }
    public CostConfigurationObservation configuration(){return new CostConfigurationObservation(executionStrategyVersion,contextPolicyId,contextPolicyVersion,toolPolicyVersion,capturePolicyVersion,routeRefs);}
    public String measurementSemanticSha() {return CanonicalValueEncoding.digest(compatibleMeasurement());}
    public Map<String,Object> canonical() {
        var result=new HashMap<>(compatibleMeasurement());result.remove("skillPackages");
        result.put("profileId",profileId);result.put("revision",Long.toString(revision));result.put("selectionPriority",Integer.toString(selectionPriority));
        result.put("scopePattern",scopePattern.stream().map(Slot::canonical).toList());return Map.copyOf(result);
    }
    public String measurementSha() {return CanonicalValueEncoding.digest(canonical());}
    @Override public String toString(){return "MeasuredCostProfile[private]";}
    private static void id(String value,int max) {require(value!=null && !value.isBlank() && value.equals(value.strip()) && value.codePointCount(0,value.length())<=max,"Invalid cost identity");CanonicalValueEncoding.utf8(value);}
    private static void sha(String value) {require(value!=null && value.matches("[0-9a-f]{64}"),"Invalid configuration digest");}
    private static void require(boolean valid,String message) {if(!valid)throw new IllegalArgumentException(message);}
}
