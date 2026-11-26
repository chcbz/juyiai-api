package cn.jia.economy.usage;

/** Existing background scheduler only. No online pricing access to private sources. */
public interface SharedGroupMaintenance {
    void runDue(String tenant,String client,ConfirmedSampleEvidence.Decoder decoder);
}
