package cn.jia.agent.service.impl;

import cn.jia.agent.service.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** The task root serializes first registration with edits and assignment. This service never creates an execution or quote. */
@Service
public class NativeTaskPreparationService implements NativeTaskPreparation,TaskInitialActionSelection {
    // A new observation must be obtained within the existing service liveness horizon. Heartbeats do not renew a ticket.
    @org.springframework.beans.factory.annotation.Value("${jia.agent.status.heartbeat-timeout-seconds:60}")
    private long probeLifetimeSeconds=60;
    private static final String TABLE="agent_task_native_preparation";
    private final JdbcTemplate db;private final AgentTaskMutationTransaction roots;private final AgentTaskRequirementSnapshotService requirements;
    private final AgentTaskExecutionGrantService grants;private final AgentExternalServicePolicyService candidates;private final ObjectMapper json;
    public NativeTaskPreparationService(JdbcTemplate db,AgentTaskMutationTransaction roots,AgentTaskRequirementSnapshotService requirements,AgentTaskExecutionGrantService grants,AgentExternalServicePolicyService candidates,ObjectMapper json){
        this.db=db;this.roots=roots;this.requirements=requirements;this.grants=grants;this.candidates=candidates;this.json=json;}
    private static String hash(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private String key(Binding b){var t=b.target();return hash(json.writeValueAsString(List.of("native-task-binding-v1",b.scope().tenantId(),b.scope().clientId(),b.scope().ownerJiacn(),b.task(),b.taskVersion(),b.requirementRevision(),b.requirementSha(),b.inputSha(),t.agent(),t.provider(),t.binding(),t.externalPolicyRevision())));}
    private Frozen decode(Binding expected,String sha,String body){
        if(!hash(body).equals(sha))throw new IllegalStateException("Changed native task intent");
        var intent=json.readValue(body,Intent.class);
        if(!expected.equals(intent.binding()))throw new IllegalStateException("Wrong native task intent scope");
        return new Frozen(sha,intent);
    }
    public Frozen find(Binding b){var scope=b.scope();var rows=db.query("SELECT intent_sha,intent_json FROM "+TABLE+" WHERE tenant_id=? AND client_id=? AND owner_jiacn=? AND task_id=? AND binding_sha=?",
        (rs,i)->decode(b,rs.getString(1),rs.getString(2)),scope.tenantId(),scope.clientId(),scope.ownerJiacn(),b.task(),key(b));return rows.isEmpty()?null:rows.getFirst();}

    @Override public Map<String,Frozen> currentForProbes(AgentTaskExecutionGrantService.Scope scope,String task,long expectedVersion,List<String> ids){
        return select(scope,task,expectedVersion,ids,null,null);
    }
    @Override public Comparison reserveComparisons(AgentTaskExecutionGrantService.Scope scope,String task,long expectedVersion,List<String> ids){
        String ticket=UUID.randomUUID().toString();return new Comparison(ticket,select(scope,task,expectedVersion,ids,ticket,null));
    }
    @Override public Comparison reserveDue(Binding anchor,Set<Binding> expected){
        String ticket=UUID.randomUUID().toString();
        var ids=expected.stream().filter(b->b.scope().equals(anchor.scope())&&b.task().equals(anchor.task())&&b.taskVersion()==anchor.taskVersion()).map(b->b.target().agent()).distinct().sorted().toList();
        return new Comparison(ticket,select(anchor.scope(),anchor.task(),anchor.taskVersion(),ids,ticket,expected));
    }
    private static final String DUE=" AND (probe_valid_until IS NULL OR probe_valid_until<=UTC_TIMESTAMP(6))";
    private Map<String,Frozen> select(AgentTaskExecutionGrantService.Scope scope,String task,long expectedVersion,List<String> ids,String ticket,Set<Binding> expected){
        if(ids.isEmpty())return Map.of();
        return roots.executeWithLockedTaskRootInOwnerScope(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),task,root->{
            if(!"open".equals(root.getRewardStatus())||root.getTaskVersion()!=expectedVersion)return Map.of();
            var targets=candidates.quoteTargets(scope,task,ids,false);if(targets.isEmpty())return Map.of();
            var req=requirements.readCurrentWithinLockedTask(scope,task,root);
            var input=grants.snapshotTaskInputsWithinLockedTask(scope,task).digest();var found=new HashMap<String,Frozen>();
            for(int i=0;i<targets.size();i+=128){
                var keys=new LinkedHashMap<String,Binding>();
                for(var target:targets.subList(i,Math.min(i+128,targets.size()))){
                    var binding=new Binding(scope,task,expectedVersion,req.requirementRevision(),req.sha256(),input,target);
                    if(expected==null||expected.contains(binding))keys.put(key(binding),binding);
                }
                if(keys.isEmpty())continue;
                var args=new ArrayList<Object>(List.of(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),task));args.addAll(keys.keySet());
                db.query("SELECT binding_sha,intent_sha,intent_json FROM "+TABLE+" WHERE tenant_id=? AND client_id=? AND owner_jiacn=? AND task_id=? AND binding_sha IN ("
                    +String.join(",",Collections.nCopies(keys.size(),"?"))+")"+(expected==null?"":DUE),(org.springframework.jdbc.core.RowCallbackHandler)rs->{
                        var binding=keys.get(rs.getString(1));var frozen=decode(binding,rs.getString(2),rs.getString(3));
                        found.put(binding.target().agent(),frozen);
                    },args.toArray());
                if(ticket!=null){
                    var matched=keys.entrySet().stream().filter(e->found.containsKey(e.getValue().target().agent())).map(Map.Entry::getKey).toList();
                    if(!matched.isEmpty()){
                        var params=new ArrayList<Object>(List.of(ticket,probeLifetimeSeconds,scope.tenantId(),scope.clientId(),scope.ownerJiacn(),task));params.addAll(matched);
                        int changed=db.update("UPDATE "+TABLE+" SET probe_ticket=?,probe_valid_until=TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(6)),measurement_sha=NULL,measurement_json=NULL WHERE tenant_id=? AND client_id=? AND owner_jiacn=? AND task_id=? AND binding_sha IN ("
                            +String.join(",",Collections.nCopies(matched.size(),"?"))+")"+(expected==null?"":DUE),params.toArray());
                        if(changed!=matched.size())throw new IllegalStateException("Preparation changed while reserving comparison");
                    }
                }
            }
            return Map.copyOf(found);
        });
    }
    private static final String EXACT=" WHERE tenant_id=? AND client_id=? AND owner_jiacn=? AND task_id=? AND binding_sha=? AND intent_sha=? AND probe_ticket=?";
    private List<Object> observationKey(Frozen decision,String ticket){var b=decision.intent().binding();var scope=b.scope();return List.of(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),b.task(),key(b),decision.sha256(),ticket);}
    @Override public boolean recordMeasurement(Comparison comparison,String agent,NativeTaskConfigurationProbe.Measurement measurement){
        var decision=comparison.decisions().get(agent);
        if(decision==null||measurement==null||decision.intent().instruction()!=null && "NATIVE_LOCAL_ARTIFACT_TEXT_V1".equals(measurement.capturePolicyVersion())||!decision.sha256().equals(measurement.intentSha256()))return false;
        String body=json.writeValueAsString(measurement);var args=new ArrayList<Object>(List.of(hash(body),body));args.addAll(observationKey(decision,comparison.ticket()));
        return db.update("UPDATE "+TABLE+" SET measurement_sha=?,measurement_json=?"+EXACT+" AND probe_valid_until>UTC_TIMESTAMP(6) AND measurement_sha IS NULL",args.toArray())==1;
    }
    @Override public boolean lockMeasurement(String ticket,Frozen decision,NativeTaskConfigurationProbe.Measurement measurement){
        var binding=decision.intent().binding();var found=recorded(binding.scope(),binding.task(),List.of(binding),true).get(binding);
        return found!=null&&found.equals(new Recorded(ticket,decision,measurement));
    }
    @Override public Map<Binding,Recorded> recorded(AgentTaskExecutionGrantService.Scope scope,String task,Collection<Binding> bindings,boolean locking){
        return readBatch(scope,task,bindings,locking).current();
    }
    @Override public ReadBatch readBatch(AgentTaskExecutionGrantService.Scope scope,String task,Collection<Binding> bindings,boolean locking){
        var due=new HashSet<Binding>();
        if(locking&&!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Missing quote transaction");
        var keys=new TreeMap<String,Binding>();for(var binding:bindings){
            if(!scope.equals(binding.scope())||!task.equals(binding.task()))throw new IllegalArgumentException("Mixed task scope");
            keys.put(key(binding),binding);
        }
        var result=new HashMap<Binding,Recorded>();var ids=List.copyOf(keys.keySet());
        for(int i=0;i<ids.size();i+=128){var chunk=ids.subList(i,Math.min(i+128,ids.size()));
            var args=new ArrayList<Object>(List.of(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),task));args.addAll(chunk);
            db.query("SELECT binding_sha,intent_sha,intent_json,probe_ticket,measurement_sha,measurement_json,(probe_valid_until>UTC_TIMESTAMP(6)) FROM "+TABLE
                +" WHERE tenant_id=? AND client_id=? AND owner_jiacn=? AND task_id=? AND binding_sha IN ("+String.join(",",Collections.nCopies(chunk.size(),"?"))+") ORDER BY binding_sha"+(locking?" FOR SHARE":""),
                (org.springframework.jdbc.core.RowCallbackHandler)rs->{if(!rs.getBoolean(7)){if(!locking)due.add(keys.get(rs.getString(1)));return;}if(rs.getString(5)==null)return;
                    var binding=keys.get(rs.getString(1));var decision=decode(binding,rs.getString(2),rs.getString(3));String body=rs.getString(6);
                    if(body==null||!hash(body).equals(rs.getString(5)))throw new IllegalStateException("Changed task measurement");
                    var m=json.readValue(body,NativeTaskConfigurationProbe.Measurement.class);
                    if(!decision.sha256().equals(m.intentSha256())||rs.getString(4)==null)throw new IllegalStateException("Unbound task measurement");
                    result.put(binding,new Recorded(rs.getString(4),decision,m));},args.toArray());
        }return new ReadBatch(result,due);
    }
    /** The caller owns the task root, authorized grant and execution transaction. No current quote/probe read here. */
    void consumeFirst(AgentTaskExecutionGrantService.Scope scope,String task,TaskAssigneeAuthorityService.Target target,String sha,String execution,String policy,List<Map<String,Object>> selections,Map<String,Object> outputs){
        if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Missing native admission transaction");
        var row=committed(scope,task,sha,true);if(row.execution()!=null&&!row.execution().equals(execution))return;
        var intent=row.intent();var b=intent.binding();var t=b.target();
        if(!scope.equals(b.scope())||!task.equals(b.task())||!t.agent().equals(target.agentId())||!t.provider().equals(target.ownerJiacn())||t.binding()!=target.bindingId()
            ||!intent.matches(policy,selections,outputs))throw new PersonalWorkspaceExecutionService.Failure(PersonalWorkspaceExecutionService.Reason.TASK_CONFLICT);
        if(row.execution()==null&&db.update("UPDATE "+TABLE+" SET initial_execution_id=?"+COMMITTED+" AND initial_execution_id IS NULL",execution,scope.tenantId(),scope.clientId(),scope.ownerJiacn(),task,sha)!=1)throw new IllegalStateException("Initial admission changed");
    }
    private static final String COMMITTED=" WHERE tenant_id=? AND client_id=? AND owner_jiacn=? AND task_id=? AND intent_sha=?";
    private record Committed(Intent intent,String execution){}
    private Committed committed(AgentTaskExecutionGrantService.Scope scope,String task,String sha,boolean consume){
        var rows=db.query("SELECT intent_json,initial_execution_id FROM "+TABLE+COMMITTED+(consume?" FOR UPDATE":" FOR SHARE"),(rs,n)->{
            String body=rs.getString(1);if(!hash(body).equals(sha))throw new IllegalStateException("Changed quoted initial intent");
            var intent=json.readValue(body,Intent.class);if(!scope.equals(intent.binding().scope())||!task.equals(intent.binding().task()))throw new IllegalStateException("Wrong initial intent scope");
            return new Committed(intent,rs.getString(2));},scope.tenantId(),scope.clientId(),scope.ownerJiacn(),task,sha);
        if(rows.size()!=1)throw new IllegalStateException("Missing quoted initial intent");return rows.getFirst();
    }
    Output pendingOutput(AgentTaskExecutionGrantService.Scope scope,String task,String agent,String sha){
        var row=committed(scope,task,sha,false);if(!agent.equals(row.intent().binding().target().agent()))throw new IllegalStateException("Wrong quoted initial target");
        return row.execution()==null?row.intent().output():null;
    }
    private org.springframework.context.ApplicationEventPublisher events;
    @org.springframework.beans.factory.annotation.Autowired public void setSelectionEvents(org.springframework.context.ApplicationEventPublisher value){events=Objects.requireNonNull(value);}
    private InstalledSkillDescriptorLookup installations;
    @org.springframework.beans.factory.annotation.Autowired public void setInstallations(InstalledSkillDescriptorLookup lookup){installations=Objects.requireNonNull(lookup);}
    private Instruction installed(Binding b,String installationId){
        var t=b.target();var runtime=new SkillCapabilityLookup.Scope(b.scope().tenantId(),b.scope().clientId(),t.provider(),t.agent());
        var found=installations.readCurrent(runtime).skills().stream().filter(d->installationId.equals(d.installationId())
            && d.verifiedNative() && d.manifestSha256()==null && !d.domainApi()).toList();
        if(found.size()!=1)throw new Missing();var d=found.getFirst();
        return new Instruction(d.installationId(),d.skillKey(),d.skillVersion(),d.packageSha256(),d.instructionPolicy());
    }
    public View chooseSkill(AgentTaskExecutionGrantService.Scope scope,String task,String agent,String version,SkillChoice choice,String principal,String key){
        var targets=candidates.quoteTargets(scope,task,List.of(agent),false);
        if(targets.size()!=1)throw new Missing();var t=targets.getFirst();
        var runtime=new SkillCapabilityLookup.Scope(scope.tenantId(),scope.clientId(),t.provider(),t.agent());
        return installations.withPreparedRead(runtime,()->selection(scope,task,agent,Objects.requireNonNull(version),null,
                Objects.requireNonNull(principal),Objects.requireNonNull(key),Objects.requireNonNull(choice)));
    }
    private static Output output(Choice c){return switch(c){case LOCAL_ANSWER->new Output(Mode.ANSWER,List.of());case LOCAL_TEXT_FILE->new Output(Mode.ARTIFACTS,List.of("text/plain"));};}
    public View view(AgentTaskExecutionGrantService.Scope scope,String task,String agent){return selection(scope,task,agent,null,null,null,null,null);}
    public View choose(AgentTaskExecutionGrantService.Scope scope,String task,String agent,String version,Choice choice,String principal,String key){return selection(scope,task,agent,Objects.requireNonNull(version),Objects.requireNonNull(choice),Objects.requireNonNull(principal),Objects.requireNonNull(key),null);}
    private View selection(AgentTaskExecutionGrantService.Scope scope,String task,String agent,String version,Choice choice,String principal,String key,SkillChoice skill){
        boolean writing=choice!=null||skill!=null;
        return roots.executeWithLockedTaskRootInOwnerScope(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),task,root->{
            var targets=candidates.quoteTargets(scope,task,List.of(agent),writing);if(targets.size()!=1||!agent.equals(targets.getFirst().agent()))throw new Missing();
            String op=null,input=null;
            if(writing){
                op=hash(json.writeValueAsString(List.of("initial-action-selection-v1",scope.tenantId(),scope.clientId(),scope.ownerJiacn(),principal,task,agent,key)));
                input=hash(json.writeValueAsString(skill==null?List.of(version,choice.name()):List.of(version,INSTRUCTION,skill.installationId(),skill.mimeType())));
                var saved=db.query("SELECT input_sha,task_version,selection_version,selected_choice FROM agent_task_native_selection_receipt WHERE operation_sha=?",(rs,n)->new String[]{rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4)},op);
                if(!saved.isEmpty()){var r=saved.getFirst();if(!input.equals(r[0]))throw new KeyConflict();return new View(r[1],agent,r[2],r[3]);}
                // A first selection schedules a post-commit device probe; GET and committed receipts do not.
            }
            if(!"open".equals(root.getRewardStatus()))throw new Changed();var req=requirements.readCurrentWithinLockedTask(scope,task,root);
            var b=new Binding(scope,task,root.getTaskVersion(),req.requirementRevision(),req.sha256(),grants.snapshotTaskInputsWithinLockedTask(scope,task).digest(),targets.getFirst());
            String stamp=key(b);if(writing&&!stamp.equals(version))throw new Changed();var prior=find(b);
            if(writing){var decision=skill==null?new Intent(LOCAL,b,output(choice)):new Intent(INSTRUCTION,b,new Output(Mode.ARTIFACTS,List.of(skill.mimeType())),installed(b,skill.installationId()));
                if(prior!=null&&!prior.intent().equals(decision))throw new Changed();
                if(prior==null){prior=insert(decision);events.publishEvent(new Selected(b));}
            }
            String selected=null;if(prior!=null){selected="OTHER_FIXED";if(prior.intent().instruction()!=null)selected="INSTALLED_SKILL";else for(var c:Choice.values())if(output(c).equals(prior.intent().output()))selected=c.name();}
            if(writing)db.update("INSERT INTO agent_task_native_selection_receipt(operation_sha,input_sha,task_version,selection_version,selected_choice) VALUES(?,?,?,?,?)",op,input,b.taskVersion(),stamp,selected);
            return new View(Long.toString(b.taskVersion()),agent,stamp,selected);
        });
    }
    public Frozen freeze(Intent intent){var b=intent.binding();var scope=b.scope();return roots.executeWithLockedTaskRootInOwnerScope(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),b.task(),root->{
        if(!"open".equals(root.getRewardStatus())||root.getTaskVersion()!=b.taskVersion())throw new IllegalStateException("Task is no longer open at the selected version");
        var req=requirements.readCurrentWithinLockedTask(scope,b.task(),root);
        if(req.requirementRevision()!=b.requirementRevision()||!req.sha256().equals(b.requirementSha())||!grants.snapshotTaskInputsWithinLockedTask(scope,b.task()).digest().equals(b.inputSha())
           ||!List.of(b.target()).equals(candidates.quoteTargets(scope,b.task(),List.of(b.target().agent()),true)))throw new IllegalStateException("Stale dispatch inputs or candidate");
        if(intent.instruction()!=null&&!intent.instruction().equals(installed(b,intent.instruction().installationId())))throw new Changed();
        var prior=find(b);if(prior!=null){if(!intent.equals(prior.intent()))throw new IllegalStateException("Different initial execution already fixed");return prior;}
        return insert(intent);
    });}
    private Frozen insert(Intent intent){var b=intent.binding();var scope=b.scope();String body=json.writeValueAsString(intent),sha=hash(body);db.update("INSERT INTO "+TABLE+" (tenant_id,client_id,owner_jiacn,task_id,binding_sha,intent_sha,intent_json) VALUES(?,?,?,?,?,?,?)",scope.tenantId(),scope.clientId(),scope.ownerJiacn(),b.task(),key(b),sha,body);return new Frozen(sha,intent);
    }
}
