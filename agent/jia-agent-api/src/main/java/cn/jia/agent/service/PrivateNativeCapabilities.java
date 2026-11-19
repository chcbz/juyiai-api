package cn.jia.agent.service;

import java.util.*;
import java.util.function.*;

/** Preparation scopes immutable package metadata to this admission only. Freeze runs in the admission transaction. */
public interface PrivateNativeCapabilities {
    record Selection(List<Map<String,Object>> skills,Map<String,Object> catalog) {
        public static Selection empty(){return new Selection(List.of(),Map.of());}
    }
    <T> T prepared(SkillCapabilityLookup.Scope scope,String socket,boolean businessTools,Function<Supplier<Selection>,T> admission);
}
