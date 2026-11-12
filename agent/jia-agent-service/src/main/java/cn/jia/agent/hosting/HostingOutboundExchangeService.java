package cn.jia.agent.hosting;

import cn.jia.agent.mapper.hosting.HostingOutboundStore;
import cn.jia.agent.mapper.hosting.HostingOutboundStore.Grant;
import cn.jia.core.entity.ProtocolMessage;
import cn.jia.core.security.FixedTenantAdmissionPolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Set;

/** Transport only. Issuance and command production must join the authoritative onboarding/rent transaction.
 * No user-facing grant/command endpoint exists here; a transport ACK is NOT business readiness/capture. */
@Service
public final class HostingOutboundExchangeService {
    public static final String PATH = "/agent/runtime/v1/hosting/exchanges";
    private static final Set<String> METHODS = Set.of("capabilities", "inventory", "adopt", "prepare", "ensure", "observe", "current", "quiesce", "evidence", "cleanup");
    private static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private final HostingOutboundStore store;
    private final FixedTenantAdmissionPolicy scope;
    private final TransactionTemplate transactions;
    public HostingOutboundExchangeService(HostingOutboundStore store, FixedTenantAdmissionPolicy scope, PlatformTransactionManager manager) {
        this.store = store; this.scope = scope; this.transactions = new TransactionTemplate(manager);
    }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
    public record Reply(String authorizationId, String hostId, String acknowledgedMessageId, Map<String, Object> command) { }
    public record Request(String authorizationId, String hostId, String runtimeInstanceId, Map<String, Object> result) { }
    static HostingRentApplicationException denied() { return new HostingRentApplicationException(403, "HOSTING_OUTBOUND_REJECTED"); }
    private static HostingRentApplicationException conflict() { return new HostingRentApplicationException(409, "HOSTING_OUTBOUND_CONFLICT"); }
    public static void exact(String value, int bytes) {
        if (value == null || value.isBlank() || !value.equals(value.trim()) || value.getBytes(StandardCharsets.UTF_8).length > bytes
                || value.codePoints().anyMatch(Character::isISOControl)) throw denied();
    }
    public static byte[] tokenHash(String token) {
        if (token == null || !token.matches("rth1_[0-9a-f]{64}")) throw denied();
        try { return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void callerTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Hosting authority requires its business transaction");
    }
    /** Internal-only persistence primitive. Caller must already authorize the exact setup or reserved binding.
     * Hash is supplied so tokens need not be generated, retained or returned by this transport layer. */
    public void register(Grant grant, long now) {
        callerTransaction(); scope.requireAdmitted(grant.tenantId(), grant.clientId());
        exact(grant.tenantId(), 50); exact(grant.clientId(), 50); exact(grant.ownerJiacn(), 50);
        exact(grant.authorizationId(), 100); exact(grant.hostId(), 100); exact(grant.canonicalAgentId(), 100);
        if (!Set.of("SETUP", "CONTROL").contains(grant.purpose()) || !"ACTIVE".equals(grant.status()) || grant.tokenHash().length != 32 || now <= 0) throw denied();
        store.insert(grant, now); // Unique keys arbitrate concurrent creation; never rotate on a replay.
    }
    public void revoke(String tenant, String client, String authorizationId, long now) {
        callerTransaction(); scope.requireAdmitted(tenant, client);
        Grant grant = store.lock(tenant, client, authorizationId);
        if (grant == null) throw denied();
        if ("ACTIVE".equals(grant.status()) && store.revoke(grant, now) != 1) throw conflict();
    }
    public void enqueue(String tenant, String client, String authorizationId, Map<String, Object> message, long now) {
        callerTransaction(); scope.requireAdmitted(tenant, client);
        Grant grant = store.lock(tenant, client, authorizationId); requireActive(grant);
        var command = command(grant, message);
        var existing = store.command(grant, command.messageId());
        if (existing != null) {
            if (!parse(existing.commandJson()).equals(message)) throw conflict();
            return;
        }
        store.enqueue(grant, command.messageId(), json(message), now);
    }
    public Reply exchange(String token, Request request, long now) {
        if (request == null) throw denied();
        exact(request.authorizationId(), 100); exact(request.hostId(), 100); exact(request.runtimeInstanceId(), 100);
        byte[] hash = tokenHash(token);
        return transactions.execute(status -> {
            Grant grant = store.authenticateForUpdate(scope.businessTenantId(), scope.clientId(), request.authorizationId(), hash);
            requireActive(grant); scope.requireAdmitted(grant.tenantId(), grant.clientId());
            if (!grant.hostId().equals(request.hostId())) throw denied();
            String acknowledgment = null;
            if (request.result() != null) {
                var result = ProtocolMessage.decode(request.result());
                exact(result.messageId(), 100); exact(result.correlationId(), 100);
                var original = store.command(grant, result.correlationId());
                if (original == null) throw denied();
                var originalMessage = parse(original.commandJson());
                validateResult(grant, command(grant, originalMessage), request.result());
                if (original.resultJson() != null) {
                    if (!parse(original.resultJson()).equals(request.result())) throw conflict();
                } else {
                    // A result for an undispatched later command cannot skip the head.
                    var head = store.pending(grant);
                    if (head == null || head.id() != original.id()) throw conflict();
                    if (store.complete(grant, original, result.messageId(), json(request.result()), now) != 1) throw conflict();
                }
                acknowledgment = result.messageId();
            }
            var next = store.pending(grant);
            Map<String, Object> nextMessage = next == null ? null : parse(next.commandJson());
            if (nextMessage != null) command(grant, nextMessage);
            return new Reply(grant.authorizationId(), grant.hostId(), acknowledgment, nextMessage);
        }); // Only a committed result produces an HTTP ACK. No network wait under SQL locks.
    }
    private static void requireActive(Grant grant) { if (grant == null || !"ACTIVE".equals(grant.status())) throw denied(); }
    private static ProtocolMessage<Object> command(Grant grant, Map<String, Object> root) {
        var message = ProtocolMessage.decode(root);
        exact(message.messageId(), 100);
        if (message.correlationId() != null || !message.messageType().startsWith("hosting.")) throw denied();
        String method = message.messageType().substring("hosting.".length());
        if (!METHODS.contains(method) || "SETUP".equals(grant.purpose()) && !Set.of("capabilities", "inventory").contains(method)) throw denied();
        subject(grant, message.data(), method.equals("capabilities"));
        return message;
    }
    private static void subject(Grant grant, Object data, boolean capabilities) {
        if (!(data instanceof Map<?, ?> fields) || !grant.tenantId().equals(fields.get("tenantId"))
                || !grant.clientId().equals(fields.get("clientId")) || !grant.ownerJiacn().equals(fields.get("ownerJiacn"))
                || !capabilities && !grant.canonicalAgentId().equals(fields.get("canonicalAgentId"))) throw denied();
    }
    private static void validateResult(Grant grant, ProtocolMessage<?> command, Map<String, Object> result) {
        try {
            // Reuse the existing lifecycle M codec; changing transport does not change outcome semantics.
            var decoded = UnixManagedHostingProvisioner.hostingResponse(command, result);
            subject(grant, decoded, command.messageType().equals("hosting.capabilities"));
            if (!grant.hostId().equals(decoded.get("hostId"))) throw denied();
        } catch (java.io.IOException invalid) { throw denied(); }
    }
    static Map<String, Object> parse(String bytes) { return JSON.readValue(bytes, MAP); }
    static String json(Map<String, Object> message) { ProtocolMessage.decode(message); return JSON.writeValueAsString(message); }
}
