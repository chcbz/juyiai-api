package cn.jia.agent.skill.quote;

import cn.jia.core.util.CanonicalValueEncoding;
import java.util.*;

/** One bounded declaration revision in an existing authenticated runtime session. Not a session fence. */
public record CostProfileReport(long sequence,List<MeasuredCostProfile> profiles) {
    public CostProfileReport {
        if(sequence<=0)throw new IllegalArgumentException("Invalid cost profile sequence");
        profiles=MeasuredCostProfile.validateProfiles(profiles);
    }
    public Map<String,Object> configuration() {
        return Map.of("schema",MeasuredCostProfile.SCHEMA,"profiles",profiles.stream().map(profile->{
            var value=new HashMap<>(profile.canonical());value.put("measurementSha",profile.measurementSha());return Map.copyOf(value);
        }).toList());
    }
    /** Sequence is an ordering fence, not cost semantics. An empty profile list explicitly withdraws cost evidence. */
    public String sha256() {return CanonicalValueEncoding.digest(configuration());}
    public Map<String,Object> wire() {
        var value=new HashMap<>(configuration());value.put("sequence",Long.toString(sequence));value.put("sha256",sha256());return Map.copyOf(value);
    }
    @Override public String toString(){return "CostProfileReport[private]";}
}
