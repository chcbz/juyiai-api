package cn.jia.economy.service.impl;

import cn.jia.economy.mapper.TaskMembershipReceiptMapper;
import cn.jia.economy.usage.TaskMembershipDigest;
import cn.jia.economy.usage.TaskMembershipReceipt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.List;
import cn.jia.economy.usage.TaskMembership.*;
import static cn.jia.economy.usage.TaskMembership.PAGE;

/** One bounded independent transaction per page. Lost ACK resumes the durable prefix; no contact with Agent DB. */
@Service
@Transactional(propagation=Propagation.REQUIRES_NEW)
public class TaskMembershipReceiptStore implements TaskMembershipReceipt {
    private final TaskMembershipReceiptMapper db;
    public TaskMembershipReceiptStore(TaskMembershipReceiptMapper db){this.db=db;}
    private static void require(boolean ok){if(!ok)throw new IllegalStateException("Task membership commitment conflict");}
    private Cursor lock(Terminal t){var c=db.lock(t);require(c!=null);return c;}
    public Cursor open(Terminal t){
        require(("ACCEPTED".equals(t.outcome())||"CANCELLED".equals(t.outcome())) && t.memberCount()>=0 && t.membershipRevision()>=t.memberCount());
        db.ensure(t,TaskMembershipDigest.EMPTY);return lock(t);
    }
    public Cursor accept(Terminal t,long expectedCursor,List<Member> page){
        var c=lock(t);require(expectedCursor>=0 && expectedCursor<=c.revision() && page.size()<=PAGE);
        if(c.verified() || c.revision()!=expectedCursor)return c;
        long count=Math.addExact(c.count(),page.size()),revision=c.revision();String digest=c.digest();require(count<=t.memberCount());
        for(var m:page){require(m.revision()>revision && m.revision()<=t.membershipRevision());revision=m.revision();
            digest=TaskMembershipDigest.next(digest,TaskMembershipDigest.member(t.tenant(),t.client(),t.owner(),t.task(),m.execution(),m.run(),m.capture(),m.admission()));}
        boolean last=count==t.memberCount();
        if(last)require(revision==t.membershipRevision() && digest.equals(t.digest()));
        else require(!page.isEmpty() && revision<t.membershipRevision());
        var next=new Cursor(revision,count,digest,last);if(!page.isEmpty())require(db.add(t,page)==page.size());
        require(db.advance(t,c.revision(),next)==1);return next;
    }
}
