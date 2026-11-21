package cn.jia.agent.api;

import cn.jia.agent.position.PositionSkillService;
import cn.jia.agent.position.PositionSkillPolicy;
import cn.jia.agent.service.ArchiveExecutionJobLookup;
import cn.jia.agent.service.SkillExecutionService;
import cn.jia.core.entity.JsonRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Exposes only a fixed job's read projection, never import, publish or replay. */
@RestController
@RequestMapping("/internal/agent/executions/{e}/runs/{r}/business/archive-jobs")
public final class ExecutionArchiveJobController extends ExecutionDomainController {
    private final ArchiveExecutionJobLookup jobs;
    ExecutionArchiveJobController(SkillExecutionService executions, InstalledDomainAuthority authority,
                                  PrivateToolCallReceipts receipts, ArchiveExecutionJobLookup jobs) {
        super(new PositionSkillService.RequiredPositionSkill("archive:public", "archive-maintenance",
                "ARCHIVE_APPOINTMENT_REQUIRED", "ARCHIVE_SKILL_NOT_READY"), executions, authority, receipts);
        this.jobs = jobs;
    }
    public record Input(String jobId, String snapshotSha256, Fence fence) implements BoundInput {}
    @PostMapping("/lookup")
    public ResponseEntity<?> lookup(@PathVariable String e, @PathVariable String r,
                                    @RequestBody JsonRequest<Input> body, Authentication auth) {
        var c = call(auth,e,r,body);
        PositionSkillPolicy.key(c.input().jobId());
        // Job metadata is private to the owner, even though the work may be published.
        return ok(authorized(c,false,true,s -> jobs.readJob(s,c.actor().agentId(),c.input().jobId())));
    }
}
