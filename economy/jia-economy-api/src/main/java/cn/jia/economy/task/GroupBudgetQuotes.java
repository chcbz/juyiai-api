package cn.jia.economy.task;

import cn.jia.economy.service.EconomyScope;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

/** Actor identity and execution grants are not inputs to shared cost statistics. */
public interface GroupBudgetQuotes {
    record Key(String groupSha) {}
    record Pin(Key key,long bookEpoch,long generation,long revision,String tariffSha,String policySha,int n,BigDecimal sumYuan) {}
    sealed interface Decision permits Mean,Fixed {}
    record Mean(Pin pin,long amountWen) implements Decision {}
    record Fixed(TaskQuoteBatches.Reason reason) implements Decision {}
    Map<Key,Decision> prepare(EconomyScope domain,Set<Key> keys);
    void lockSelected(EconomyScope domain,Collection<Pin> pins);
}
