package cn.jia.agent.service.recommendation;

import cn.jia.agent.service.TaskInitialActionSelection.Selected;
import cn.jia.agent.service.NativeTaskPreparation.Binding;
import java.util.Set;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/** Best-effort warming only. A selection receipt never asserts that an observation exists. */
@Component
public final class SelectedTaskComparison {
    private final Executor worker;private final PreparedTaskTermsReader reader;
    public SelectedTaskComparison(@Qualifier("taskExecutor") Executor worker,PreparedTaskTermsReader reader){this.worker=worker;this.reader=reader;}
    private final Set<Set<Binding>> queued=java.util.concurrent.ConcurrentHashMap.newKeySet();
    @TransactionalEventListener public void committed(Selected selection){request(Set.of(selection.binding()));}
    public void request(Set<Binding> bindings){
        if(bindings.isEmpty())return;var batch=Set.copyOf(bindings);if(!queued.add(batch))return;
        var caller=Thread.currentThread();
        try{worker.execute(()->{try{if(Thread.currentThread()==caller){warn();return;} // shared pool uses CallerRuns under pressure
            reader.refreshDue(batch);}catch(RuntimeException unavailable){warn();}finally{queued.remove(batch);}
        });}catch(java.util.concurrent.RejectedExecutionException unavailable){queued.remove(batch);warn();}
    }
    private static void warn(){org.slf4j.LoggerFactory.getLogger(SelectedTaskComparison.class).warn("Initial task configuration warm-up unavailable");}
}
