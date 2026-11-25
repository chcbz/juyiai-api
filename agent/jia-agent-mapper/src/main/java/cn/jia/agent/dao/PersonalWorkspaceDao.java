package cn.jia.agent.dao;

import cn.jia.agent.entity.PersonalWorkspaceFileEntity;
import cn.jia.agent.entity.PersonalWorkspaceOperationEntity;
import cn.jia.agent.entity.PersonalWorkspaceVersionEntity;

import java.util.List;

/** Strict owner-scoped persistence boundary for personal uploads. */
public interface PersonalWorkspaceDao {
    record UsageSnapshot(String fileId, long impactRevision, long taskReferences,
            long conversationReferences, long activeInputGrants) { }
    UsageSnapshot findUsage(String tenantId, String clientId, String ownerJiacn, String fileId);
    PersonalWorkspaceFileEntity findFile(String tenantId, String clientId, String ownerJiacn, String fileId);
    PersonalWorkspaceFileEntity lockFile(String tenantId, String clientId, String ownerJiacn, String fileId);
    List<PersonalWorkspaceFileEntity> listFiles(String tenantId, String clientId, String ownerJiacn,
            String q, String mediaFamily, String state, Long beforeCreatedAt, String afterFileId, int limit);
    void insertFile(PersonalWorkspaceFileEntity entity);
    void updateFile(PersonalWorkspaceFileEntity entity);
    PersonalWorkspaceVersionEntity findVersion(String tenantId, String clientId, String ownerJiacn,
            String fileId, int version);
    /** Scoped metadata projection for explicit immutable versions; no storage location. */
    record VersionPick(String fileId, int version) {}
    List<PersonalWorkspaceFileEntity> selectedHeaders(String tenant, String client, String owner, List<String> ids);
    List<PersonalWorkspaceVersionEntity> selectedMetadata(String tenant, String client, String owner, List<VersionPick> picks);
    List<PersonalWorkspaceVersionEntity> selectedVersionMetadata(String tenantId, String clientId, String ownerJiacn,
            String fileId, List<Integer> numbers);
    List<PersonalWorkspaceVersionEntity> listVersions(String tenantId, String clientId, String ownerJiacn,
            String fileId, int limit);
    List<PersonalWorkspaceVersionEntity> listVersionsBefore(String tenantId, String clientId, String ownerJiacn, String fileId, Integer beforeVersion);
    void insertVersion(PersonalWorkspaceVersionEntity entity);
    PersonalWorkspaceOperationEntity findOperationByIdempotency(String tenantId, String clientId,
            String ownerJiacn, String type, String key);
    PersonalWorkspaceOperationEntity findOperation(String tenantId, String clientId,
            String ownerJiacn, String operationId);
    void insertOperation(PersonalWorkspaceOperationEntity entity);
    void updateOperation(PersonalWorkspaceOperationEntity entity);
}
