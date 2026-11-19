package cn.jia.agent.api;

import cn.jia.agent.mapper.PrivateToolCallReceiptMapper.Key;
import cn.jia.agent.position.PositionSkillException;
import cn.jia.agent.service.*;
import cn.jia.agent.service.PersonalWorkspaceExecutionService.RuntimeScope;
import cn.jia.agent.service.SkillExecutionService.*;
import cn.jia.chat.service.ConversationToolProvider.Context;
import cn.jia.chat.service.PrivateConversationToolAccess.Subject;
import org.springframework.stereotype.Service;
import java.util.Map;
import static cn.jia.agent.position.PositionSkillException.require;

@Service
public final class NativePrivateModelCalls {
    private final SkillExecutionService executions; private final ModelConversationToolAdapter models; private final PrivateToolCallReceipts receipts;
    public NativePrivateModelCalls(SkillExecutionService executions,ModelConversationToolAdapter models,PrivateToolCallReceipts receipts){this.executions=executions;this.models=models;this.receipts=receipts;}
    static Context context(SkillCapabilityLookup.Scope s,String install,String host){return new Context(new Subject(s.tenantId(),s.clientId(),s.ownerJiacn(),s.agentId()),install,host);}
    private PositionAuthority.Scope authorize(RuntimeScope s,PrivateToolGrant grant,String tool){
        require(grant.tools().contains(tool)&&models.names().contains(tool),404,"PRIVATE_TOOL_UNAVAILABLE");
        var scope=new PositionAuthority.Scope(s.tenantId(),s.clientId(),s.ownerJiacn());
        models.authorizeFrozen(context(new SkillCapabilityLookup.Scope(s.tenantId(),s.clientId(),s.ownerJiacn(),s.agentId()),grant.installation(),grant.host()),scope,ModelConversationToolAdapter.priceAuthority(tool),grant.selections());return scope;
    }
    private static Key key(RuntimeScope s,String e,String r,String c){require(c!=null&&c.matches("[a-zA-Z0-9][a-zA-Z0-9._:-]{0,99}"),400,"PRIVATE_TOOL_CALL_INVALID");return new Key(s.tenantId(),s.clientId(),s.ownerJiacn(),e,r,c);}
    Map<String,Object> read(RuntimeScope s,String e,String r,String c){var k=key(s,e,r,c);return executions.withPrivateToolGrant(s,e,r,null,g->{
        var receipt=receipts.find(k);require(receipt!=null,404,"PRIVATE_TOOL_RECEIPT_UNAVAILABLE");authorize(s,g,receipt.tool());return PrivateToolCallReceipts.replay(receipt,receipt.tool(),receipt.inputSha256());});}
    Map<String,Object> call(RuntimeScope s,String e,String r,String c,PrivateToolRequest cmd){
        var k=key(s,e,r,c); require(cmd.threadId()!=null&&cmd.turnId()!=null&&cmd.threadId().matches("[a-zA-Z0-9_.:-]{1,100}")&&cmd.turnId().matches("[a-zA-Z0-9_.:-]{1,100}")&&cmd.tool()!=null&&cmd.arguments()!=null&&cmd.catalogSha256()!=null,400,"PRIVATE_TOOL_CALL_INVALID");
        String hash=cn.jia.agent.position.PositionSkillService.hash(SkillCanonicalJson.write(Map.of("executionId",e,"runId",r,"callId",c,"threadId",cmd.threadId(),"turnId",cmd.turnId(),"snapshotSha256",cmd.snapshotDigest(),"catalogSha256",cmd.catalogSha256(),"tool",cmd.tool(),"arguments",cmd.arguments())));
        var prior=executions.withPrivateToolGrant(s,e,r,cmd,g->{authorize(s,g,cmd.tool());return receipts.find(k);});
        if(prior!=null)return PrivateToolCallReceipts.replay(prior,cmd.tool(),hash);
        var operation=models.prepare(cmd.tool(),cmd.arguments());
        return executions.withPrivateToolGrant(s,e,r,cmd,g->{var scope=authorize(s,g,cmd.tool());return ModelConversationToolAdapter.transactionalWrite(cmd.tool())?
                receipts.apply(k,cmd.tool(),hash,()->operation.apply(scope)):operation.apply(scope);});
    }
}
