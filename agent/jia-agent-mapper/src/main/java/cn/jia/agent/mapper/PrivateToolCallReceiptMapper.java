package cn.jia.agent.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Bound to the execution transaction. No upsert: failed inserts must roll back domain writes. */
public interface PrivateToolCallReceiptMapper {
    record Key(String tenant,String client,String owner,String execution,String run,String call) {}
    record Receipt(String tool,String inputSha256,String receiptJson) {}
    String SELECT = """
        SELECT tool_name AS tool, input_sha256 AS inputSha256, CAST(receipt_json AS CHAR) AS receiptJson
        FROM agent_private_tool_call_receipt
        WHERE tenant_id=#{k.tenant} AND client_id=#{k.client} AND owner_jiacn=#{k.owner}
        AND execution_id=#{k.execution} AND run_id=#{k.run} AND call_id=#{k.call}
        """;
    @Select(SELECT) Receipt find(@Param("k") Key key);
    @Select(SELECT+" FOR UPDATE") Receipt lock(@Param("k") Key key);
    @Insert("""
        INSERT INTO agent_private_tool_call_receipt
        (tenant_id,client_id,owner_jiacn,execution_id,run_id,call_id,tool_name,input_sha256,receipt_json,created_at)
        VALUES(#{k.tenant},#{k.client},#{k.owner},#{k.execution},#{k.run},#{k.call},#{r.tool},#{r.inputSha256},#{r.receiptJson},#{now})
        """) int insert(@Param("k") Key key,@Param("r") Receipt receipt,@Param("now") long now);
}
