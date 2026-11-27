package cn.jia.economy.task;

import cn.jia.economy.common.Money;
import java.util.*;
import java.util.function.Function;

/** Quote-only intent journal. All methods require the caller to have authorized access to this task. */
public interface TaskQuoteBatches {
    interface ScopeKey {TaskBillingService.Task task();String payer();}
    /** Offline correlation of the private capture roster. Not a capability to replay an HTTP request. */
    record ObservationKey(TaskBillingService.Task task,String payer,String idempotencyKeySha256) implements ScopeKey {public ObservationKey{
        Objects.requireNonNull(task);if(payer==null||payer.isBlank()||idempotencyKeySha256==null||!idempotencyKeySha256.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid capture binding");
    }}
    record Key(TaskBillingService.Task task,String payer,String idempotencyKey) implements ScopeKey {public Key{
        Objects.requireNonNull(task);if(payer==null||payer.isBlank()||idempotencyKey==null||idempotencyKey.isBlank())throw new IllegalArgumentException("Missing quote intent");
    }}
    record Intent(String expectedTaskVersion,List<String> agentIds){public Intent{
        if(expectedTaskVersion==null||!expectedTaskVersion.matches("0|[1-9][0-9]*")||agentIds==null||agentIds.isEmpty())throw new IllegalArgumentException("Invalid quote input");
        var ids=new TreeSet<String>();for(String id:agentIds){if(id==null||id.isBlank())throw new IllegalArgumentException("Missing candidate");ids.add(id);}agentIds=List.copyOf(ids);
    }}
    enum State{AVAILABLE,UNAVAILABLE}
    enum Reason{NO_BASELINE,OUT_OF_DISTRIBUTION,USAGE_INCOMPLETE,BILLING_ROUTE_MISSING,PRICE_MISSING,COST_INPUT_MISSING}
    record Summary(String method,String version,int sampleCount,String qualityStatus){}
    record Item(String agentId,State state,String quoteId,String recommendationId,Money amount,Reason unavailableReason,Summary estimateSummary){public Item{
        Objects.requireNonNull(agentId);Objects.requireNonNull(recommendationId);Objects.requireNonNull(state);
        if(state==State.AVAILABLE?(amount==null||quoteId==null||quoteId.isBlank()||unavailableReason!=null):(amount!=null||quoteId!=null||unavailableReason==null))throw new IllegalArgumentException("Incomplete quote result");
    }}
    record Result(String taskId,String taskVersion,long observedAt,List<Item> quotes){public Result{quotes=List.copyOf(quotes);}}
    final class IntentConflict extends RuntimeException {public IntentConflict(){super("QUOTE_IDEMPOTENCY_CONFLICT");}}
    Result replay(Key key,Intent intent);
    Result observed(ObservationKey key,Intent intent);
    /** Caller holds the original task root. Callback persists prepared items in this transaction, never does I/O to a provider.
     * It runs only for the winning new intent, and receives the batch recommendationId for binding all candidate quotes. */
    Result commit(Key key,Intent intent,Function<String,List<Item>> persistPrepared);
}
