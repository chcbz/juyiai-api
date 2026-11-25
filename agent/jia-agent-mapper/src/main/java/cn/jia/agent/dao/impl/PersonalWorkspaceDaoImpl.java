package cn.jia.agent.dao.impl;

import cn.jia.agent.dao.PersonalWorkspaceDao;
import cn.jia.agent.entity.PersonalWorkspaceFileEntity;
import cn.jia.agent.entity.PersonalWorkspaceOperationEntity;
import cn.jia.agent.entity.PersonalWorkspaceVersionEntity;
import cn.jia.agent.mapper.PersonalWorkspaceFileMapper;
import cn.jia.agent.mapper.PersonalWorkspaceOperationMapper;
import cn.jia.agent.mapper.PersonalWorkspaceVersionMapper;
import cn.jia.core.util.DateUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.util.List;
import java.util.Objects;

@Named
public class PersonalWorkspaceDaoImpl implements PersonalWorkspaceDao {
    private final PersonalWorkspaceFileMapper files;
    private final PersonalWorkspaceVersionMapper versions;
    private final PersonalWorkspaceOperationMapper operations;

    @Inject
    public PersonalWorkspaceDaoImpl(PersonalWorkspaceFileMapper files,
            PersonalWorkspaceVersionMapper versions, PersonalWorkspaceOperationMapper operations) {
        this.files = Objects.requireNonNull(files, "files");
        this.versions = Objects.requireNonNull(versions, "versions");
        this.operations = Objects.requireNonNull(operations, "operations");
    }

