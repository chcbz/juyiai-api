package cn.jia.agent.service.usage;

import cn.jia.agent.dao.PersonalWorkspaceExecutionDao;
import cn.jia.agent.service.AgentTaskExecutionGrantService.Scope;
import cn.jia.agent.service.TaskDiscussionMembership;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskDiscussionMembershipService implements TaskDiscussionMembership {
    private final PersonalWorkspaceExecutionDao executions;
    public TaskDiscussionMembershipService(PersonalWorkspaceExecutionDao executions) {this.executions=executions;}

    @Override @Transactional(propagation=Propagation.MANDATORY, rollbackFor=Exception.class)
    public void admit(Scope owner,String taskId,String turnId,String initialDispatchId,String requestDigest) {
        // Keep Chat's original IDs, not a fabricated workspace execution or a zero-usage capture.
        var member = new PersonalWorkspaceExecutionDao.MemberIdentity(owner.tenantId(),owner.clientId(),owner.ownerJiacn(),
                taskId,turnId,initialDispatchId,requestDigest);
        executions.appendMember(member,null);
    }
}
