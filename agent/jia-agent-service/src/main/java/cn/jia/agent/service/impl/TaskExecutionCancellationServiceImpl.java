package cn.jia.agent.service.impl;

import cn.jia.agent.dao.PersonalWorkspaceExecutionDao;
import cn.jia.agent.service.PersonalWorkspaceExecutionService.OwnerScope;
import cn.jia.agent.service.SkillExecutionService;
import cn.jia.agent.service.TaskExecutionCancellationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Task business cancellation selects its executions; the shared native engine owns
 * state/fence cancellation and the existing post-commit projection notification. */
@Service
public class TaskExecutionCancellationServiceImpl implements TaskExecutionCancellationService {
    private final PersonalWorkspaceExecutionDao executions;
    private final SkillExecutionService nativeExecutions;
    public TaskExecutionCancellationServiceImpl(PersonalWorkspaceExecutionDao executions,
            @org.springframework.context.annotation.Lazy SkillExecutionService nativeExecutions) {
        this.executions=executions;this.nativeExecutions=nativeExecutions;
    }
    @Override @Transactional(propagation=Propagation.MANDATORY,rollbackFor=Exception.class)
    public void cancelTaskExecutions(String tenant,String client,String owner,String task) {
        // Caller already owns the real task root. Do not lock executions before their
        // conversation lifecycle rows: cancel() uses the same root order as commit/project.
        var scope=new OwnerScope(tenant,client,owner);
        for(var candidate:executions.findUnfinishedTaskExecutions(tenant,client,owner,task))
            nativeExecutions.cancel(scope,candidate.getExecutionId());
    }
}
