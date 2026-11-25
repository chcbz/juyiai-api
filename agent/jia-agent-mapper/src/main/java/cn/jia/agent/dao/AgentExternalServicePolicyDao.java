package cn.jia.agent.dao;

import cn.jia.agent.entity.*;
import java.util.List;
public interface AgentExternalServicePolicyDao {
    AgentExternalServicePolicyEntity latestForUpdate(String tenant,String client,String agent,long binding);
    AgentExternalServicePolicyEntity receiptForUpdate(String tenant,String client,String owner,String key);
    AgentExternalServicePolicyEntity latest(String tenant, String client, String agent, long binding);
    AgentExternalServicePolicyEntity receipt(String tenant, String client, String owner, String key);
    AgentIdentityRegistryEntity identity(String tenant, String client, String agent);
    /** Task picker projection: no runtime credentials, private abilities or provider login in display fields. */
    record CandidateRow(String agentId, String ownerJiacn, String displayName, String status) { }
    List<CandidateRow> candidates(String tenant, String client, String owner, String after, int limit);
    record QuoteIdentity(String agent,String provider,long binding,Long revision){}
    List<QuoteIdentity> selected(String tenant,String client,String owner,List<String> ids,boolean locking);
    void insert(AgentExternalServicePolicyEntity policy);
}