    @Override public UsageSnapshot findUsage(String tenantId, String clientId, String ownerJiacn, String fileId) {
        requireScope(tenantId, clientId, ownerJiacn); requireId(fileId, "fileId", 100);
        return files.selectUsage(tenantId, clientId, ownerJiacn, fileId);
    }
    @Override public PersonalWorkspaceFileEntity findFile(String tenantId, String clientId,
            String ownerJiacn, String fileId) {
        requireScope(tenantId, clientId, ownerJiacn); requireId(fileId, "fileId", 100);
        LambdaQueryWrapper<PersonalWorkspaceFileEntity> q = fileScope(tenantId, clientId, ownerJiacn)
                .eq(PersonalWorkspaceFileEntity::getFileId, fileId);
        exact(q, "file_id", fileId); return files.selectOne(q.last("limit 1"));
    }
    @Override public PersonalWorkspaceFileEntity lockFile(String tenantId, String clientId,
            String ownerJiacn, String fileId) {
        requireScope(tenantId, clientId, ownerJiacn); requireId(fileId, "fileId", 100);
        return files.selectForUpdate(tenantId, clientId, ownerJiacn, fileId);
    }
    @Override public List<PersonalWorkspaceFileEntity> listFiles(String tenantId, String clientId,
            String ownerJiacn, String qText, String mediaFamily, String state, Long beforeCreatedAt,
            String afterFileId, int limit) {
        requireScope(tenantId, clientId, ownerJiacn);
        LambdaQueryWrapper<PersonalWorkspaceFileEntity> q = fileScope(tenantId, clientId, ownerJiacn);
        if (qText != null) q.like(PersonalWorkspaceFileEntity::getDisplayName, qText);
        if (mediaFamily != null) { q.eq(PersonalWorkspaceFileEntity::getMediaFamily, mediaFamily); exact(q, "media_family", mediaFamily); }
        if (state != null) { q.eq(PersonalWorkspaceFileEntity::getState, state); exact(q, "state", state); }
        if (beforeCreatedAt != null && afterFileId != null) {
            requireId(afterFileId, "cursor", 100);
            q.and(cursor -> cursor.lt(PersonalWorkspaceFileEntity::getCreatedAt, beforeCreatedAt)
                    .or(same -> same.eq(PersonalWorkspaceFileEntity::getCreatedAt, beforeCreatedAt)
                            .gt(PersonalWorkspaceFileEntity::getFileId, afterFileId)));
        }
        return files.selectList(q.orderByDesc(PersonalWorkspaceFileEntity::getCreatedAt)
                .orderByAsc(PersonalWorkspaceFileEntity::getFileId).last("limit " + bounded(limit)));
    }
    @Override public void insertFile(PersonalWorkspaceFileEntity entity) { init(entity); files.insert(entity); }
    @Override public void updateFile(PersonalWorkspaceFileEntity entity) { entity.setUpdateTime(DateUtil.nowTime()); files.updateById(entity); }
    @Override public PersonalWorkspaceVersionEntity findVersion(String tenantId, String clientId,
            String ownerJiacn, String fileId, int version) {
        requireScope(tenantId, clientId, ownerJiacn); requireId(fileId, "fileId", 100); requireVersion(version);
        LambdaQueryWrapper<PersonalWorkspaceVersionEntity> q = versionScope(tenantId, clientId, ownerJiacn)
                .eq(PersonalWorkspaceVersionEntity::getFileId, fileId)
                .eq(PersonalWorkspaceVersionEntity::getVersion, version);
        exact(q, "file_id", fileId); return versions.selectOne(q.last("limit 1"));
    }
    @Override public List<PersonalWorkspaceFileEntity> selectedHeaders(String tenant, String client, String owner, List<String> ids) {
        requireScope(tenant, client, owner);
        if (ids.isEmpty()) return List.of();
        ids.forEach(id -> requireId(id, "fileId", 100));
        var q = fileScope(tenant, client, owner).in(PersonalWorkspaceFileEntity::getFileId, ids);
        q.and(group -> ids.forEach(id -> group.or(item -> exact(item, "file_id", id))));
        return files.selectList(q.select(PersonalWorkspaceFileEntity::getFileId, PersonalWorkspaceFileEntity::getSourceKind,
                PersonalWorkspaceFileEntity::getOriginKind, PersonalWorkspaceFileEntity::getDisplayName,
                PersonalWorkspaceFileEntity::getMediaFamily, PersonalWorkspaceFileEntity::getState,
                PersonalWorkspaceFileEntity::getMetadataRevision, PersonalWorkspaceFileEntity::getLatestVersion,
                PersonalWorkspaceFileEntity::getCreatedAt));
    }
    @Override public List<PersonalWorkspaceVersionEntity> selectedMetadata(String tenant, String client, String owner, List<VersionPick> picks) {
        requireScope(tenant, client, owner);
        if (picks.isEmpty()) return List.of();
        var grouped = new java.util.LinkedHashMap<String,List<Integer>>();
        for (var pick : picks) {
            requireId(pick.fileId(), "fileId", 100); requireVersion(pick.version());
            grouped.computeIfAbsent(pick.fileId(), ignored -> new java.util.ArrayList<>()).add(pick.version());
        }
        var q = versionScope(tenant, client, owner);
        q.and(group -> grouped.forEach((id, numbers) -> group.or(item -> exact(item.eq(PersonalWorkspaceVersionEntity::getFileId, id), "file_id", id)
                .in(PersonalWorkspaceVersionEntity::getVersion, numbers))));
        return versions.selectList(q.select(PersonalWorkspaceVersionEntity::getFileId, PersonalWorkspaceVersionEntity::getVersion,
                PersonalWorkspaceVersionEntity::getContentHash, PersonalWorkspaceVersionEntity::getByteLength,
                PersonalWorkspaceVersionEntity::getOriginalFilename, PersonalWorkspaceVersionEntity::getContentMimeType,
                PersonalWorkspaceVersionEntity::getCreatedAt));
    }
    @Override public List<PersonalWorkspaceVersionEntity> selectedVersionMetadata(String tenantId, String clientId,
            String ownerJiacn, String fileId, List<Integer> numbers) {
        requireScope(tenantId, clientId, ownerJiacn); requireId(fileId, "fileId", 100);
        if (numbers.isEmpty()) return List.of(); // an empty IN must never become an unrestricted query
        numbers.forEach(PersonalWorkspaceDaoImpl::requireVersion);
        var q = versionScope(tenantId, clientId, ownerJiacn).eq(PersonalWorkspaceVersionEntity::getFileId, fileId)
                .in(PersonalWorkspaceVersionEntity::getVersion, numbers);
        exact(q, "file_id", fileId);
        return versions.selectList(q.select(PersonalWorkspaceVersionEntity::getFileId, PersonalWorkspaceVersionEntity::getVersion,
                PersonalWorkspaceVersionEntity::getContentHash, PersonalWorkspaceVersionEntity::getByteLength,
                PersonalWorkspaceVersionEntity::getOriginalFilename, PersonalWorkspaceVersionEntity::getContentMimeType,
                PersonalWorkspaceVersionEntity::getCreatedAt));
    }
    @Override public List<PersonalWorkspaceVersionEntity> listVersions(String tenantId, String clientId,
            String ownerJiacn, String fileId, int limit) {
        requireScope(tenantId, clientId, ownerJiacn); requireId(fileId, "fileId", 100);
        LambdaQueryWrapper<PersonalWorkspaceVersionEntity> q = versionScope(tenantId, clientId, ownerJiacn)
                .eq(PersonalWorkspaceVersionEntity::getFileId, fileId);
        exact(q, "file_id", fileId);
        return versions.selectList(q.orderByDesc(PersonalWorkspaceVersionEntity::getVersion).last("limit " + bounded(limit)));
    }
    @Override public List<PersonalWorkspaceVersionEntity> listVersionsBefore(String tenantId, String clientId,
            String ownerJiacn, String fileId, Integer beforeVersion) {
        requireScope(tenantId, clientId, ownerJiacn); requireId(fileId, "fileId", 100);
        LambdaQueryWrapper<PersonalWorkspaceVersionEntity> q = versionScope(tenantId, clientId, ownerJiacn)
                .eq(PersonalWorkspaceVersionEntity::getFileId, fileId);
        if (beforeVersion != null) q.lt(PersonalWorkspaceVersionEntity::getVersion, beforeVersion);
        exact(q, "file_id", fileId); return versions.selectList(q.orderByDesc(PersonalWorkspaceVersionEntity::getVersion).last("limit 101"));
    }
    @Override public void insertVersion(PersonalWorkspaceVersionEntity entity) { init(entity); versions.insert(entity); }
    @Override public PersonalWorkspaceOperationEntity findOperationByIdempotency(String tenantId,
            String clientId, String ownerJiacn, String type, String key) {
        requireScope(tenantId, clientId, ownerJiacn); requireId(type, "operationType", 40); requireId(key, "idempotencyKey", 100);
        LambdaQueryWrapper<PersonalWorkspaceOperationEntity> q = operationScope(tenantId, clientId, ownerJiacn)
                .eq(PersonalWorkspaceOperationEntity::getOperationType, type)
                .eq(PersonalWorkspaceOperationEntity::getIdempotencyKey, key);
        exact(q, "operation_type", type); exact(q, "idempotency_key", key); return operations.selectOne(q.last("limit 1"));
    }
    @Override public PersonalWorkspaceOperationEntity findOperation(String tenantId, String clientId,
            String ownerJiacn, String operationId) {
        requireScope(tenantId, clientId, ownerJiacn); requireId(operationId, "operationId", 100);
        LambdaQueryWrapper<PersonalWorkspaceOperationEntity> q = operationScope(tenantId, clientId, ownerJiacn)
                .eq(PersonalWorkspaceOperationEntity::getOperationId, operationId);
        exact(q, "operation_id", operationId); return operations.selectOne(q.last("limit 1"));
    }
    @Override public void insertOperation(PersonalWorkspaceOperationEntity entity) { init(entity); operations.insert(entity); }
    @Override public void updateOperation(PersonalWorkspaceOperationEntity entity) { entity.setUpdateTime(DateUtil.nowTime()); operations.updateById(entity); }

