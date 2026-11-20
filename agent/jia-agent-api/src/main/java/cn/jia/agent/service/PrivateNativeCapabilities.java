package cn.jia.agent.service;

import java.util.*;
import java.util.function.*;

/** Preparation scopes immutable package metadata to this admission only. Freeze runs in the admission transaction. */
public interface PrivateNativeCapabilities {
    record Selection(List<Map<String,Object>> skills,String policy) {
        public static Selection empty(){return new Selection(List.of(),SkillExecutionService.NATIVE_LOCAL_FILES_POLICY);}
    }
    <T> T prepared(SkillCapabilityLookup.Scope scope,String socket,boolean domainSupported,Function<Supplier<Selection>,T> admission);
}
