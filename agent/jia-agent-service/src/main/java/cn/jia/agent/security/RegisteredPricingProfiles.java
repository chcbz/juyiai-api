package cn.jia.agent.security;

import cn.jia.agent.service.NativePricingProfiles;
import cn.jia.agent.service.SkillCapabilityLookup;
import cn.jia.agent.skill.SkillManagementService;
import cn.jia.agent.skill.quote.CostProfileReport;
import cn.jia.agent.skill.quote.MeasuredCostProfile.Slot;
import cn.jia.agent.service.AgentManagedSessionLookup.SessionFence;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** The caller's existing registration fence owns installation/identity/runtime locks, in that order. */
@Service
public class RegisteredPricingProfiles implements NativePricingProfiles {
    private final JdbcTemplate db;
    private final SkillManagementService skills;
    public RegisteredPricingProfiles(JdbcTemplate db,SkillManagementService skills){this.db=db;this.skills=skills;}
    private boolean installed(String tenant,String client,String provider,String agent,String installation,String host,CostProfileReport report){
        return installed(tenant,client,provider,agent,installation,host,report,false);
    }
    private boolean installed(String tenant,String client,String provider,String agent,String installation,String host,CostProfileReport report,boolean locking){
        var requested=new HashSet<Slot>();
        for(var profile:report.profiles())requested.addAll(profile.scopePattern());
        // An empty report withdraws cost evidence without querying the package/runtime catalog.
        if(requested.isEmpty())return true;
        var scope=new SkillCapabilityLookup.Scope(tenant,client,provider,agent);
        return (locking?skills.lockCostProfileBindings(scope,installation,host,requested)
                :skills.costProfileBindings(scope,installation,host,requested)).containsAll(requested);
    }
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
        CostProfileReport report=null;
        if(wire!=null)try{report=CostProfileReportJson.readWire(wire);}catch(IllegalArgumentException ignored){ /* withdraw malformed/unknown semantics */ }
        long seq=report!=null?report.sequence():row.same()?row.sequence():0;
        String sha=report==null?null:report.sha256();
        // A stale report cannot withdraw a newer revision or trigger authority reads.
        if(row.same() && seq<row.sequence())return;
        if(row.same() && seq==row.sequence() && !Objects.equals(row.sha(),sha)) {
            // Contradiction (including malformed input) is sticky until a higher sequence.
            if(row.sha()==null)return;
            report=null;sha=null;
        }
        if(report!=null && !installed(tenant,client,provider,agent,fence.runtimeInstallationId(),fence.hostId(),report)) {
            report=null;sha=null;
        }
        if(row.same() && seq==row.sequence() && Objects.equals(row.sha(),sha))return;
        String json=report==null?null:CostProfileReportJson.write(report);
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
    /** The caller already locked this exact assignment target through the original authority path. */
    public Observed atAssignment(cn.jia.agent.service.TaskAssigneeAuthorityService.Target target){
        return readSubjects(target.tenantId(),target.clientId(),List.of(new Subject(target.agentId(),target.ownerJiacn(),target.bindingId())),true).get(target.agentId());
    }
    public Observed atDispatch(cn.jia.agent.service.TaskAssigneeAuthorityService.Target target,SessionFence fence){
        var row=readSubjects(target.tenantId(),target.clientId(),List.of(new Subject(target.agentId(),target.ownerJiacn(),target.bindingId())),true).get(target.agentId());
        return row!=null && row.installation().equals(fence.runtimeInstallationId()) && row.host().equals(fence.hostId()) && row.instance().equals(fence.runtimeInstanceId()) && row.generation()==fence.sessionGeneration()?row:null;
    }
    /** Current native identity and installed capability facts, deliberately independent of whether
     * a pricing report was sent. The authorized quote targets still come from the task authority. */
    public record CapabilityObserved(String agent,String provider,long binding,String installation,String host,
            String instance,long generation,Set<SkillManagementService.QuoteCapability> capabilities) {
        public CapabilityObserved {capabilities=Set.copyOf(capabilities);}
        @Override public String toString(){return "RegisteredCapabilityFacts[private]";}
    }
    public Map<String,CapabilityObserved> readCapabilities(String tenant,String client,
            List<cn.jia.agent.service.AgentExternalServicePolicyService.QuoteTarget> authorized,boolean lock) {
        var subjects=authorized.stream().map(t->new Subject(t.agent(),t.provider(),t.binding())).toList();
        var result=new HashMap<String,CapabilityObserved>();
        for(var row:readSubjectRows(tenant,client,subjects,lock,false).values()) {
            var scope=new SkillCapabilityLookup.Scope(tenant,client,row.provider(),row.agent());
            var capabilities=lock?skills.lockQuoteCapabilities(scope,row.installation(),row.host())
                    :skills.quoteCapabilities(scope,row.installation(),row.host());
            result.put(row.agent(),new CapabilityObserved(row.agent(),row.provider(),row.binding(),row.installation(),
                    row.host(),row.instance(),row.generation(),capabilities));
        }
        return Map.copyOf(result);
    }
    private Map<String,Observed> readSubjects(String tenant,String client,List<Subject> authorized,boolean lock){
        var result=new HashMap<String,Observed>();
        for(var row:readSubjectRows(tenant,client,authorized,lock,true).values()) {
            CostProfileReport report;
            try{report=CostProfileReportJson.read(row.canonical());}
            catch(IllegalArgumentException corrupt){throw new IllegalStateException("Invalid stored pricing configuration",corrupt);}
            if(row.seq()!=report.sequence() || !row.sha().equals(report.sha256()))throw new IllegalStateException("Invalid stored pricing configuration");
            if(!installed(tenant,client,row.provider(),row.agent(),row.installation(),row.host(),report,lock))continue;
            result.put(row.agent(),row);
        }
        return Map.copyOf(result);
    }
    /** Shared bounded identity read. Capability projection does not even reference pricing columns;
     * missing, withdrawn or malformed pricing evidence cannot erase native installation identity. */
    private Map<String,Observed> readSubjectRows(String tenant,String client,List<Subject> authorized,boolean lock,boolean pricing){
        if(lock&&!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Missing quote transaction");
        var targets=new TreeMap<String,Subject>();
        for(var t:authorized)if(targets.put(t.agent(),t)!=null)throw new IllegalArgumentException("Duplicate candidate");
        var ids=List.copyOf(targets.keySet());var result=new LinkedHashMap<String,Observed>();
        for(int i=0;i<ids.size();i+=128){var page=ids.subList(i,Math.min(ids.size(),i+128));var params=new ArrayList<Object>(List.of(tenant,client));params.addAll(page);
            try{
                var rows=db.query("""
                    SELECT r.agent_id,r.owner_jiacn,r.binding_id,r.runtime_installation_id,r.runtime_host_id,r.runtime_instance_id,r.runtime_session_generation,
                    """+(pricing?"r.pricing_sequence,r.pricing_config_sha,r.pricing_profiles_json":"0,NULL,NULL")+" "+"""
                    FROM agent_runtime r JOIN agent_runtime_v1_installation n ON n.installation_id=r.runtime_installation_id
                      AND BINARY n.installation_id=BINARY r.runtime_installation_id AND BINARY n.canonical_agent_id=BINARY r.agent_id
                      AND BINARY n.tenant_id=BINARY r.tenant_id AND BINARY n.client_id=BINARY r.client_id AND BINARY n.status='ACTIVE'
                    WHERE BINARY r.tenant_id=BINARY ? AND BINARY r.client_id=BINARY ?
                    """+(pricing?"""
                      AND r.pricing_config_sha IS NOT NULL
                      AND BINARY r.pricing_installation_id=BINARY r.runtime_installation_id AND r.pricing_generation=r.runtime_session_generation
                      AND r.pricing_binding_id=r.binding_id
                    """:"")+" AND r.agent_id IN ("+String.join(",",Collections.nCopies(page.size(),"?"))+") ORDER BY r.agent_id"+(lock?" FOR SHARE NOWAIT":""),
                    (rs,k)->new Observed(rs.getString(1),rs.getString(2),rs.getLong(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getLong(7),rs.getLong(8),rs.getString(9),rs.getString(10)),params.toArray());
                for(var row:rows){var t=targets.get(row.agent());
                    if(t!=null&&row.provider().equals(t.provider())&&row.binding()==t.binding())result.put(row.agent(),row);}
            }catch(org.springframework.dao.DataAccessException failure){for(Throwable c=failure;c!=null;c=c.getCause())if(lock&&c instanceof java.sql.SQLException e&&e.getErrorCode()==3572)throw new PreparedRuntimeEpochFence.Busy();throw failure;}
        }
        // Preserve the ordered identity scan for subsequent per-candidate skill locks.
        return Collections.unmodifiableMap(result);
    }

}