    private static LambdaQueryWrapper<PersonalWorkspaceFileEntity> fileScope(String tenant, String client, String owner) {
        LambdaQueryWrapper<PersonalWorkspaceFileEntity> q = new LambdaQueryWrapper<PersonalWorkspaceFileEntity>()
                .eq(PersonalWorkspaceFileEntity::getTenantId, tenant).eq(PersonalWorkspaceFileEntity::getClientId, client)
                .eq(PersonalWorkspaceFileEntity::getOwnerJiacn, owner); exact(q,"tenant_id",tenant); exact(q,"client_id",client); return exact(q,"owner_jiacn",owner);
    }
    private static LambdaQueryWrapper<PersonalWorkspaceVersionEntity> versionScope(String tenant, String client, String owner) {
        LambdaQueryWrapper<PersonalWorkspaceVersionEntity> q = new LambdaQueryWrapper<PersonalWorkspaceVersionEntity>()
                .eq(PersonalWorkspaceVersionEntity::getTenantId, tenant).eq(PersonalWorkspaceVersionEntity::getClientId, client)
                .eq(PersonalWorkspaceVersionEntity::getOwnerJiacn, owner); exact(q,"tenant_id",tenant); exact(q,"client_id",client); return exact(q,"owner_jiacn",owner);
    }
    private static LambdaQueryWrapper<PersonalWorkspaceOperationEntity> operationScope(String tenant, String client, String owner) {
        LambdaQueryWrapper<PersonalWorkspaceOperationEntity> q = new LambdaQueryWrapper<PersonalWorkspaceOperationEntity>()
                .eq(PersonalWorkspaceOperationEntity::getTenantId, tenant).eq(PersonalWorkspaceOperationEntity::getClientId, client)
                .eq(PersonalWorkspaceOperationEntity::getOwnerJiacn, owner); exact(q,"tenant_id",tenant); exact(q,"client_id",client); return exact(q,"owner_jiacn",owner);
    }
    private static <T> LambdaQueryWrapper<T> exact(LambdaQueryWrapper<T> q, String c, String v) {
        return q.apply("CAST(" + c + " AS BINARY)=CAST({0} AS BINARY)",v).apply("OCTET_LENGTH(" + c + ")=OCTET_LENGTH({0})",v);
    }
    private static void init(cn.jia.core.entity.BaseEntity entity) { entity.init4Creation(); }
    private static int bounded(int limit) { return Math.max(1, Math.min(limit, 100)); }
    private static void requireScope(String tenant, String client, String owner) { requireId(tenant,"tenantId",50); requireId(client,"clientId",50); requireId(owner,"ownerJiacn",50); if ("0".equals(owner)) throw new IllegalArgumentException("owner"); }
    private static void requireVersion(int value) { if (value < 1) throw new IllegalArgumentException("version"); }
    private static void requireId(String value, String name, int max) { if (value == null || value.isBlank() || !value.equals(value.strip()) || value.codePointCount(0,value.length()) > max || value.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException(name); }
}
