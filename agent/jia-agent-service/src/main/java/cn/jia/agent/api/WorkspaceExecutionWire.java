package cn.jia.agent.api;

import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.*;

/** Browser-safe historical facts; never serialize a retired execution command. */
public final class WorkspaceExecutionWire {
    private WorkspaceExecutionWire() { }
    private static String decimal(long v, boolean positive) {
        if (v < (positive ? 1 : 0)) throw new IllegalArgumentException("Invalid workspace integer");
        return Long.toString(v);
    }
    public record Input(String inputRef,String fileId,String version,String originalFilename,
            String contentMimeType,String byteLength,String sha256) { }
    public static Input from(PersonalWorkspaceExecutionService.RuntimeInput v) {
        return new Input(v.inputRef(),v.fileId(),decimal(v.version(),true),v.originalFilename(),v.contentMimeType(),decimal(v.byteLength(),false),v.sha256());
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Execution(String executionId,String taskId,String runId,String conversationId,String targetAgentId,
            String state,String failureCode,String failureMessage,String grantRevision,String outputContentMimeType,
            List<Input> inputs,Void runtimeCommand,String executionMode,String businessTaskId,String workItemId,String workItemState,
            @JsonInclude(JsonInclude.Include.NON_NULL) String recoveryStatus) { }
    public static Execution from(PersonalWorkspaceExecutionService.ExecutionView v) {
        if(v==null)return null;
        return new Execution(v.executionId(),v.taskId(),v.runId(),v.conversationId(),v.targetAgentId(),v.state(),v.failureCode(),v.failureMessage(),decimal(v.grantRevision(),true),v.outputContentMimeType(),v.inputs().stream().map(WorkspaceExecutionWire::from).toList(),null,v.executionMode(),v.businessTaskId(),v.workItemId(),v.workItemState(),v.recoveryStatus());
    }
}
