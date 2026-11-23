package cn.jia.economy.service.impl;

import cn.jia.economy.mapper.EconomyTaskBillingMapper;
import cn.jia.economy.task.TaskQuoteBatches;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.function.Function;

/** No price, model, sample, wallet or Agent-table reads on replay. One immutable response includes unavailable candidates. */
@Service
public class TaskQuoteBatchStore implements TaskQuoteBatches {
    private final EconomyTaskBillingMapper db;private final ObjectMapper json;
    public TaskQuoteBatchStore(EconomyTaskBillingMapper db,ObjectMapper json){this.db=db;this.json=json;}
    private static String sha(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private String input(Intent intent){return sha(json.writeValueAsString(List.of("TASK_QUOTE_BATCH_V1",intent.expectedTaskVersion(),intent.agentIds())));}
    private Result unpack(EconomyTaskBillingMapper.Batch row,String hash){if(row==null)return null;if(!hash.equals(row.inputSha()))throw new IntentConflict();return json.readValue(row.responseJson(),Result.class);}
    public Result replay(Key key,Intent intent){return unpack(db.readBatch(key,sha(key.idempotencyKey())),input(intent));}
    @Transactional(propagation=Propagation.MANDATORY,rollbackFor=Exception.class)
    public Result commit(Key key,Intent intent,Function<String,List<Item>> persistPrepared){
        String keySha=sha(key.idempotencyKey()),hash=input(intent);var stored=unpack(db.lockBatch(key,keySha),hash);if(stored!=null)return stored;
        String batch="quote_batch_"+UUID.randomUUID();var items=List.copyOf(persistPrepared.apply(batch));
        if(!intent.agentIds().equals(items.stream().map(Item::agentId).toList())||items.stream().anyMatch(i->!batch.equals(i.recommendationId())))throw new IllegalStateException("Prepared quote response does not match intent");
        var result=new Result(key.task().taskId(),intent.expectedTaskVersion(),System.currentTimeMillis(),items);
        db.insertBatch(key,keySha,hash,json.writeValueAsString(result));return result;
    }
}
