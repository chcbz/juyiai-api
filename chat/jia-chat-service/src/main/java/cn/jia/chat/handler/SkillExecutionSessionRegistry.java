package cn.jia.chat.handler;

import cn.jia.agent.service.AgentManagedSessionLookup.SessionFence;
import cn.jia.agent.service.SkillCapabilityLookup;
import org.springframework.stereotype.Component;

import cn.jia.agent.security.AgentRuntimeAuthenticationService.RecommendationChannel;
import java.util.function.Predicate;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/** Transient live directory only. Registration caller supplies installation-derived scope/fence
 * and a live-binding predicate, never identities or permissions taken from the skill report.
 * The actual authenticated WS registration adapter is a separate integration step. */
@Component
public final class SkillExecutionSessionRegistry implements cn.jia.agent.skill.RecommendationCatalogLookup, cn.jia.agent.service.SkillCatalogIndexHints {
    private record Key(String sessionId, String agentId) { }
    private record Entry(Key key, Scope scope, SessionFence fence, Catalog catalog, cn.jia.agent.security.AgentRuntimeAuthenticationService.Receipt receipt, Predicate<RecommendationChannel> current) { }
    // Writers own the reverse key index under this monitor. Readers use immutable per-subject
    // snapshots; a slow native authority callback must not hold the registration monitor.
    private final java.util.Map<Key,Entry> entries = new java.util.HashMap<>();
    private final ConcurrentHashMap<Scope,java.util.List<Entry>> subjects = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<Key,Entry> watermarks=new ConcurrentHashMap<>();

    /** Null catalog records absence/invalidity and replaces any previously valid directory. */
    public synchronized void register(String sessionId, Scope trustedScope, SessionFence trustedFence,
            Catalog catalog, BooleanSupplier currentBinding) {
        Objects.requireNonNull(currentBinding);
        registerObserved(sessionId,trustedScope,trustedFence,catalog,null,ignored -> currentBinding.getAsBoolean());
    }

    public synchronized void registerObserved(String sessionId, Scope trustedScope, SessionFence trustedFence,
            Catalog catalog, cn.jia.agent.security.AgentRuntimeAuthenticationService.Receipt receipt, Predicate<RecommendationChannel> currentBinding) {
        if (sessionId == null || sessionId.isBlank() || !sessionId.equals(sessionId.strip())
                || sessionId.codePointCount(0, sessionId.length()) > 128
                || sessionId.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid skill session ID");
        Objects.requireNonNull(trustedScope); SkillCapabilityLookup.validateFence(trustedFence);
        var key = new Key(sessionId, trustedScope.agentId());
        var previous=watermarks.get(key);
        if(previous!=null && previous.fence().equals(trustedFence) && previous.catalog()!=null && catalog!=null) {
            int order=new java.math.BigInteger(catalog.catalogRevision()).compareTo(new java.math.BigInteger(previous.catalog().catalogRevision()));
            if(order<0 || order==0&&!sameMeasuredFacts(previous.catalog(),catalog)) throw new IllegalArgumentException("Skill catalog revision conflict");
        }
        var entry=new Entry(key,trustedScope,trustedFence,catalog,receipt,Objects.requireNonNull(currentBinding));
        var old=entries.put(key,entry);
        if(old!=null && !trustedScope.equals(old.scope()))unlink(old);
        subjects.compute(trustedScope,(unused,rows)->{
            var next=new java.util.ArrayList<Entry>();
            if(rows!=null)for(var row:rows)if(row!=old)next.add(row);
            next.add(entry);return java.util.List.copyOf(next);
        });
        if(catalog!=null)watermarks.put(key,entry);
    }

    private static boolean sameMeasuredFacts(Catalog previous,Catalog next) {
        if(previous.skills().size()!=next.skills().size())return false;
        for(var before:previous.skills()) {
            var matches=next.skills().stream().filter(row->Objects.equals(before.skillInstallationId(),row.skillInstallationId())
                    &&before.skillKey().equals(row.skillKey())&&before.skillVersion().equals(row.skillVersion())).toList();
            if(matches.size()!=1)return false;var after=matches.getFirst();
            // Runtime revision fingerprints configuration/readiness, not the time of a
            // repeated measurement. Accept newer observation time only; every exact
            // installation, digest, policy, binding and readiness fact stays fenced.
            if(before.observedAt()!=null&&(after.observedAt()==null||after.observedAt()<before.observedAt()))return false;
            if(!before.sameExecutionFacts(after))return false;
        }
        return true;
    }

    public synchronized void suspend(String sessionId) {
        if(sessionId!=null)entries.values().removeIf(entry->{
            if(!sessionId.equals(entry.key().sessionId()))return false;
            unlink(entry);return true;
        });
    }
    private void unlink(Entry entry){
        subjects.computeIfPresent(entry.scope(),(unused,rows)->{
            var next=rows.stream().filter(row->row!=entry).toList();return next.isEmpty()?null:next;
        });
    }
    public synchronized void remove(String sessionId) {
        suspend(sessionId);
        if(sessionId!=null)watermarks.entrySet().removeIf(e->sessionId.equals(e.getKey().sessionId()));
    }

    @Override public View lookup(Scope scope, SessionFence expectedFence) {
        return read(scope, expectedFence, null);
    }
    @Override public View forRecommendation(Scope scope, SessionFence expectedFence, RecommendationChannel channel) {
        return read(scope, expectedFence, Objects.requireNonNull(channel));
    }
    private View read(Scope scope, SessionFence expectedFence, RecommendationChannel channel) {
        Objects.requireNonNull(scope); SkillCapabilityLookup.validateFence(expectedFence);
        var snapshot=subjects.get(scope);
        if(snapshot==null)return View.unavailable(State.DISCONNECTED);
        Entry selected = null;
        for (var entry : snapshot) {
            if (!current(entry,channel)) continue;
            if (selected != null) return View.unavailable(State.AMBIGUOUS);
            selected = entry;
        }
        if (selected == null) return View.unavailable(State.DISCONNECTED);
        if (!selected.fence().equals(expectedFence) || !current(selected,channel)
                || (channel != null && (!channel.issued(selected.receipt()) || !channel.matches(selected.key().sessionId(),scope.tenantId(),scope.clientId(),
                        scope.ownerJiacn(),scope.agentId(),expectedFence.runtimeInstanceId()))) || subjects.get(scope) != snapshot)
            return View.unavailable(State.FENCED);
        if (selected.catalog() == null) return View.unavailable(State.INVALID_DECLARATION);
        return new View(State.AVAILABLE, selected.key().sessionId(), selected.fence(), selected.catalog());
    }

    @Override public java.util.List<cn.jia.agent.service.SkillCatalogIndexHints.Key> local(String tenant,String optionalClient){
        // Weakly consistent hints. lookup() on the eventual claim rechecks the live predicate and ambiguity.
        return subjects.keySet().stream().filter(s->tenant.equals(s.tenantId())&&(optionalClient==null||optionalClient.equals(s.clientId())))
                .map(s->new cn.jia.agent.service.SkillCatalogIndexHints.Key(s.tenantId(),s.clientId(),s.agentId())).distinct().toList();
    }
    private static boolean current(Entry entry, RecommendationChannel channel) {
        try { return entry.current().test(channel!=null && channel.issued(entry.receipt()) ? channel : null); }
        catch (RuntimeException unavailable) { return false; }
    }
}
