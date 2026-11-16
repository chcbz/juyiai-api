package cn.jia.agent.service;

import java.util.List;
import java.util.Map;
import static cn.jia.agent.service.PersonalWorkspaceExecutionService.*;

/** Trusted application execution boundary; no browser-provided skill authority or fees. */
public interface SkillExecutionService {
    /** Isolated local workspace/file and artifact/result tools only. Never network, credentials or paid providers. */
    String NATIVE_LOCAL_FILES_POLICY = "NATIVE_LOCAL_FILES_V1";
    String NATIVE_SKILL_SCRIPT_POLICY = "NATIVE_SKILL_SCRIPT_V1";
    sealed interface ExecutionScope permits PrivateScope,TaskScope {
        String conversationId();
        long conversationGeneration();
        default ScopeView wire() {
            return new ScopeView(this instanceof TaskScope?"TASK":"PRIVATE",conversationId(),
                    Long.toString(conversationGeneration()),this instanceof TaskScope task?task.taskId():null);
        }
    }
    record PrivateScope(String conversationId,long conversationGeneration) implements ExecutionScope { }
    record TaskScope(String conversationId,long conversationGeneration,String taskId,String taskGrantId,
            long taskGrantVersion,long assignmentRevision) implements ExecutionScope { }
    record Create(String actionEventId,ExecutionScope scope,String targetAgentId,Map<String,Object> snapshot) { }
    record Creation(String executionId,String taskId,String runId,String conversationId,String targetAgentId,
            String state,String snapshotDigest) { }
    Creation create(OwnerScope scope,Create command);
    /** Trusted business caller cancels local admission. Already committed results are preserved;
     * unresolved external effects remain recorded, never declared undone or replayed. */
    String cancel(OwnerScope scope,String executionId);
    record Skill(String key,String version,String packageSha256,String manifestSha256) { }
    /** Protocol 6 only. Skills are exact managed bindings, never execution/Provider authority. */
    record AuthorizedSkill(Skill skill,Map<String,Object> executionBinding) { }
    /** Public identity only; task grant/assignment authority never leaks into the Runtime scope. */
    record ScopeView(String kind,String conversationId,String conversationGeneration,String taskId) {
        public Map<String,Object> asMap() {
            var value=new java.util.LinkedHashMap<String,Object>();
            value.put("kind",kind);value.put("conversationId",conversationId);
            value.put("conversationGeneration",conversationGeneration);value.put("taskId",taskId);
            return java.util.Collections.unmodifiableMap(value);
        }
    }
    record Command(int executionProtocolVersion,String executionId,String runId,ScopeView scope,
            String commandId,String messageId,List<AuthorizedSkill> skills,
            String snapshotDigest,Map<String,Object> parameters,String instruction,List<String> inputRefs,
            Map<String,Object> outputs,String toolPolicyRef) implements ConversationCommandView { }
    record EffectStart(String executionId,String snapshotDigest,ConversationFence fence,
            String effectDigest,String providerBindingRevision) { }
    record EffectResult(String executionId,String snapshotDigest,ConversationFence fence,
            String effectDigest,String providerBindingRevision,String status,String providerRequestId,String resultDigest) { }
    record EffectReceipt(int schemaVersion,String disposition,String status,String providerRequestId,String resultDigest) { }
    EffectReceipt startEffect(RuntimeScope scope,String executionId,String runId,String effectId,EffectStart command);
    EffectReceipt recordEffect(RuntimeScope scope,String executionId,String runId,String effectId,EffectResult command);
    record Inputs(int schemaVersion,String executionId,long leaseVersion,String snapshotDigest,
            boolean noReferencedMaterials,List<Map<String,Object>> inputs) { }
    Inputs inputs(RuntimeScope scope,String executionId,String runId,ConversationFence fence);
    RuntimeDownload inputContent(RuntimeScope scope,String executionId,String runId,String inputRef,ConversationFence fence);
    record Resume(String executionId,String snapshotDigest) { }
    record ResumeReceipt(ConversationLease lease,String phase,List<String> allowedWork,Map<String,Object> resultManifest,
            Map<String,Object> effects,String turnFailureCode) {
        public ResumeReceipt(ConversationLease lease,String phase,List<String> allowedWork,Map<String,Object> resultManifest,Map<String,Object> effects) {
            this(lease,phase,allowedWork,resultManifest,effects,null);
        }
        public ResumeReceipt(ConversationLease lease,String phase,List<String> allowedWork,Map<String,Object> resultManifest) {
            this(lease,phase,allowedWork,resultManifest,Map.of(),null);
        }
    }
    ResumeReceipt resume(RuntimeScope scope,String executionId,String runId,Resume command);
    record InputReceipt(String inputRef,String sha256,String carrier,String delivery) { }
    record ResultCommit(ConversationFence fence,String snapshotDigest,Object data,List<OutputDeclaration> artifacts,
            List<InputReceipt> inputReceipts,List<String> effectDependencies) {
        /** Source compatibility for internal callers only; v6 HTTP requires explicit dependencies. */
        public ResultCommit(ConversationFence fence,String snapshotDigest,Object data,List<OutputDeclaration> artifacts,
                List<InputReceipt> inputReceipts) { this(fence,snapshotDigest,data,artifacts,inputReceipts,List.of()); }
    }
    record ResultReceipt(String manifestId,String state,String phase,String resultDigest) { }
    ResultReceipt commitResult(RuntimeScope scope,String executionId,String runId,String manifestId,ResultCommit command);
    record ResultView(String executionId,String state,String phase,String lastErrorCode,String snapshotDigest,long executionStateVersion,
            Map<String,Object> resultManifest) { }
    ResultView result(OwnerScope scope,String executionId);
    /** Called by committed Chat projection in its mandatory transaction; not a runtime success claim. */
    void markProjected(OwnerScope scope,String executionId,String expectedResultDigest);
}
