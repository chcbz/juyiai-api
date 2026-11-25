package cn.jia.agent.dao;

import cn.jia.agent.entity.AgentIdentityAliasEntity;
import cn.jia.core.dao.IBaseDao;

public interface AgentIdentityAliasDao extends IBaseDao<AgentIdentityAliasEntity> {
    java.util.List<AgentIdentityAliasEntity> findActiveBindingAliases(String tenant, String client, String owner, java.util.Set<Long> ids);
    AgentIdentityAliasEntity findExactActiveLegacyAlias(String tenantId, String clientId,
            String ownerJiacn, String aliasValue);

    AgentIdentityAliasEntity findExactLegacyAlias(String tenantId, String clientId,
            String ownerJiacn, String aliasValue);
}
