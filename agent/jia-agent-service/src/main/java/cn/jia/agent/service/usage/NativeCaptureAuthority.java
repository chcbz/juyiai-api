package cn.jia.agent.service.usage;

import cn.jia.agent.dao.PersonalWorkspaceExecutionDao;
import cn.jia.agent.security.AgentRuntimeAuthentication;
import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import cn.jia.economy.usage.UsageCaptureJournal;
import org.springframework.stereotype.Service;
import java.util.List;

/** Called under the original Runtime fence. Terminal runs keep evidence authority, never execution authority. */
@Service
public final class NativeCaptureAuthority {
    private final PersonalWorkspaceExecutionDao executions;
    public NativeCaptureAuthority(PersonalWorkspaceExecutionDao executions){this.executions=executions;}
    public UsageCaptureJournal.Binding resolve(AgentRuntimeAuthentication.Scope scope,String execution,String run){
        var row=executions.findForNativeUsageCost(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),scope.agentId(),execution,run);
        if(row==null||!"RESPONSE_V2".equals(row.getUsageMode())||!"METERED_CAPTURE_V2".equals(row.getCaptureProtocol()))
            throw new PersonalWorkspaceExecutionService.Failure(PersonalWorkspaceExecutionService.Reason.NOT_FOUND);
        return new UsageCaptureJournal.Binding(scope.tenantId(),scope.clientId(),row.getOwnerJiacn(),scope.agentId(),row.getTaskId(),execution,run,
                NativeCaptureEvidence.digest(List.of("CAPTURE_V2",execution,run)));
    }
}
