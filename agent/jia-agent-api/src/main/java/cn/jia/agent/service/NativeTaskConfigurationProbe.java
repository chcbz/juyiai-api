package cn.jia.agent.service;

import java.util.concurrent.CompletionStage;

/** Internal, background-only observation. Null means unavailable, never zero cost or permission to execute.
 * The caller must recheck current authoritative revisions before quote use. Never call in a task transaction. */
public interface NativeTaskConfigurationProbe {
    record Profile(long sequence,String profileSha256){public Profile{
        if(sequence<=0 || profileSha256!=null&&!profileSha256.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid prepared profile epoch");}}
    interface ProfileRecorder { void report(String tenant,String client,String agent,String provider,AgentManagedSessionLookup.SessionFence fence,Profile profile);
        /** A new runtime session generation, not a heartbeat, is needed after transport authority is lost. */
        default void retire(String tenant,String client,String agent,String provider,AgentManagedSessionLookup.SessionFence fence){
            report(tenant,client,agent,provider,fence,new Profile(Long.MAX_VALUE,null));
        }
    }
    record Measurement(String intentSha256, AgentManagedSessionLookup.SessionFence fence, String version,
                       String executionConfigSha256, String routeSha256, String toolConfigSha256, String capturePolicyVersion,Profile profile) {}
    CompletionStage<Measurement> measure(NativeTaskPreparation.Frozen frozen, AgentManagedSessionLookup.SessionFence fence);
}
