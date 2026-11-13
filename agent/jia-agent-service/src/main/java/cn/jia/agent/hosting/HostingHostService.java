package cn.jia.agent.hosting;

import cn.jia.agent.mapper.hosting.HostingHostStore;
import cn.jia.agent.mapper.hosting.HostingHostStore.Host;
import cn.jia.core.security.FixedTenantAdmissionPolicy;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import static cn.jia.agent.hosting.HostingRentHttp.*;

/** Host registration only. No installation, rent or Runtime connection is created here. */
public final class HostingHostService {
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
    public record View(String hostId, String mode, String tenantId, String clientId, String ownerJiacn, String tokenSha256) { }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
    public record Page(List<View> items, String nextCursor, boolean hasMore) { }
    private final HostingHostStore store;
    private final FixedTenantAdmissionPolicy scope;
    private final HostingRentOwnerResolver owners;
    private final TransactionTemplate transactions;
    public HostingHostService(HostingHostStore store, FixedTenantAdmissionPolicy scope, HostingRentOwnerResolver owners,
                              PlatformTransactionManager manager) {
        this.store=store; this.scope=scope; this.owners=owners; this.transactions=new TransactionTemplate(manager);
    }
    public static View view(Host h) {
        return new View(h.hostId(),h.mode(),h.tenantId(),h.clientId(),h.ownerJiacn(),HexFormat.of().formatHex(h.tokenHash()));
    }
    public View register(Actor actor, String idempotencyKey, Map<String,String> body) {
        scope.requireAdmitted(actor.tenantId(),actor.clientId()); owners.requireOwner(actor); key(idempotencyKey);
        String host=required(body,"hostId"), digest=required(body,"tokenSha256");
        if (!digest.matches("[0-9a-f]{64}")) throw badRequest();
        return register(new Host(actor.tenantId(),actor.clientId(),host,"local",actor.ownerJiacn(),
                HexFormat.of().parseHex(digest),"ACTIVE",idempotencyKey,hash("hosting-host-register",body)));
    }
    /** Operator maintenance only; no HTTP endpoint accepts a server/owner override. */
    public View registerManaged(String tenant, String client, String host, String digest, String idempotencyKey) {
        scope.requireAdmitted(tenant,client); exact(host,100); key(idempotencyKey);
        if (digest == null || !digest.matches("[0-9a-f]{64}")) throw badRequest();
        return register(new Host(tenant,client,host,"server",null,HexFormat.of().parseHex(digest),"ACTIVE",idempotencyKey,
                hash("hosting-host-register-managed",Map.of("hostId",host,"tokenSha256",digest))));
    }
    private View register(Host next) {
        try {
            return transactions.execute(tx -> {
                Host replay=store.replay(next.tenantId(),next.clientId(),next.ownerJiacn(),next.idempotencyKey());
                if (replay!=null && !MessageDigest.isEqual(replay.requestHash(),next.requestHash())) throw conflict();
                Host existing=replay == null ? store.find(next.tenantId(),next.clientId(),next.hostId()) : replay;
                if (existing!=null) {
                    if (!existing.hostId().equals(next.hostId()) || !existing.mode().equals(next.mode())
                            || !Objects.equals(existing.ownerJiacn(),next.ownerJiacn())
                            || !MessageDigest.isEqual(existing.tokenHash(),next.tokenHash()) || !"ACTIVE".equals(existing.status())) throw conflict();
                    return view(existing);
                }
                store.insert(next,System.currentTimeMillis()); return view(next);
            });
        } catch (org.springframework.dao.DuplicateKeyException duplicate) { throw conflict(); }
    }
    public Page list(Actor actor, String cursor, int pageSize) {
        scope.requireAdmitted(actor.tenantId(),actor.clientId()); owners.requireOwner(actor);
        String after=cursor(cursor); pageSize(pageSize);
        var rows=store.owned(actor.tenantId(),actor.clientId(),actor.ownerJiacn(),after,pageSize+1);
        boolean more=rows.size()>pageSize; var items=rows.stream().limit(pageSize).map(HostingHostService::view).toList();
        return new Page(items,more ? items.getLast().hostId() : null,more);
    }
    public static String cursor(String cursor) {
        if (cursor==null) return "";
        try { exact(cursor,100); return cursor; } catch (IllegalArgumentException invalid) { throw badRequest(); }
    }
    public static int pageSize(int value) { if (value<1 || value>100) throw badRequest(); return value; }
    private static HostingRentApplicationException conflict() { return new HostingRentApplicationException(409,"HOSTING_OUTBOUND_CONFLICT"); }
}
