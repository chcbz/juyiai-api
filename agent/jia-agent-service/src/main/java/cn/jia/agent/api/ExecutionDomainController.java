package cn.jia.agent.api;

import cn.jia.agent.mapper.PrivateToolCallReceiptMapper.Key;
import cn.jia.agent.position.PositionSkillException;
import cn.jia.agent.position.PositionSkillService;
import cn.jia.agent.security.AgentRuntimeAuthenticationService;
import cn.jia.agent.service.PersonalWorkspaceExecutionService.*;
import cn.jia.agent.service.PositionAuthority.Scope;
import cn.jia.agent.service.SkillCanonicalJson;
import cn.jia.agent.service.SkillExecutionService;
import cn.jia.core.entity.JsonRequest;
import cn.jia.core.entity.JsonResult;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import static cn.jia.agent.position.PositionSkillException.require;

/** Execution admission and durable intent receipts, independent of the business resource. */
abstract class ExecutionDomainController extends RunningExecutionEndpoint {
    private final PositionSkillService.RequiredPositionSkill requirement;
    private final InstalledDomainAuthority authority;
    private final PrivateToolCallReceipts receipts;
    ExecutionDomainController(PositionSkillService.RequiredPositionSkill requirement,SkillExecutionService executions,InstalledDomainAuthority authority,PrivateToolCallReceipts receipts){
        super(executions);this.requirement=requirement;this.authority=authority;this.receipts=receipts;
    }
    <T> T authorized(Call<?> c,boolean publish,boolean mutation,Function<Scope,T> action) {
        return executions.withRunningSkills(c.actor(),c.execution(),c.run(),c.fence(),c.snapshot(),grant->{
            var a=grant.subject(); var scope=new Scope(a.tenantId(),a.clientId(),a.executorOwnerJiacn());
            // A task from a different principal cannot commission administrative writes on the executor's account.
            require(!mutation || a.executorOwnerJiacn().equals(a.ownerJiacn()),403,"DOMAIN_OWNER_REQUIRED");
            var selected=ExecutionSkillSelection.bound(c,grant.selections(),receipts);
            authority.requireInstalled(scope,a.agentId(),grant.installation(),grant.host(),publish,selected,requirement);return action.apply(scope);
        });
    }
    // One business intention per canonical operation/input in this run. The receipt is independent of
    // socket lifetime, model tool IDs, lease renewal and retried HTTP request IDs. A receipt-only request
    // never prepares or invokes a mutation. The second authorization covers out-of-transaction quote I/O.
    ResponseEntity<?> change(Call<?> c,String resource,boolean publish,Map<String,?> input,boolean receiptOnly,Supplier<Function<Scope,Map<String,Object>>> prepare){
        var digest=PositionSkillService.hash(SkillCanonicalJson.write(Map.of("resource",resource,"input",input,"snapshot",c.snapshot())));
        var a=c.actor();var key=new Key(a.tenantId(),a.clientId(),a.ownerJiacn(),c.execution(),c.run(),"domain-"+digest);
        var prior=authorized(c,publish,true,s->receipts.find(key));
        if(prior!=null)return ok(PrivateToolCallReceipts.replay(prior,resource,digest));
        require(!receiptOnly,404,"DOMAIN_RECEIPT_NOT_FOUND");var op=prepare.get();
        return ok(authorized(c,publish,true,s->receipts.apply(key,resource,digest,()->op.apply(s))));
    }
}
