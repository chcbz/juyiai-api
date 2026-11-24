package cn.jia.agent.service;

import cn.jia.agent.entity.AgentTaskMetaEntity;
import java.util.function.Function;

/** Reads under a shared owner-scoped root lock, never an admission for writes or lock upgrades. */
public interface AgentTaskRootObservation {
    <T> T observe(String tenantId,String clientId,String ownerJiacn,String taskId,Function<AgentTaskMetaEntity,T> read);
}
