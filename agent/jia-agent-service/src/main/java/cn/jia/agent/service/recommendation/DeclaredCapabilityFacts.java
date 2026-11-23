package cn.jia.agent.service.recommendation;

import java.util.List;
import java.util.Optional;

/** Internal fact read, not a task grant or an HTTP candidate-discovery endpoint. */
public interface DeclaredCapabilityFacts {
    record Snapshot(String tenant,String client,String owner,String agent,List<String> abilities){
        public Snapshot { abilities=List.copyOf(abilities); }
    }
    Optional<Snapshot> declaredCapabilities(String tenant,String client,long runtimeId);
    interface Changes { void changed(String tenant,String client,long runtimeId); }
}
