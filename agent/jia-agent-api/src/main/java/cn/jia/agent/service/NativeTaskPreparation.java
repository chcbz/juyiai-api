package cn.jia.agent.service;

import java.util.*;

/** Dispatch-owned, explicit initial TASK/native/local-files intent. Not authority to run or spend.
 * An open-ended deliberation has no such intent. Never derive it from its later first action. */
public interface NativeTaskPreparation {
    enum Mode { ANSWER, ARTIFACTS }
    record Output(Mode mode,List<String> mimeTypes){
        public Output{Objects.requireNonNull(mode);mimeTypes=mode==Mode.ANSWER?List.of():List.copyOf(mimeTypes);
            if(mode==Mode.ARTIFACTS && (mimeTypes.isEmpty()||mimeTypes.stream().anyMatch(String::isBlank)))throw new IllegalArgumentException("Missing artifact contract");}
        public Map<String,Object> commandOutputs(){return mode==Mode.ARTIFACTS?Map.of("mimeTypes",mimeTypes,"multiple",true,"artifactsRequired",true):
            Map.of("mimeTypes",List.of(),"multiple",false,"artifactsRequired",false,"dataSchema",Map.of("$schema","https://json-schema.org/draft/2020-12/schema","type","object",
                "properties",Map.of("answer",Map.of("type","string","minLength",1,"pattern","\\S")),"required",List.of("answer"),"additionalProperties",false));}
        /** Runtime's comparison distinguishes explicitly absent data schema from an unresolved output contract. */
        public Map<String,Object> comparisonOutputs(){var result=new LinkedHashMap<>(commandOutputs());result.putIfAbsent("dataSchema",null);return Collections.unmodifiableMap(result);}
    }
    record Binding(AgentTaskExecutionGrantService.Scope scope,String task,long taskVersion,long requirementRevision,String requirementSha,String inputSha,AgentExternalServicePolicyService.QuoteTarget target){}
    record Intent(String schema,Binding binding,Output output){public Intent{if(!"TASK_NATIVE_LOCAL_FILES_V1".equals(schema))throw new IllegalArgumentException("Unknown dispatch intent");Objects.requireNonNull(binding);Objects.requireNonNull(output);}}
    record Frozen(String sha256,Intent intent){}
    /** Only a server task producer that has actually chosen a local-only initial execution may call this. */
    Frozen freeze(Intent intent);
    /** Internal exact-input lookup, not a browser capability. Absence is not an empty skill decision. */
    Frozen find(Binding binding);
    /** Prepare inputs for background comparisons. Read current authorized candidates and exact persisted decisions
     * at one task-root revision. Missing/stale/undecided candidates have no entry. No network or quote authority. */
    record Comparison(String ticket,Map<String,Frozen> decisions){public Comparison{decisions=Map.copyOf(decisions);}}
    /** Supersedes previous observation even when no reply follows. Call before transport I/O. */
    Comparison reserveComparisons(AgentTaskExecutionGrantService.Scope scope,String task,long expectedVersion,List<String> agentIds);
    /** No quote authority: records the first authenticated observation for the reserved comparison only. */
    boolean recordMeasurement(Comparison comparison,String agent,NativeTaskConfigurationProbe.Measurement measurement);
    /** Under caller's existing transaction: exact stored observation only, not a check of live registration or configuration drift. */
    record Recorded(String ticket,Frozen decision,NativeTaskConfigurationProbe.Measurement measurement) {}
    /** Authorized exact bindings only. Uses database time; no task or runtime network lookup. */
    Map<Binding,Recorded> recorded(AgentTaskExecutionGrantService.Scope scope,String task,Collection<Binding> bindings,boolean locking);
    record ReadBatch(Map<Binding,Recorded> current,Set<Binding> due){public ReadBatch{current=Map.copyOf(current);due=Set.copyOf(due);}}
    ReadBatch readBatch(AgentTaskExecutionGrantService.Scope scope,String task,Collection<Binding> bindings,boolean locking);
    Comparison reserveDue(Binding anchor,Set<Binding> expected);
    boolean lockMeasurement(String ticket,Frozen decision,NativeTaskConfigurationProbe.Measurement measurement);
    Map<String,Frozen> currentForProbes(AgentTaskExecutionGrantService.Scope scope,String task,long expectedVersion,List<String> agentIds);
}
