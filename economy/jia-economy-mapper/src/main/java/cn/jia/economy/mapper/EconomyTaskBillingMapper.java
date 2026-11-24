package cn.jia.economy.mapper;

import cn.jia.economy.entity.EconomyTaskQuoteEntity;
import cn.jia.economy.entity.EconomyTaskChargeEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Param;

/** Curated SQL boundary: exact owner-scoped reads, immutable quotes, locked task charge transitions. */
public interface EconomyTaskBillingMapper {
    String SCOPE = "tenant_id=#{tenantId} AND client_id=#{clientId} AND owner_jiacn=#{ownerJiacn} AND task_id=#{taskId}";

    record Batch(String inputSha,String responseJson) {}
    String BATCH_KEY="tenant_id=#{k.task.scope.tenantId} AND client_id=#{k.task.scope.clientId} AND owner_jiacn=#{k.task.ownerJiacn} AND task_id=#{k.task.taskId} AND payer_user_id=#{k.payer} AND key_sha256=#{keySha}";
    @Select("SELECT input_sha256,response_json FROM economy_task_quote_batch WHERE "+BATCH_KEY)
    Batch readBatch(@Param("k") cn.jia.economy.task.TaskQuoteBatches.Key key,@Param("keySha") String keySha);
    @Select("SELECT input_sha256,response_json FROM economy_task_quote_batch WHERE "+BATCH_KEY+" FOR UPDATE")
    Batch lockBatch(@Param("k") cn.jia.economy.task.TaskQuoteBatches.Key key,@Param("keySha") String keySha);
    @Insert("""
        INSERT INTO economy_task_quote_batch(tenant_id,client_id,owner_jiacn,task_id,payer_user_id,key_sha256,input_sha256,response_json)
        VALUES(#{k.task.scope.tenantId},#{k.task.scope.clientId},#{k.task.ownerJiacn},#{k.task.taskId},#{k.payer},#{keySha},#{input},#{response})
        """) void insertBatch(@Param("k") cn.jia.economy.task.TaskQuoteBatches.Key key,@Param("keySha") String keySha,@Param("input") String input,@Param("response") String response);

    @Select("SELECT * FROM economy_task_quote_resolved WHERE " + SCOPE + " AND binding_sha256=#{bindingSha256}")
    EconomyTaskQuoteEntity findQuoteByBinding(@Param("tenantId") String tenant, @Param("clientId") String client,
            @Param("ownerJiacn") String owner, @Param("taskId") String task, @Param("bindingSha256") String binding);

    @Select("SELECT * FROM economy_task_quote_resolved WHERE " + SCOPE + " AND quote_id=#{quoteId}")
    EconomyTaskQuoteEntity findQuote(@Param("tenantId") String tenant, @Param("clientId") String client,
            @Param("ownerJiacn") String owner, @Param("taskId") String task, @Param("quoteId") String quote);

    @Insert("""
            INSERT INTO economy_task_quote(quote_id,binding_sha256,tenant_id,client_id,owner_jiacn,task_id,
                payer_user_id,amount_wen,snapshot_json,created_at)
            VALUES(#{quoteId},#{bindingSha256},#{tenantId},#{clientId},#{ownerJiacn},#{taskId},
                #{payerUserId},#{amountWen},#{snapshotJson},#{createdAt})
            """)
    int insertQuote(EconomyTaskQuoteEntity quote);

    @Insert("""
            <script>
            INSERT INTO economy_task_quote(quote_id,binding_sha256,tenant_id,client_id,owner_jiacn,task_id,
                payer_user_id,amount_wen,snapshot_json,created_at) VALUES
            <foreach collection="rows" item="r" separator=",">
                (#{r.quoteId},#{r.bindingSha256},#{r.tenantId},#{r.clientId},#{r.ownerJiacn},#{r.taskId},
                 #{r.payerUserId},#{r.amountWen},#{r.snapshotJson},#{r.createdAt})
            </foreach>
            </script>
            """)
    int insertQuotes(@Param("rows") java.util.List<EconomyTaskQuoteEntity> rows);

