package cn.jia.economy.usage;

/** Immutable membership wire facts, shared by the authoritative producer and recipient. */
public final class TaskMembership {
    private TaskMembership() {}
    public static final int PAGE=64;
    public record Pending(String client,String fact) {}
    public record Terminal(String tenant,String client,String owner,String task,String fact,String outcome,long terminalRevision,
                    long closedAt,long membershipRevision,long memberCount,String digest) {}
    public record Member(long revision,String execution,String run,String capture,String admission) {}
    public record Cursor(long revision,long count,String digest,boolean verified) {}
}
