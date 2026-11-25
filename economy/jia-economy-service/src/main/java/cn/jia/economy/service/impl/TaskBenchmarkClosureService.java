package cn.jia.economy.service.impl;

import cn.jia.economy.mapper.TaskBenchmarkClosureMapper;
import cn.jia.economy.mapper.TaskMembershipReceiptMapper;
import cn.jia.economy.task.TaskBenchmarkAdmission;
import cn.jia.economy.task.TaskBenchmarkClosure;
import cn.jia.economy.task.TaskBillingService.Task;
import cn.jia.economy.usage.TaskMembership;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskBenchmarkClosureService implements TaskBenchmarkClosure {
    private final TaskBenchmarkClosureMapper db; private final TaskMembershipReceiptMapper members;
    public TaskBenchmarkClosureService(TaskBenchmarkClosureMapper db,TaskMembershipReceiptMapper members){this.db=db;this.members=members;}
    private static void require(boolean ok){if(!ok)throw new IllegalStateException("Benchmark closure conflict");}
    @Override @Transactional(propagation=Propagation.REQUIRES_NEW)
    public Step reconcile(Task task,String id,String sha,String fact){
        if(db.admitted(task,id,sha)==null)throw new TaskBenchmarkAdmission.NotAdmitted();
        var terminal=db.verified(task,fact);if(terminal==null)return Step.AWAITING_VERIFIED_TERMINAL;
        db.start(sha,fact);var cursor=db.lock(sha);require(cursor.fact().equals(fact));if(cursor.complete())return Step.CLOSED;
        var page=db.revisions(terminal,cursor.afterRevision(),TaskMembership.PAGE);
        long after=page.isEmpty()?cursor.afterRevision():page.getLast(),count=Math.addExact(cursor.count(),page.size());
        boolean done=count==terminal.memberCount();require(count<=terminal.memberCount() && (done?after==terminal.membershipRevision():page.size()==TaskMembership.PAGE && after<terminal.membershipRevision()));
        if(!page.isEmpty())require(members.attachReceived(sha,terminal,cursor.afterRevision(),after)==page.size());
        if(done)require(members.closeSample(sha,terminal)==1);
        require(db.advance(sha,fact,cursor.afterRevision(),after,count,done)==1);return done?Step.CLOSED:Step.MORE;
    }
}