    // The caller cannot nominate a quote id. Read the charge winner, not the most recent quote or configuration.
    @Select("""
        SELECT JSON_UNQUOTE(JSON_EXTRACT(q.snapshot_json,'$.initialIntentSha256')) FROM economy_task_charge c
        JOIN economy_task_quote_resolved q ON q.quote_id=c.quote_id AND BINARY q.tenant_id=BINARY c.tenant_id AND BINARY q.client_id=BINARY c.client_id
          AND BINARY q.owner_jiacn=BINARY c.owner_jiacn AND BINARY q.task_id=BINARY c.task_id AND BINARY q.payer_user_id=BINARY c.payer_user_id
        WHERE c.tenant_id=#{tenant} AND c.client_id=#{client} AND c.owner_jiacn=#{owner} AND c.task_id=#{task} AND c.state='HELD'
          AND BINARY c.tenant_id=BINARY #{tenant} AND BINARY c.client_id=BINARY #{client} AND BINARY c.owner_jiacn=BINARY #{owner} AND BINARY c.task_id=BINARY #{task}
          AND JSON_UNQUOTE(JSON_EXTRACT(q.snapshot_json,'$.schema'))='MEASURED_TASK_QUOTE_V1'
          AND BINARY JSON_UNQUOTE(JSON_EXTRACT(q.snapshot_json,'$.binding.agentId'))=BINARY #{agent}
        """) String heldInitialIntent(@Param("tenant") String tenant,@Param("client") String client,@Param("owner") String owner,@Param("task") String task,@Param("agent") String agent);

    // Full features/configuration and repricing proof remain on the immutable quote; the sample references it.
    @Insert("""
        INSERT INTO economy_task_cost_sample(sample_id,tenant_id,client_id,owner_jiacn,task_id,quote_id,sample_definition_version,
          initial_requirement_sha256,initial_input_snapshot_sha256,initial_feature_sha256,history_group_sha256,lifecycle_state,evidence_revision,sample_state)
        SELECT #{sid},tenant_id,client_id,owner_jiacn,task_id,quote_id,'TASK_LIFECYCLE_V1',
          JSON_UNQUOTE(JSON_EXTRACT(snapshot_json,'$.binding.requirementSha256')),
          JSON_UNQUOTE(JSON_EXTRACT(snapshot_json,'$.binding.inputSnapshotDigest')),
          JSON_UNQUOTE(JSON_EXTRACT(snapshot_json,'$.featureSha256')),
          JSON_UNQUOTE(JSON_EXTRACT(snapshot_json,'$.historyGroupSha256')),'ACTIVE',1,'WAITING_EVIDENCE'
        FROM economy_task_quote_resolved WHERE tenant_id=#{q.tenantId} AND client_id=#{q.clientId} AND owner_jiacn=#{q.ownerJiacn}
          AND task_id=#{q.taskId} AND quote_id=#{q.quoteId} AND payer_user_id=#{q.payerUserId}
          AND JSON_UNQUOTE(JSON_EXTRACT(snapshot_json,'$.schema'))='MEASURED_TASK_QUOTE_V1'
        """) int confirmMeasuredSample(@Param("q") EconomyTaskQuoteEntity quote,@Param("sid") String sampleId);

    @Select("SELECT * FROM economy_task_charge WHERE " + SCOPE + " FOR UPDATE")
    EconomyTaskChargeEntity lockCharge(@Param("tenantId") String tenant, @Param("clientId") String client,
            @Param("ownerJiacn") String owner, @Param("taskId") String task);

    @Insert("""
            INSERT INTO economy_task_charge(tenant_id,client_id,owner_jiacn,task_id,quote_id,payer_user_id,
                amount_wen,state,created_at,updated_at)
            VALUES(#{tenantId},#{clientId},#{ownerJiacn},#{taskId},#{quoteId},#{payerUserId},
                #{amountWen},'RESERVING',#{createdAt},#{updatedAt})
            ON DUPLICATE KEY UPDATE task_id=task_id
            """)
    int insertChargeIfAbsent(EconomyTaskChargeEntity charge);

    @Update("UPDATE economy_task_charge SET state='HELD',reserve_transaction_id=#{reserveTransactionId},"
            + "escrow_version=#{escrowVersion},updated_at=#{updatedAt} WHERE " + SCOPE + " AND state='RESERVING'")
    int markHeld(EconomyTaskChargeEntity charge);

    @Update("UPDATE economy_task_charge SET state=#{state},terminal_transaction_id=#{terminalTransactionId},"
            + "escrow_version=#{escrowVersion},updated_at=#{updatedAt} WHERE " + SCOPE + " AND state='HELD'")
    int markTerminal(EconomyTaskChargeEntity charge);
}
