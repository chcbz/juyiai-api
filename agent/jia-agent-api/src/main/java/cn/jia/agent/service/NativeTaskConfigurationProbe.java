package cn.jia.agent.service;

import java.util.concurrent.CompletionStage;

/** Internal, background-only observation. Null means unavailable, never zero cost or permission to execute.
 * The caller must recheck current authoritative revisions before quote use. Never call in a task transaction. */
public interface NativeTaskConfigurationProbe {
    record Measurement(String intentSha256, AgentManagedSessionLookup.SessionFence fence, String version,
                       String executionConfigSha256, String routeSha256, String toolConfigSha256, String capturePolicyVersion) {}
    CompletionStage<Measurement> measure(NativeTaskPreparation.Frozen frozen, AgentManagedSessionLookup.SessionFence fence);
}
