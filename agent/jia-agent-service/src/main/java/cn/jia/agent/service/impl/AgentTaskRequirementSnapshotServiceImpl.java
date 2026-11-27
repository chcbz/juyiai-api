package cn.jia.agent.service.impl;

import cn.jia.agent.service.AgentTaskExecutionGrantService;
import cn.jia.agent.service.AgentTaskRequirementConfirmationActor;
import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.dao.impl.AgentTaskScopedRequirementStore;
import cn.jia.agent.requirement.ConfirmedScope;
import org.springframework.beans.factory.ObjectProvider;
import cn.jia.agent.exception.AgentTaskCollaborationException;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.agent.service.AgentTaskRequirementReadException;
import cn.jia.agent.service.AgentTaskRequirementSnapshotService;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Current requirements are mutable; exact creation/assignment snapshots remain immutable. */
@Named
public final class AgentTaskRequirementSnapshotServiceImpl implements AgentTaskRequirementSnapshotService {
    private final JdbcTemplate jdbc;
    private final AgentTaskMutationTransaction transactions;
    private final AgentTaskCurrentRequirementStore currentRequirements;
    private final AgentTaskScopedRequirementStore scopedRequirements;
    private ObjectProvider<AgentTaskExecutionGrantService> inputCatalogues;
    private AgentTaskMetaDao taskRoots;
    private AgentTaskRequirementConfirmationActor confirmationActor;
    private cn.jia.agent.skill.PublishedCapabilityQuoteSpecs capabilitySpecifications;

    @Inject void capabilitySpecifications(cn.jia.agent.skill.PublishedCapabilityQuoteSpecs specifications) {
        this.capabilitySpecifications=Objects.requireNonNull(specifications);
    }


    @Inject void confirmationActor(AgentTaskRequirementConfirmationActor actor) {
        this.confirmationActor=Objects.requireNonNull(actor);
    }

    /** Lazy catalogue dependency avoids the existing grant -> requirements constructor cycle. */
    @Inject void scopeDependencies(ObjectProvider<AgentTaskExecutionGrantService> catalogues,AgentTaskMetaDao tasks) {
        this.inputCatalogues=catalogues;
        this.taskRoots=tasks;
    }

    @Inject
    public AgentTaskRequirementSnapshotServiceImpl(JdbcTemplate jdbc, AgentTaskMutationTransaction transactions) {
        this.jdbc=Objects.requireNonNull(jdbc);
        this.transactions=Objects.requireNonNull(transactions);
        this.currentRequirements=new AgentTaskCurrentRequirementStore(jdbc);
        this.scopedRequirements=new AgentTaskScopedRequirementStore(jdbc);
    }

    @Override public Snapshot captureOnCreate(AgentTaskExecutionGrantService.Scope scope, String taskId,
            String title, String description) {
        validReadScope(scope,taskId);
        writeTransaction();
        if (latest(scope,taskId,true)!=null) throw new IllegalStateException("Requirement snapshot already exists");
        var created=insert(scope,taskId,1,0,"CREATE",title,description,"CREATE");
        currentRequirements.insert(created);
        return created;
    }

    private void requireAuthenticatedOwner(AgentTaskExecutionGrantService.Scope scope) {
        if (confirmationActor==null) throw new IllegalStateException("Confirmation security boundary unavailable");
        var owner=confirmationActor.authenticatedOwner();
        if (owner==null || !scope.ownerJiacn().equals(owner.ownerJiacn())
                || !scope.clientId().equals(owner.clientId()))
            throw new IllegalArgumentException("Authenticated task owner required for re-confirmation");
    }

