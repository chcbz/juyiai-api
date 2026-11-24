package cn.jia.agent.service.recommendation;

import cn.jia.agent.service.TaskInitialActionSelection.Selected;
import java.util.List;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/** Best-effort warming only. A selection receipt never asserts that an observation exists. */
@Component
public final class SelectedTaskComparison {
    private final Executor worker;private final PreparedTaskTermsReader reader;
    public SelectedTaskComparison(@Qualifier("taskExecutor") Executor worker,PreparedTaskTermsReader reader){this.worker=worker;this.reader=reader;}
    @TransactionalEventListener public void committed(Selected selection){var caller=Thread.currentThread();
        try{worker.execute(()->{if(Thread.currentThread()==caller){warn();return;} // shared pool uses CallerRuns under pressure
            try{reader.refresh(selection.scope(),selection.task(),selection.taskVersion(),List.of(selection.agent()));}catch(RuntimeException unavailable){warn();}
        });}catch(java.util.concurrent.RejectedExecutionException unavailable){warn();}
    }
    private static void warn(){org.slf4j.LoggerFactory.getLogger(SelectedTaskComparison.class).warn("Initial task configuration warm-up unavailable");}
}
