package cn.jia.agent.service;

import java.util.Map;

/** Archive domain projection; caller supplies an execution-authorized owner and agent. */
public interface ArchiveExecutionJobLookup {
    Map<String,Object> readJob(PositionAuthority.Scope scope, String agentId, String jobId);
}
