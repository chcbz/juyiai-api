package cn.jia.agent.service;

import java.util.List;

/** Owner-scoped historical reads and the current native runtime lease/output boundary. */
public interface PersonalWorkspaceExecutionService {
    /** Stable owner-scoped identity for the zero-input text action and its immutable Chat link. */
    static String textFileExecutionId(OwnerScope scope, String intentId) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            for (String value : List.of("conversation-text-file-v1", scope.tenantId(), scope.clientId(),
                    scope.ownerJiacn(), intentId)) {
                byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return "pwe_" + java.util.HexFormat.of().formatHex(digest.digest()).substring(0, 60);
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    /** Trusted Chat consumer obtains exact bytes only after owner/grant re-admission; not a public URL. */
    ConversationOutput readConversationOutput(OwnerScope scope, String executionId, String runId, String outputId);
    /** Verified descriptor; caller closes it. File I/O does not hold this service's root locks. */
    ConversationDownload openConversationOutput(OwnerScope scope, String executionId, String runId, String outputId);

    record ConversationDownload(String executionId, String outputId, String originalFilename,
            String contentMimeType, String sha256, long byteLength, java.io.InputStream content)
            implements AutoCloseable {
        @Override public void close() throws java.io.IOException { content.close(); }
    }

    /** Committed conversation-only outputs whose bytes were checked under the same owner/grant fence. */
    List<ConversationOutputInfo> listConversationOutputs(OwnerScope scope, String executionId, String runId);
    /** Exact earlier result replaced by this edit, never an input/reference used for generation. */
    record OutputReplacement(String requestId, String stepId, String outputId, String sha256) { }
    record ConversationOutputInfo(String executionId, String outputId, String contentMimeType,
            String sha256, long byteLength, OutputReplacement replaces, String filename) {
        public ConversationOutputInfo(String executionId, String outputId, String contentMimeType,
                String sha256, long byteLength, OutputReplacement replaces) {
            this(executionId, outputId, contentMimeType, sha256, byteLength, replaces, "");
        }
        public ConversationOutputInfo(String executionId, String outputId, String contentMimeType,
                String sha256, long byteLength) {
            this(executionId, outputId, contentMimeType, sha256, byteLength, null);
        }
    }
    /** Native runtime inbox; intentionally separate from legacy unfenced commands. */
    List<? extends ConversationCommandView> runtimeConversationCommandViews(RuntimeScope scope,int limit);
    sealed interface ConversationCommandView permits SkillExecutionService.Command { }
    /** Trusted native runtime only; lease token is never sent to browsers or legacy inbox. */
    ConversationLease claimConversationStart(RuntimeScope scope, String executionId, String runId,
            String commandId, String messageId);
    ConversationLease renewConversationLease(RuntimeScope scope, String executionId, String runId, ConversationFence fence);
    StagedOutput stageConversationOutput(RuntimeScope scope, String executionId, String runId, ConversationFence fence,
            String outputId, String filename, String contentMimeType, byte[] content);
    StagedOutput stageConversationOutputStream(RuntimeScope scope, String executionId, String runId,
            ConversationFence fence, String outputId, String filename, String contentMimeType,
            PersonalWorkspaceService.UploadSource content, long byteLength, String sha256);
    record RuntimeFailure(String executionId,String runId,SkillExecutionService.ScopeView scope,
            String targetAgentId,String state,String failureCode) { }
    RuntimeFailure failConversation(RuntimeScope scope, String executionId, String runId, ConversationFence fence,
            String code);
    /** Never put these values in a browser response, ordinary queue payload or logs. */
    record ConversationFence(long version, String token) { }
    record ConversationLease(String executionId, long version, String token, long expiresAt) {
        public ConversationFence fence() { return new ConversationFence(version, token); }
    }
    record ConversationOutput(String executionId, String outputId, String originalFilename,
            String contentMimeType, String sha256, long byteLength, byte[] bytes) {
        public ConversationOutput { bytes = bytes == null ? null : bytes.clone(); }
        @Override public byte[] bytes() { return bytes == null ? null : bytes.clone(); }
    }

    /** Server-side native admission capabilities; never Provider authority. */
    default ExecutionCapabilities nativeLocalFileCapabilities() { return new ExecutionCapabilities(List.of(),List.of(),false); }
    /** Bounded, newest-first owner history. The summary is an explicit browser-safe allow-list. */
    ExecutionHistoryView list(OwnerScope scope, int limit, Long beforeCreatedAt,
            String beforeExecutionId);
    /** Read-only reconciliation of an already persisted create request. */
    ExecutionView getByIdempotencyKey(OwnerScope scope, String idempotencyKey);
    ExecutionView get(OwnerScope scope, String executionId);
    record OwnerScope(String tenantId, String clientId, String ownerJiacn) { }
    /** ownerJiacn is the data scope; executorOwnerJiacn always remains the native authenticated subject.
     * The five-argument constructor is the authentication boundary, before resolving a persisted run. */
    record RuntimeScope(String tenantId, String clientId, String ownerJiacn, String agentId,
                        String runtimeInstanceId, String executorOwnerJiacn) {
        public RuntimeScope(String tenantId,String clientId,String ownerJiacn,String agentId,String runtimeInstanceId) {
            this(tenantId,clientId,ownerJiacn,agentId,runtimeInstanceId,ownerJiacn);
        }
    }
    record ExecutionSummary(String executionId, String targetAgentId, String state,
            String outputContentMimeType, long createdAt) { }
    record ExecutionCursor(long createdAt, String executionId) { }
    record ExecutionHistoryView(List<ExecutionSummary> items, ExecutionCursor nextCursor) {
        public ExecutionHistoryView {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }
    record RuntimeInput(String inputRef, String fileId, int version, String originalFilename,
            String contentMimeType, long byteLength, String sha256) { }
    /** Browser-safe state projection. Task lease credentials and storage URIs are intentionally absent. */
    record ExecutionView(String executionId, String taskId, String runId, String conversationId,
            String targetAgentId, String state, String failureCode, String failureMessage, long grantRevision,
            String outputContentMimeType, List<RuntimeInput> inputs, Void runtimeCommand,
            String executionMode, String businessTaskId, String workItemId, String workItemState,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            String recoveryStatus) {
        /** Existing adapters retain their original constructor and wire semantics. */
        public ExecutionView(String executionId, String taskId, String runId, String conversationId,
                String targetAgentId, String state, String failureCode, String failureMessage, long grantRevision,
                String outputContentMimeType, List<RuntimeInput> inputs, Void runtimeCommand,
                String executionMode, String businessTaskId, String workItemId, String workItemState) {
            this(executionId, taskId, runId, conversationId, targetAgentId, state, failureCode,
                    failureMessage, grantRevision, outputContentMimeType, inputs, runtimeCommand,
                    executionMode, businessTaskId, workItemId, workItemState, null);
        }
        /** Source-compatible private-mode constructor for existing adapters and clients. */
        public ExecutionView(String executionId, String taskId, String runId, String conversationId,
                String targetAgentId, String state, String failureCode, String failureMessage, long grantRevision,
                String outputContentMimeType, List<RuntimeInput> inputs, Void runtimeCommand) {
            this(executionId, taskId, runId, conversationId, targetAgentId, state, failureCode,
                    failureMessage, grantRevision, outputContentMimeType, inputs, runtimeCommand,
                    "PRIVATE", null, null, null);
        }
    }
    record ExecutionCapabilities(List<String> allowedMimeTypes, List<String> inputMimeTypes,
            boolean generationEnabled) {
        public ExecutionCapabilities {
            allowedMimeTypes = allowedMimeTypes == null ? List.of() : List.copyOf(allowedMimeTypes);
            inputMimeTypes = inputMimeTypes == null ? List.of() : List.copyOf(inputMimeTypes);
        }
    }
    /** Verified binary source; ownership of this descriptor transfers to the caller. */
    record RuntimeDownload(String filename,String contentMimeType,String sha256,long byteLength,
            java.io.InputStream content) implements AutoCloseable {
        @Override public void close() throws java.io.IOException { content.close(); }
    }
    record RuntimeContent(String filename, String contentMimeType, byte[] bytes) {
        public RuntimeContent { bytes = bytes == null ? null : bytes.clone(); }
        @Override public byte[] bytes() { return bytes == null ? null : bytes.clone(); }
    }
    record StagedOutput(String outputId, String sha256, long byteLength, String state) { }
    record OutputDeclaration(String outputId, String sha256, long byteLength) { }
    final class Failure extends RuntimeException {
        private final Reason reason;
        public Failure(Reason reason) { super(reason.name()); this.reason = reason; }
        public Failure(Reason reason, Throwable cause) { super(reason.name(), cause); this.reason = reason; }
        public Reason getReason() { return reason; }
    }
    enum Reason {
        BAD_REQUEST, NOT_FOUND, IDEMPOTENCY_CONFLICT, GRANT_CHANGED, GRANT_REVOKED,
        OUTPUT_CONFLICT, OUTPUT_MISSING, CAPABILITY_UNAVAILABLE, STORAGE_UNAVAILABLE,
        TASK_CONFLICT
    }
}
