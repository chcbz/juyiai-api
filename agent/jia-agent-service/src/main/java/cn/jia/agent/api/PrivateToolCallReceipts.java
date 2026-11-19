package cn.jia.agent.api;

import cn.jia.agent.mapper.PrivateToolCallReceiptMapper;
import cn.jia.agent.mapper.PrivateToolCallReceiptMapper.Key;
import cn.jia.agent.mapper.PrivateToolCallReceiptMapper.Receipt;
import cn.jia.agent.position.PositionSkillException;
import cn.jia.agent.service.SkillCanonicalJson;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.function.Supplier;

/** Storage only. Caller owns the current execution lock, live permission and domain operation.
 * Do not execute network calls inside apply; domain and receipt must join the same transaction. */
@Service
public class PrivateToolCallReceipts {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final PrivateToolCallReceiptMapper mapper;
    public PrivateToolCallReceipts(PrivateToolCallReceiptMapper mapper) {this.mapper=mapper;}

    @Transactional(propagation=Propagation.MANDATORY)
    public Map<String,Object> apply(Key key,String tool,String digest,Supplier<Map<String,Object>> mutation) {
        Receipt old=mapper.lock(key);
        if(old!=null) return replay(old,tool,digest);
        var value=mutation.get();
        if(mapper.insert(key,new Receipt(tool,digest,SkillCanonicalJson.write(value)),System.currentTimeMillis())!=1)
            throw new IllegalStateException("Private tool receipt not persisted");
        return value;
    }
    @SuppressWarnings("unchecked")
    static Map<String,Object> replay(Receipt old,String tool,String digest) {
        if(!tool.equals(old.tool())||!digest.equals(old.inputSha256())) throw new PositionSkillException(409,"PRIVATE_TOOL_CALL_CONFLICT");
        return JSON.readValue(old.receiptJson(),Map.class);
    }
}
