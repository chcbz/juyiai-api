package cn.jia.agent.security;

import cn.jia.agent.service.NativePricingProfiles;
import cn.jia.agent.service.AgentManagedSessionLookup.SessionFence;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** The caller's existing registration fence owns installation/identity/runtime locks, in that order. */
@Service
public class RegisteredPricingProfiles implements NativePricingProfiles {
    private final JdbcTemplate db;
    public RegisteredPricingProfiles(JdbcTemplate db){this.db=db;}
    private record Row(long id,boolean same,long sequence,String sha){}
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public void observe(String tenant,String client,String provider,String agent,SessionFence fence,Object wire){
        var rows=db.query("""
            SELECT id,COALESCE(BINARY pricing_installation_id=BINARY runtime_installation_id AND pricing_binding_id=binding_id
              AND pricing_generation=runtime_session_generation,0),pricing_sequence,pricing_config_sha FROM agent_runtime
            WHERE agent_id=? AND BINARY agent_id=BINARY ? AND BINARY tenant_id=BINARY ? AND BINARY client_id=BINARY ? AND BINARY owner_jiacn=BINARY ?
              AND binding_id IS NOT NULL AND BINARY runtime_installation_id=BINARY ? AND BINARY runtime_host_id=BINARY ?
              AND BINARY runtime_instance_id=BINARY ? AND runtime_session_generation=? FOR UPDATE
            """,(rs,i)->new Row(rs.getLong(1),rs.getBoolean(2),rs.getLong(3),rs.getString(4)),agent,agent,tenant,client,provider,fence.runtimeInstallationId(),fence.hostId(),fence.runtimeInstanceId(),fence.sessionGeneration());
        if(rows.size()!=1)throw new IllegalStateException("Registration fence changed");var row=rows.getFirst();
        Report report=null;if(wire!=null)try{report=NativePricingProfiles.parse(wire);}catch(IllegalArgumentException ignored){ /* withdraw: never persist unknown fields */ }
        // Unsequenced/malformed input may withdraw, never advance an epoch or revive an old revision.
        long seq=report!=null?report.sequence():row.same()?row.sequence():0;String sha=report==null?null:report.sha();String json=report==null?null:report.canonical();
        if(row.same()){
            if(seq<row.sequence() || seq==row.sequence() && Objects.equals(row.sha(),sha))return;
            if(seq==row.sequence()){if(row.sha()==null)return;sha=null;json=null;} // conflict sticky until next authoritative revision
        }
        db.update("""
            UPDATE agent_runtime SET pricing_installation_id=runtime_installation_id,pricing_binding_id=binding_id,
              pricing_generation=runtime_session_generation,pricing_sequence=?,pricing_config_sha=?,pricing_profiles_json=?,pricing_observed_at=UTC_TIMESTAMP(3) WHERE id=?
            """,seq,sha,json,row.id());
    }
    /** Caller has authorized these targets on this task. Offline status is not revocation of a configuration. */
    public record Observed(String agent,String provider,long binding,String installation,String host,String instance,long generation,long seq,String sha,String canonical){
        @Override public String toString(){return "RegisteredCostConfiguration[private]";}
    }
    public Map<String,Observed> read(String tenant,String client,List<cn.jia.agent.service.AgentExternalServicePolicyService.QuoteTarget> authorized,boolean lock){
        return readSubjects(tenant,client,authorized.stream().map(t->new Subject(t.agent(),t.provider(),t.binding())).toList(),lock);
    }
    private record Subject(String agent,String provider,long binding){}
    public Observed atDispatch(cn.jia.agent.service.TaskAssigneeAuthorityService.Target target,SessionFence fence){
        var row=readSubjects(target.tenantId(),target.clientId(),List.of(new Subject(target.agentId(),target.ownerJiacn(),target.bindingId())),true).get(target.agentId());
        return row!=null && row.installation().equals(fence.runtimeInstallationId()) && row.host().equals(fence.hostId()) && row.instance().equals(fence.runtimeInstanceId()) && row.generation()==fence.sessionGeneration()?row:null;
    }
    private Map<String,Observed> readSubjects(String tenant,String client,List<Subject> authorized,boolean lock){
        if(lock&&!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Missing quote transaction");
        var targets=new TreeMap<String,Subject>();
        for(var t:authorized)if(targets.put(t.agent(),t)!=null)throw new IllegalArgumentException("Duplicate candidate");
        var ids=List.copyOf(targets.keySet());var result=new HashMap<String,Observed>();
        for(int i=0;i<ids.size();i+=128){var page=ids.subList(i,Math.min(ids.size(),i+128));var params=new ArrayList<Object>(List.of(tenant,client));params.addAll(page);
            try{
                var rows=db.query("""
                    SELECT r.agent_id,r.owner_jiacn,r.binding_id,r.runtime_installation_id,r.runtime_host_id,r.runtime_instance_id,r.runtime_session_generation,r.pricing_sequence,r.pricing_config_sha,r.pricing_profiles_json
                    FROM agent_runtime r JOIN agent_runtime_v1_installation n ON n.installation_id=r.runtime_installation_id
                      AND BINARY n.installation_id=BINARY r.runtime_installation_id AND BINARY n.canonical_agent_id=BINARY r.agent_id
                      AND BINARY n.tenant_id=BINARY r.tenant_id AND BINARY n.client_id=BINARY r.client_id AND BINARY n.status='ACTIVE'
                    WHERE BINARY r.tenant_id=BINARY ? AND BINARY r.client_id=BINARY ? AND r.pricing_config_sha IS NOT NULL
                      AND BINARY r.pricing_installation_id=BINARY r.runtime_installation_id AND r.pricing_generation=r.runtime_session_generation
                      AND r.pricing_binding_id=r.binding_id AND r.agent_id IN (
                    """+String.join(",",Collections.nCopies(page.size(),"?"))+") ORDER BY r.agent_id"+(lock?" FOR SHARE NOWAIT":""),
                    (rs,k)->new Observed(rs.getString(1),rs.getString(2),rs.getLong(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getLong(7),rs.getLong(8),rs.getString(9),rs.getString(10)),params.toArray());
                for(var row:rows){var t=targets.get(row.agent());if(t==null||!row.provider().equals(t.provider())||row.binding()!=t.binding())continue;
                    if(row.canonical()==null || !row.sha().equals(NativePricingProfiles.digest(row.canonical())))throw new IllegalStateException("Invalid stored pricing configuration");
                    result.put(row.agent(),row);}
            }catch(org.springframework.dao.DataAccessException failure){for(Throwable c=failure;c!=null;c=c.getCause())if(lock&&c instanceof java.sql.SQLException e&&e.getErrorCode()==3572)throw new PreparedRuntimeEpochFence.Busy();throw failure;}
        }
        return Map.copyOf(result);
    }
}
