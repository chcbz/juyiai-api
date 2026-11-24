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
public class NativeTaskPreparationService implements NativeTaskPreparation {
    private static final String TABLE="agent_task_native_preparation";
    private final JdbcTemplate db;private final AgentTaskMutationTransaction roots;private final AgentTaskRequirementSnapshotService requirements;
    private final AgentTaskExecutionGrantService grants;private final AgentExternalServicePolicyService candidates;private final ObjectMapper json;
    public NativeTaskPreparationService(JdbcTemplate db,AgentTaskMutationTransaction roots,AgentTaskRequirementSnapshotService requirements,AgentTaskExecutionGrantService grants,AgentExternalServicePolicyService candidates,ObjectMapper json){
        this.db=db;this.roots=roots;this.requirements=requirements;this.grants=grants;this.candidates=candidates;this.json=json;}
    private static String hash(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private String key(Binding b){var t=b.target();return hash(json.writeValueAsString(List.of("native-task-binding-v1",b.scope().tenantId(),b.scope().clientId(),b.scope().ownerJiacn(),b.task(),b.taskVersion(),b.requirementRevision(),b.requirementSha(),b.inputSha(),t.agent(),t.provider(),t.binding(),t.externalPolicyRevision())));}
    public Frozen find(Binding b){var scope=b.scope();var rows=db.query("SELECT intent_sha,intent_json FROM "+TABLE+" WHERE tenant_id=? AND client_id=? AND owner_jiacn=? AND task_id=? AND binding_sha=?",(rs,i)->{
        String sha=rs.getString(1),body=rs.getString(2);if(!hash(body).equals(sha))throw new IllegalStateException("Changed native task intent");
        var intent=json.readValue(body,Intent.class);if(!b.equals(intent.binding()))throw new IllegalStateException("Wrong native task intent scope");return new Frozen(sha,intent);
    },scope.tenantId(),scope.clientId(),scope.ownerJiacn(),b.task(),key(b));return rows.isEmpty()?null:rows.getFirst();}
    public Frozen freeze(Intent intent){var b=intent.binding();var scope=b.scope();return roots.executeWithLockedTaskRootInOwnerScope(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),b.task(),root->{
        if(!"open".equals(root.getRewardStatus())||root.getTaskVersion()!=b.taskVersion())throw new IllegalStateException("Task is no longer open at the selected version");
        var req=requirements.readCurrentWithinLockedTask(scope,b.task(),root);
        if(req.requirementRevision()!=b.requirementRevision()||!req.sha256().equals(b.requirementSha())||!grants.snapshotTaskInputsWithinLockedTask(scope,b.task()).digest().equals(b.inputSha())
           ||!List.of(b.target()).equals(candidates.quoteTargets(scope,b.task(),List.of(b.target().agent()),true)))throw new IllegalStateException("Stale dispatch inputs or candidate");
        var prior=find(b);if(prior!=null){if(!intent.equals(prior.intent()))throw new IllegalStateException("Different initial execution already fixed");return prior;}
        String body=json.writeValueAsString(intent),sha=hash(body);db.update("INSERT INTO "+TABLE+" (tenant_id,client_id,owner_jiacn,task_id,binding_sha,intent_sha,intent_json) VALUES(?,?,?,?,?,?,?)",scope.tenantId(),scope.clientId(),scope.ownerJiacn(),b.task(),key(b),sha,body);return new Frozen(sha,intent);
    });}
}
