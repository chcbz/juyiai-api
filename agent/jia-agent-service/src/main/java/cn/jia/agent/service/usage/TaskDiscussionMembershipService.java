package cn.jia.agent.service.usage;

import cn.jia.agent.dao.PersonalWorkspaceExecutionDao;
import cn.jia.agent.service.AgentTaskExecutionGrantService.Scope;
import cn.jia.agent.service.TaskDiscussionMembership;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskDiscussionMembershipService implements TaskDiscussionMembership, cn.jia.agent.service.TaskDiscussionCaptureLookup {
    private final PersonalWorkspaceExecutionDao executions;
    public TaskDiscussionMembershipService(PersonalWorkspaceExecutionDao executions) {this.executions=executions;}

    @Override @Transactional(propagation=Propagation.MANDATORY, readOnly=true)
    public boolean enrolled(Scope owner,String taskId,String turnId,String dispatchId,String admissionSha256,String captureId) {
        var member = new PersonalWorkspaceExecutionDao.MemberIdentity(owner.tenantId(),owner.clientId(),owner.ownerJiacn(),
                taskId,turnId,dispatchId,admissionSha256);
        return executions.hasMemberCapture(member,captureId);
    }

    @Override @Transactional(propagation=Propagation.MANDATORY, rollbackFor=Exception.class)
    public String admit(Scope owner,String taskId,String turnId,String initialDispatchId,String requestDigest,boolean captureTextBootstrap) {
        // Keep Chat's original IDs, not a fabricated workspace execution or a zero-usage capture.
        var member = new PersonalWorkspaceExecutionDao.MemberIdentity(owner.tenantId(),owner.clientId(),owner.ownerJiacn(),
                taskId,turnId,initialDispatchId,requestDigest);
        String capture = captureTextBootstrap ? NativeCaptureEvidence.captureIdentity(turnId, initialDispatchId) : null;
        executions.appendMember(member,capture);
        return capture;
    }
}