    @Override public Snapshot confirmScope(AgentTaskExecutionGrantService.Scope scope,String taskId,
            long expectedTaskVersion,String confirmationId,ConfirmScopeCommand command) {
        validScope(scope,taskId);
        requireAuthenticatedOwner(scope);
        if (command==null || command.payload()==null || expectedTaskVersion<0
                || confirmationId==null || !confirmationId.matches("[A-Za-z0-9_-]{1,100}")
                || "CREATE".equals(confirmationId)) throw new IllegalArgumentException("Invalid scope confirmation");
        validateOriginalInput(command.title(),command.description());
        return transactions.executeWithLockedTaskRootInOwnerScope(scope.tenantId(),scope.clientId(),
                scope.ownerJiacn(),taskId,root -> {
                    writeTransaction();
                    Snapshot previous=currentRequirements.read(scope,taskId);
                    var repeated=jdbc.query("SELECT revision FROM agent_task_requirement_snapshot "
                            +"WHERE tenant_id=? AND client_id=? AND owner_jiacn=? AND task_id=? AND confirmation_id=? "
                            +"AND CAST(confirmation_id AS BINARY)=CAST(? AS BINARY)",
                            (r,n)->r.getLong(1),scope.tenantId(),scope.clientId(),scope.ownerJiacn(),taskId,
                            confirmationId,confirmationId);
                    if (!repeated.isEmpty()) {
                        if (repeated.size()!=1) throw new IllegalStateException("Ambiguous scope confirmation");
                        var prior=read(scope,taskId,repeated.getFirst());
                        var confirmed=prior.confirmedScope();
                        if (confirmed==null || previous==null || previous.revision()!=prior.revision()
                                || scopedRequirements.confirmationVersion(scope,taskId,confirmationId)!=expectedTaskVersion
                                || !Objects.equals(prior.title(),command.title())
                                || !Objects.equals(prior.description(),command.description())
                                || !confirmed.scopeSha256().equals(command.payload().digest(confirmed.inputSnapshotDigest())))
                            throw new IllegalStateException("Scope confirmation idempotency conflict");
                        return prior;
                    }
                    if (root.getTaskVersion()==null || root.getTaskVersion()!=expectedTaskVersion
                            || expectedTaskVersion==Long.MAX_VALUE || previous==null)
                        throw new IllegalStateException("Scope confirmation version conflict");
                    requireUnfunded(scope,taskId);
                    if (!"open".equals(root.getRewardStatus()) || root.getAssignedAt()!=null
                            || root.getStartedAt()!=null || root.getAssignedAgentId()!=null && !root.getAssignedAgentId().isBlank())
                        throw new IllegalStateException("Task scope no longer editable");
                    if (inputCatalogues==null || taskRoots==null) throw new IllegalStateException("Scope dependencies unavailable");
                    var catalogue=inputCatalogues.getObject().snapshotTaskInputsWithinLockedTask(scope,taskId);
                    var refs=new java.util.HashSet<String>();
                    for (var input:catalogue.inputs()) refs.add(input.fileId()+"@"+input.version());
                    for (var item:command.payload().workItems())
                        if (!refs.containsAll(item.inputRefs())) throw new IllegalArgumentException("Scope input is not authorized");
                    if (capabilitySpecifications==null) throw new IllegalStateException("Published scope specifications unavailable");
                    capabilitySpecifications.validateForConfirmation(scope.tenantId(),scope.clientId(),command.payload().workItems());
                    long revision=Math.addExact(previous.revision(),1);
                    var payload=command.payload();
                    String requirementSha=payload.requirementDigest(command.title(),command.description(),catalogue.digest());
                    var confirmed=new ConfirmedScope(revision,requirementSha,payload.digest(catalogue.digest()),
                            catalogue.digest(),payload.schemaVersion(),System.currentTimeMillis(),payload.workItems(),payload.acceptanceText());
                    var row=new Snapshot(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),taskId,revision,
                            command.title(),command.description(),requirementSha,"SCOPED",confirmed);
                    String json=ConfirmedScopeJson.write(confirmed);
                    scopedRequirements.insert(row,confirmationId,expectedTaskVersion,json);
                    scopedRequirements.replaceCurrent(row,previous.revision(),json);
                    if (taskRoots.advanceRequirementVersionInOwnerScope(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),
                            taskId,expectedTaskVersion)!=1) throw new IllegalStateException("Scope task version changed");
                    return row;
                });
    }

    @Override public ConfirmedScope readConfirmedWithinLockedTask(AgentTaskExecutionGrantService.Scope scope,
            String taskId,cn.jia.agent.entity.AgentTaskMetaEntity root) {
        var current=readCurrentWithinLockedTask(scope,taskId,root);
        if (current.confirmedScope()==null) throw new IllegalStateException("REQUIREMENT_SCOPE_REQUIRED");
        return current.confirmedScope();
    }

    private void requireUnfunded(AgentTaskExecutionGrantService.Scope scope,String taskId) {
        Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM agent_task_funding "
                +"WHERE tenant_id=? AND client_id=? AND owner_jiacn=? AND task_id=?",Integer.class,
                scope.tenantId(),scope.clientId(),scope.ownerJiacn(),taskId);
        if (count==null || count!=0) throw new IllegalStateException("Funded task scope is immutable");
    }

    @Override public Snapshot reconfirm(AgentTaskExecutionGrantService.Scope scope, String taskId,
            long expectedTaskVersion, String confirmationId, String title, String description) {
        validScope(scope,taskId);
        requireAuthenticatedOwner(scope);
        if (expectedTaskVersion<0 || confirmationId==null || !confirmationId.matches("[A-Za-z0-9_-]{1,100}")
                || "CREATE".equals(confirmationId))
            throw new IllegalArgumentException("Confirmation identity/version invalid");
        // Lock order: task root -> funding guard -> latest snapshot -> append. No grants/outbox locks.
        return transactions.executeWithLockedTaskRootInOwnerScope(scope.tenantId(),scope.clientId(),
                scope.ownerJiacn(),taskId,root -> {
                    writeTransaction();
                    Snapshot previous=currentRequirements.read(scope,taskId);
                    List<Snapshot> repeated=jdbc.query("SELECT tenant_id,client_id,owner_jiacn,task_id,revision,title,description,"
                                    + "content_sha256,source,"+ConfirmedScopeJson.COLUMNS+" FROM agent_task_requirement_snapshot WHERE tenant_id=? "
                                    + "AND client_id=? AND owner_jiacn=? AND task_id=? AND confirmation_id=? "
                                    + "AND CAST(tenant_id AS BINARY)=CAST(? AS BINARY) "
                                    + "AND CAST(client_id AS BINARY)=CAST(? AS BINARY) "
                                    + "AND CAST(owner_jiacn AS BINARY)=CAST(? AS BINARY) "
                                    + "AND CAST(task_id AS BINARY)=CAST(? AS BINARY) "
                                    + "AND CAST(confirmation_id AS BINARY)=CAST(? AS BINARY) LIMIT 2",
                            (rs,n)->new Snapshot(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),
                                    rs.getLong(5),rs.getString(6),rs.getString(7),rs.getString(8),rs.getString(9),
                                ConfirmedScopeJson.read(rs,rs.getLong(5),rs.getString(8))),
                            scope.tenantId(),scope.clientId(),scope.ownerJiacn(),taskId,confirmationId,
                            scope.tenantId(),scope.clientId(),scope.ownerJiacn(),taskId,confirmationId);
                    if (!repeated.isEmpty()) {
                        if (repeated.size()!=1) throw new IllegalStateException("Ambiguous confirmation");
                        Snapshot prior=verified(repeated.getFirst(),scope,taskId);
                        Long originalVersion=jdbc.queryForObject("SELECT task_version_at_confirmation "
                                + "FROM agent_task_requirement_snapshot WHERE tenant_id=? AND client_id=? "
                                + "AND owner_jiacn=? AND task_id=? AND confirmation_id=? AND revision=?",
                                Long.class,scope.tenantId(),scope.clientId(),scope.ownerJiacn(),
                                taskId,confirmationId,prior.revision());
                        if (originalVersion==null || originalVersion!=expectedTaskVersion
                                || previous==null || prior.revision()!=previous.revision()
                                || !Objects.equals(prior.title(),title)
                                || !Objects.equals(prior.description(),description))
                            throw new IllegalStateException("Confirmation idempotency conflict");
                        return prior;
                    }
                    if (root.getTaskVersion()==null || root.getTaskVersion()!=expectedTaskVersion)
                        throw new IllegalStateException("Task root version changed during confirmation");
                    Integer funded=jdbc.queryForObject("SELECT COUNT(*) FROM agent_task_funding "
                            + "WHERE tenant_id=? AND client_id=? AND owner_jiacn=? AND task_id=? "
                            + "AND CAST(tenant_id AS BINARY)=CAST(? AS BINARY) "
                            + "AND CAST(client_id AS BINARY)=CAST(? AS BINARY) "
                            + "AND CAST(owner_jiacn AS BINARY)=CAST(? AS BINARY) "
                            + "AND CAST(task_id AS BINARY)=CAST(? AS BINARY)", Integer.class,
                            scope.tenantId(),scope.clientId(),scope.ownerJiacn(),taskId,
                            scope.tenantId(),scope.clientId(),scope.ownerJiacn(),taskId);
                    if (funded==null || funded!=0) throw new IllegalStateException("Funded task confirmation is not authorized");
                    if (previous!=null && previous.revision()==Long.MAX_VALUE)
                        throw new IllegalStateException("Requirement revision exhausted");
                    var confirmed=insert(scope,taskId,previous==null?1:previous.revision()+1,
                            expectedTaskVersion,confirmationId,title,description,"RECONFIRM");
                    if (previous==null) currentRequirements.insert(confirmed);
                    else currentRequirements.replace(scope,taskId,previous.revision(),title,description);
                    if (previous!=null && previous.confirmedScope()!=null) {
                        if (taskRoots==null || expectedTaskVersion==Long.MAX_VALUE
                                || taskRoots.advanceRequirementVersionInOwnerScope(scope.tenantId(),scope.clientId(),
                                    scope.ownerJiacn(),taskId,expectedTaskVersion)!=1)
                            throw new IllegalStateException("Scope withdrawal version changed");
                    }
                    return confirmed;
                });
    }

    @Override public Snapshot read(AgentTaskExecutionGrantService.Scope scope,String taskId,long revision) {
        validReadScope(scope,taskId);
        if (revision<1) throw new IllegalArgumentException("requirement revision invalid");
        List<Snapshot> rows=jdbc.query("SELECT tenant_id,client_id,owner_jiacn,task_id,revision,title,description,"
                        + "content_sha256,source,"+ConfirmedScopeJson.COLUMNS+" FROM agent_task_requirement_snapshot "
                        + "WHERE tenant_id=? AND client_id=? AND owner_jiacn=? AND task_id=? AND revision=? "
                        + "AND CAST(tenant_id AS BINARY)=CAST(? AS BINARY) "
                        + "AND CAST(client_id AS BINARY)=CAST(? AS BINARY) "
                        + "AND CAST(owner_jiacn AS BINARY)=CAST(? AS BINARY) "
                        + "AND CAST(task_id AS BINARY)=CAST(? AS BINARY) LIMIT 2",
                (rs,n)->new Snapshot(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),
                        rs.getLong(5),rs.getString(6),rs.getString(7),rs.getString(8),rs.getString(9),
                                ConfirmedScopeJson.read(rs,rs.getLong(5),rs.getString(8))),
                scope.tenantId(),scope.clientId(),scope.ownerJiacn(),taskId,revision,
                scope.tenantId(),scope.clientId(),scope.ownerJiacn(),taskId);
        if (rows.size()!=1) throw new IllegalStateException("Exact requirement snapshot unavailable");
        return verified(rows.getFirst(),scope,taskId);
    }

    @Override public CurrentSnapshot readCurrent(
            AgentTaskExecutionGrantService.Scope scope, String taskId) {
        validReadScope(scope,taskId);
        return readSafely(() -> transactions.executeWithLockedTaskRootInOwnerScope(
                scope.tenantId(),scope.clientId(),scope.ownerJiacn(),taskId,root -> currentForRoot(scope,taskId,root)));
    }

    @Override public CurrentSnapshot readCurrentWithinLockedTask(AgentTaskExecutionGrantService.Scope scope,String taskId,
            cn.jia.agent.entity.AgentTaskMetaEntity root) {
        validReadScope(scope,taskId);
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Current requirement needs the caller's task transaction");
        return readSafely(() -> currentForRoot(scope,taskId,root));
    }

    private CurrentSnapshot currentForRoot(AgentTaskExecutionGrantService.Scope scope,String taskId,
            cn.jia.agent.entity.AgentTaskMetaEntity root) {
        if (root==null || !Objects.equals(root.getTenantId(),scope.tenantId())
                || !Objects.equals(root.getClientId(),scope.clientId())
                || !Objects.equals(root.getOwnerJiacn(),scope.ownerJiacn())
                || !Objects.equals(root.getTaskId(),taskId)
                || root.getTaskVersion()==null || root.getTaskVersion()<0) {
            throw new SnapshotIntegrityException();
        }
        Snapshot current=currentRequirements.read(scope,taskId);
        if (current==null) {
            throw new AgentTaskRequirementReadException(
                    AgentTaskRequirementReadException.Reason.RECONFIRM_REQUIRED);
        }
        return new CurrentSnapshot(taskId,root.getTaskVersion(),current.revision(),
                current.title(),current.description(),current.sha256(),current.source(),current.confirmedScope());
    }

    private static CurrentSnapshot readSafely(java.util.function.Supplier<CurrentSnapshot> read) {
        try {
            return read.get();
        } catch (AgentTaskRequirementReadException failure) {
            throw failure;
        } catch (SnapshotIntegrityException failure) {
            throw new AgentTaskRequirementReadException(
                    AgentTaskRequirementReadException.Reason.INTEGRITY_ERROR,failure);
        } catch (AgentTaskCollaborationException failure) {
            AgentTaskRequirementReadException.Reason reason=switch (failure.getReason()) {
                case NOT_FOUND, FORBIDDEN -> AgentTaskRequirementReadException.Reason.NOT_FOUND;
                case INVALID_PERSISTED_STATE -> AgentTaskRequirementReadException.Reason.INTEGRITY_ERROR;
                default -> AgentTaskRequirementReadException.Reason.SOURCE_UNAVAILABLE;
            };
            throw new AgentTaskRequirementReadException(reason,failure);
        } catch (DataAccessException | TransactionException failure) {
            throw new AgentTaskRequirementReadException(
                    AgentTaskRequirementReadException.Reason.SOURCE_UNAVAILABLE,failure);
        } catch (RuntimeException failure) {
            throw new AgentTaskRequirementReadException(
                    AgentTaskRequirementReadException.Reason.SOURCE_UNAVAILABLE,failure);
        }
    }

    @Override public Snapshot requireCurrent(AgentTaskExecutionGrantService.Scope scope,String taskId,long revision) {
        validReadScope(scope,taskId);
        if (revision<1) throw new IllegalArgumentException("requirement revision invalid");
        Snapshot current=currentRequirements.read(scope,taskId);
        if (current==null || current.revision()!=revision)
            throw new IllegalStateException("Current requirement revision not confirmed by owner");
        return current;
    }

    @Override public Snapshot updateCurrent(AgentTaskExecutionGrantService.Scope scope,String taskId,
            long expectedRevision,String title,String description) {
        validReadScope(scope,taskId);
        writeTransaction();
        var current=currentRequirements.read(scope,taskId);
        if (current!=null && current.confirmedScope()!=null) requireUnfunded(scope,taskId);
        return currentRequirements.replace(scope,taskId,expectedRevision,title,description);
    }

    @Override public Snapshot freezeCurrent(AgentTaskExecutionGrantService.Scope scope,String taskId,long revision) {
        writeTransaction();
        var current=requireCurrent(scope,taskId,revision);
        Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM agent_task_requirement_snapshot "
                + "WHERE tenant_id=? AND client_id=? AND owner_jiacn=? AND task_id=? AND revision=?",
                Integer.class,scope.tenantId(),scope.clientId(),scope.ownerJiacn(),taskId,revision);
        if (count!=null && count>0) {
            var frozen=read(scope,taskId,revision);
            if (!Objects.equals(frozen,current)) throw new IllegalStateException("Frozen requirement differs from current");
            return frozen;
        }
        return insert(scope,taskId,revision,readCurrent(scope,taskId).taskVersion(),
                "ASSIGN:"+revision,current.title(),current.description(),current.source());
    }

    private Snapshot latest(AgentTaskExecutionGrantService.Scope scope,String taskId,boolean lock) {
        validReadScope(scope,taskId);
        List<Snapshot> rows=jdbc.query("SELECT tenant_id,client_id,owner_jiacn,task_id,revision,title,description,"
                        + "content_sha256,source,"+ConfirmedScopeJson.COLUMNS+" FROM agent_task_requirement_snapshot "
                        + "WHERE tenant_id=? AND client_id=? AND owner_jiacn=? AND task_id=? "
                        + "AND CAST(tenant_id AS BINARY)=CAST(? AS BINARY) "
                        + "AND CAST(client_id AS BINARY)=CAST(? AS BINARY) "
                        + "AND CAST(owner_jiacn AS BINARY)=CAST(? AS BINARY) "
                        + "AND CAST(task_id AS BINARY)=CAST(? AS BINARY) "
                        + "AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(?) "
                        + "AND OCTET_LENGTH(client_id)=OCTET_LENGTH(?) "
                        + "AND OCTET_LENGTH(owner_jiacn)=OCTET_LENGTH(?) "
                        + "AND OCTET_LENGTH(task_id)=OCTET_LENGTH(?) "
                        + "ORDER BY revision DESC LIMIT 1"+(lock?" FOR UPDATE":""),
                (rs,n)->new Snapshot(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),
                        rs.getLong(5),rs.getString(6),rs.getString(7),rs.getString(8),rs.getString(9),
                                ConfirmedScopeJson.read(rs,rs.getLong(5),rs.getString(8))),
                scope.tenantId(),scope.clientId(),scope.ownerJiacn(),taskId,
                scope.tenantId(),scope.clientId(),scope.ownerJiacn(),taskId,
                scope.tenantId(),scope.clientId(),scope.ownerJiacn(),taskId);
        try {
            return rows.isEmpty()?null:verified(rows.getFirst(),scope,taskId);
        } catch (RuntimeException failure) {
            throw new SnapshotIntegrityException(failure);
        }
    }

    /** Reject malformed UTF-16 and actual MEDIUMTEXT overflow before any task-plan insert. */
    static void validateOriginalInput(String title,String description) {
        if (title==null || title.isBlank()) throw new IllegalArgumentException("Confirmed title is missing");
        textBytes(title);
        if (description!=null) textBytes(description);
    }

    private Snapshot insert(AgentTaskExecutionGrantService.Scope scope,String taskId,long revision,
            long taskVersion, String confirmationId, String title,String description,String source) {
        validateOriginalInput(title, description);
        String hash=digest(scope,taskId,revision,source,title,description);
        if (jdbc.update("INSERT INTO agent_task_requirement_snapshot "
                + "(tenant_id,client_id,owner_jiacn,task_id,revision,confirmation_id,task_version_at_confirmation,"
                + "title,description,content_sha256,source,created_at) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",scope.tenantId(),scope.clientId(),scope.ownerJiacn(),
                taskId,revision,confirmationId,taskVersion,title,description,hash,source,System.currentTimeMillis())!=1)
            throw new IllegalStateException("Requirement snapshot insert failed");
        return new Snapshot(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),taskId,
                revision,title,description,hash,source);
    }

    static Snapshot verified(Snapshot row,AgentTaskExecutionGrantService.Scope scope,String taskId) {
        if (!Objects.equals(row.tenantId(),scope.tenantId()) || !Objects.equals(row.clientId(),scope.clientId())
                || !Objects.equals(row.ownerJiacn(),scope.ownerJiacn()) || !Objects.equals(row.taskId(),taskId)
                || row.revision()<1 || row.title()==null || row.title().isBlank()
                || row.sha256()==null || !row.sha256().matches("[0-9a-f]{64}")
                || !("CREATE".equals(row.source()) || "RECONFIRM".equals(row.source()) || "SCOPED".equals(row.source()))
                || "SCOPED".equals(row.source()) != (row.confirmedScope()!=null)
                || row.confirmedScope()!=null && (row.confirmedScope().requirementRevision()!=row.revision()
                    || !row.confirmedScope().requirementSha256().equals(row.sha256()))
                || !MessageDigest.isEqual(row.sha256().getBytes(StandardCharsets.US_ASCII),
                (row.confirmedScope()==null ? digest(scope,taskId,row.revision(),row.source(),row.title(),row.description())
                    : new ConfirmedScope.Payload(row.confirmedScope().schemaVersion(),row.confirmedScope().workItems(),
                        row.confirmedScope().acceptanceText()).requirementDigest(row.title(),row.description(),
                        row.confirmedScope().inputSnapshotDigest())).getBytes(StandardCharsets.US_ASCII)))
            throw new IllegalStateException("Requirement snapshot integrity/scope drift");
        return row;
    }

    static String digest(AgentTaskExecutionGrantService.Scope scope,String taskId,long revision,
            String source,String title,String description) {
        try {
            MessageDigest md=MessageDigest.getInstance("SHA-256");
            for (String field:List.of("TASK_REQUIREMENT_SNAPSHOT_V1",scope.tenantId(),scope.clientId(),
                    scope.ownerJiacn(),taskId,Long.toString(revision),source,title)) add(md,field);
            if (description==null) md.update((byte)0); else { md.update((byte)1); add(md,description); }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static void add(MessageDigest md,String value) {
        byte[] bytes=textBytes(value);
        md.update(ByteBuffer.allocate(4).putInt(bytes.length).array());md.update(bytes);
    }
    private static byte[] textBytes(String value) {
        try {
            ByteBuffer encoded=StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value));
            if (encoded.remaining()>16_777_215) throw new IllegalArgumentException("Text exceeds MEDIUMTEXT bytes");
            byte[] bytes=new byte[encoded.remaining()];encoded.get(bytes);return bytes;
        } catch (CharacterCodingException invalid) {
            throw new IllegalArgumentException("Invalid Unicode in requirement", invalid);
        }
    }
    private static void validScope(AgentTaskExecutionGrantService.Scope scope,String taskId) {
        validReadScope(scope, taskId);
        if (!"0".equals(scope.tenantId()))
            throw new IllegalArgumentException("Task requirement mutation scope is not supported");
    }
    private static void validReadScope(AgentTaskExecutionGrantService.Scope scope,String taskId) {
        if (scope==null || !exact(scope.tenantId(),50) || !exact(scope.clientId(),50)
                || !exact(scope.ownerJiacn(),50) || "0".equals(scope.ownerJiacn())
                || !exact(taskId,100))
            throw new IllegalArgumentException("Owner-scoped requirement key invalid");
    }
    private static boolean exact(String value,int max) {
        return value!=null && !value.isEmpty() && !hasUnpairedSurrogate(value)
                && value.codePointCount(0,value.length())<=max
                && !value.codePoints().allMatch(AgentTaskRequirementSnapshotServiceImpl::isPadding)
                && !isPadding(value.codePointAt(0))
                && !isPadding(value.codePointBefore(value.length()))
                && value.codePoints().noneMatch(Character::isISOControl);
    }
    private static boolean hasUnpairedSurrogate(String value) {
        for (int index=0;index<value.length();index++) {
            char unit=value.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                if (++index>=value.length() || !Character.isLowSurrogate(value.charAt(index))) return true;
            } else if (Character.isLowSurrogate(unit)) return true;
        }
        return false;
    }
    private static boolean isPadding(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }
    static final class SnapshotIntegrityException extends IllegalStateException {
        private SnapshotIntegrityException() { super("Requirement snapshot integrity failure"); }
        SnapshotIntegrityException(Throwable cause) {
            super("Requirement snapshot integrity failure",cause);
        }
    }

    private static void writeTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw new IllegalStateException("Requirement snapshot needs owning task-root transaction");
    }
}
