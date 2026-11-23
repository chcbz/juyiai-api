package cn.jia.economy.usage;

import java.util.List;
import cn.jia.economy.usage.TaskMembership.*;

/** Trusted in-process lifecycle handoff; closure verifies membership, not usage, price or sample eligibility. */
public interface TaskMembershipReceipt {
    /** Returns committed resume point. A blank receipt is not a completed zero-member task. */
    Cursor open(Terminal terminal);
    Cursor accept(Terminal terminal,long expectedCursor,List<Member> orderedPage);
}
