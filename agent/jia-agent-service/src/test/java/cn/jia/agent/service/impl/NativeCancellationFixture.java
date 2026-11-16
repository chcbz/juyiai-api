package cn.jia.agent.service.impl;

import cn.jia.agent.config.PersonalWorkspaceExecutionProperties;
import cn.jia.agent.dao.*;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.service.*;
import cn.jia.chat.service.WorkspaceConversationAccessService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real native cancellation service and JDBC root locks. The fixture conversation adapter
 * is not a claim that the production Chat ACL adapter has been exercised by this slice. */
final class NativeCancellationFixture {
    private NativeCancellationFixture() { }
    static SkillExecutionService create(JdbcTemplate jdbc,PersonalWorkspaceExecutionDao executions,SkillExecutionDao skills,
            ApplicationEventPublisher events,String tenant,String client,String owner,String task,String agent,
            AgentTaskMutationTransaction suppliedRoots) {
        jdbc.execute("CREATE TABLE IF NOT EXISTS native_cancel_conversation_fixture (id BIGINT PRIMARY KEY)");
        jdbc.execute("INSERT IGNORE INTO native_cancel_conversation_fixture VALUES (42)");
        AgentTaskMutationTransaction roots=suppliedRoots;
        if(roots==null) {
            jdbc.execute("CREATE TABLE IF NOT EXISTS native_cancel_task_fixture (id BIGINT PRIMARY KEY)");
            jdbc.execute("INSERT IGNORE INTO native_cancel_task_fixture VALUES (1)");
            roots=mock(AgentTaskMutationTransaction.class);
            var root=new AgentTaskMetaEntity().setTaskId(task).setAssignedAgentId(agent).setTaskVersion(7L).setOwnerJiacn(owner);
            root.setTenantId(tenant);root.setClientId(client);
            when(roots.executeWithLockedTaskRootInOwnerScope(eq(tenant),eq(client),eq(owner),eq(task),any())).thenAnswer(call->{
                jdbc.queryForObject("SELECT id FROM native_cancel_task_fixture WHERE id=1 FOR UPDATE",Long.class);
                return ((AgentTaskMutationTransaction.LockedTaskMutation<?>)call.getArgument(4)).apply(root);
            });
        }
        WorkspaceConversationAccessService conversations=new WorkspaceConversationAccessService() {
            @Override public ConversationView requireAccessible(Scope scope,String id) {
                if(!tenant.equals(scope.tenantId())||!client.equals(scope.clientId())||!owner.equals(scope.ownerJiacn())||!"42".equals(id))
                    throw new IllegalArgumentException("Fixture scope mismatch");
                return new ConversationView("42","bounty","task:"+task,task,List.of(agent),1,1);
            }
            @Override public <T>T withLockedAccessible(Scope scope,String id,java.util.function.Function<ConversationView,T> action) {
                var view=requireAccessible(scope,id);
                jdbc.queryForObject("SELECT id FROM native_cancel_conversation_fixture WHERE id=42 FOR UPDATE",Long.class);
                return action.apply(view);
            }
        };
        var service=new PersonalWorkspaceExecutionServiceImpl(executions,mock(PersonalWorkspaceDao.class),
                mock(PersonalWorkspaceTaskLinkDao.class),mock(AgentRuntimeDao.class),mock(PersonalWorkspaceStorage.class),
                mock(PersonalWorkspaceWriteService.class),new PersonalWorkspaceExecutionProperties(List.of("text/plain")));
        service.setConversationAdmission(mock(AgentTaskExecutionGrantService.class),roots);
        service.setTaskExecutionDependencies(conversations,mock(AgentTaskWorkItemDao.class),mock(AgentWorkItemLeaseService.class));
        service.setSkillExecutionDependencies(skills,mock(InstalledSkillDescriptorLookup.class),mock(SkillCapabilityLookup.class),mock(TaskSkillPolicyService.class));
        service.setProjectionEvents(events);
        return service;
    }
    static String snapshotDigest(String task) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(snapshot(task).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException unavailable) { throw new IllegalStateException(unavailable); }
    }
    static String snapshot(String task) {
        return cn.jia.agent.service.SkillCanonicalJson.write(java.util.Map.of("executionProtocolVersion",6,
                "scope",java.util.Map.of("kind","TASK","conversationId","42","conversationGeneration","1","taskId",task)));
    }
}
