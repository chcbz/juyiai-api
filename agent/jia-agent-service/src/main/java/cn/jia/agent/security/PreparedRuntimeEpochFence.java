package cn.jia.agent.security;

import cn.jia.agent.service.AgentManagedSessionLookup.SessionFence;
import java.util.*;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Guards the *persisted epoch*, not authentication or live toolchain state. Never accepts a token or contacts a runtime.
 * A task transaction can already hold the task/identity; runtime ingress can hold installation/runtime before a task.
 * Do not wait on those latter locks in reverse order. Only MySQL NOWAIT conflict is a stale observation. */
@Component
public final class PreparedRuntimeEpochFence implements cn.jia.agent.service.NativeTaskConfigurationProbe.ProfileRecorder {
    public record Epoch(String agent,String provider,long binding,SessionFence fence,cn.jia.agent.service.NativeTaskConfigurationProbe.Profile profile) {}
    private final JdbcTemplate db;
    public PreparedRuntimeEpochFence(JdbcTemplate db){this.db=db;}
    /** Authenticated transport supplies the scope; a replaced session cannot republish. Same-sequence contradictions withdraw
     * irreversibly for that sequence. MySQL single-table SET expressions use left-to-right assignment order. */
    public void report(String tenant,String client,String agent,String provider,SessionFence f,cn.jia.agent.service.NativeTaskConfigurationProbe.Profile p){
        db.update("""
            UPDATE agent_runtime SET prepared_profile_sha=CASE WHEN prepared_session_generation=runtime_session_generation AND prepared_profile_sequence=? THEN NULL ELSE ? END,
            prepared_profile_sequence=?,prepared_session_generation=runtime_session_generation
            WHERE agent_id=? AND BINARY agent_id=BINARY ? AND BINARY tenant_id=BINARY ? AND BINARY client_id=BINARY ? AND BINARY owner_jiacn=BINARY ?
              AND BINARY runtime_installation_id=BINARY ? AND BINARY runtime_host_id=BINARY ? AND BINARY runtime_instance_id=BINARY ? AND runtime_session_generation=?
              AND (prepared_session_generation IS NULL OR prepared_session_generation<>runtime_session_generation OR prepared_profile_sequence<?
                OR (prepared_profile_sequence=? AND prepared_profile_sha IS NOT NULL AND NOT(prepared_profile_sha <=> ?)))
            """,p.sequence(),p.profileSha256(),p.sequence(),agent,agent,tenant,client,provider,f.runtimeInstallationId(),f.hostId(),f.runtimeInstanceId(),f.sessionGeneration(),p.sequence(),p.sequence(),p.profileSha256());
    }
    public boolean lock(String tenant,String client,Collection<Epoch> epochs){
        try{return matching(tenant,client,epochs,true).size()==epochs.size();}catch(Busy conflict){return false;}
    }
    /** NOWAIT is a transient change, not evidence that a candidate lacks a configuration. */
    public static final class Busy extends RuntimeException {}
    public Set<String> matching(String tenant,String client,Collection<Epoch> epochs,boolean locking){
        if(locking&&!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Missing quote transaction");
        var expected=new TreeMap<String,Epoch>();for(var epoch:epochs)if(expected.putIfAbsent(epoch.agent(),epoch)!=null)throw new IllegalArgumentException("Duplicate epoch candidate");
        var ids=List.copyOf(expected.keySet());var matches=new HashSet<String>();
        for(int i=0;i<ids.size();i+=128){var part=ids.subList(i,Math.min(i+128,ids.size()));
            var args=new ArrayList<Object>(List.of(tenant,client));args.addAll(part);
            try {
                var observed=db.query("""
                    SELECT r.agent_id,r.owner_jiacn,r.binding_id,r.runtime_installation_id,r.runtime_host_id,r.runtime_instance_id,r.runtime_session_generation,r.prepared_profile_sequence,r.prepared_profile_sha
                    FROM agent_runtime r JOIN agent_runtime_v1_installation n ON n.installation_id=r.runtime_installation_id
                    AND BINARY n.installation_id=BINARY r.runtime_installation_id AND BINARY n.tenant_id=BINARY r.tenant_id
                    AND BINARY n.client_id=BINARY r.client_id AND BINARY n.canonical_agent_id=BINARY r.agent_id
                    WHERE BINARY r.tenant_id=BINARY ? AND BINARY r.client_id=BINARY ? AND BINARY n.status='ACTIVE'
                    AND r.prepared_session_generation=r.runtime_session_generation AND r.prepared_profile_sequence>0 AND r.prepared_profile_sha IS NOT NULL
                    AND BINARY r.status IN ('online','busy') AND r.binding_id IS NOT NULL AND r.runtime_session_generation>0 AND r.agent_id IN (
                    """+String.join(",",Collections.nCopies(part.size(),"?"))+")"+(locking?" FOR SHARE NOWAIT":""),(rs,k)->new Epoch(rs.getString(1),rs.getString(2),rs.getLong(3),
                        new SessionFence(rs.getString(4),rs.getString(5),rs.getString(6),rs.getLong(7)),new cn.jia.agent.service.NativeTaskConfigurationProbe.Profile(rs.getLong(8),rs.getString(9))),args.toArray());
                for(var e:observed)if(e.equals(expected.get(e.agent())))matches.add(e.agent());
            }catch(DataAccessException failure){
                for(Throwable cause=failure;cause!=null;cause=cause.getCause())if(cause instanceof java.sql.SQLException sql&&locking&&sql.getErrorCode()==3572)throw new Busy();
                throw failure;
            }
        }return Set.copyOf(matches);
    }
}
