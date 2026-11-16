package cn.jia.agent.service.impl;

import cn.jia.agent.config.PersonalWorkspaceExecutionProperties;
import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.dao.PersonalWorkspaceDao;
import cn.jia.agent.dao.PersonalWorkspaceExecutionDao;
import cn.jia.agent.dao.PersonalWorkspaceTaskLinkDao;
import cn.jia.agent.entity.PersonalWorkspaceExecutionEntity;
import cn.jia.agent.entity.PersonalWorkspaceExecutionInputEntity;
import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import cn.jia.agent.service.PersonalWorkspaceStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.clearInvocations;

/** Historical Owner reads and retired execution rejection. Native6 behavior lives in SkillExecutionServiceTest. */
class PersonalWorkspaceExecutionServiceImplTest {
    private static final PersonalWorkspaceExecutionService.RuntimeScope RUNTIME =
            new PersonalWorkspaceExecutionService.RuntimeScope("0", "client-a", "owner-a", "agent-a", "runtime-a");
    private PersonalWorkspaceExecutionDao executions;
    private PersonalWorkspaceDao workspace;
    private PersonalWorkspaceTaskLinkDao taskLinks;
    private AgentRuntimeDao runtimes;
    private PersonalWorkspaceStorage storage;
    private PersonalWorkspaceWriteService writes;
    private PersonalWorkspaceExecutionServiceImpl service;

    @Test
    void grayExecutionReadUsesExactOwnerPartitionWithoutProductionFallback() {
        var gray = new PersonalWorkspaceExecutionService.OwnerScope("1", "client-a", "owner-a");
        var missing = assertThrows(PersonalWorkspaceExecutionService.Failure.class,
                () -> service.get(gray, "execution-gray"));
        assertEquals(PersonalWorkspaceExecutionService.Reason.NOT_FOUND, missing.getReason());
        verify(executions).find("1", "client-a", "owner-a", "execution-gray");
        verify(executions, never()).find("0", "client-a", "owner-a", "execution-gray");
        assertThrows(PersonalWorkspaceExecutionService.Failure.class, () -> service.get(
                new PersonalWorkspaceExecutionService.OwnerScope("1 ", "client-a", "owner-a"), "execution-gray"));
    }

    @BeforeEach
    void setUp() throws Exception {
        executions = mock(PersonalWorkspaceExecutionDao.class);
        workspace = mock(PersonalWorkspaceDao.class);
        taskLinks = mock(PersonalWorkspaceTaskLinkDao.class);
        runtimes = mock(AgentRuntimeDao.class);
        storage = mock(PersonalWorkspaceStorage.class);
        when(storage.maxContentBytes()).thenReturn(4096L);
        writes = mock(PersonalWorkspaceWriteService.class);
        service = new PersonalWorkspaceExecutionServiceImpl(executions, workspace,
                taskLinks, runtimes, storage, writes,
                new PersonalWorkspaceExecutionProperties(List.of(PersonalWorkspaceExecutionProperties.DOCX)));
        org.mockito.Mockito.clearInvocations(storage);

    }

    @Test
    void ownerHistoryUsesLimitPlusOneAndByteExactTieCursorWithoutDuplicates() {
        PersonalWorkspaceExecutionEntity upper = historyExecution("pwe_A", 1_790_000_000_000L);
        PersonalWorkspaceExecutionEntity lower = historyExecution("pwe_a", 1_790_000_000_000L);
        PersonalWorkspaceExecutionEntity later = historyExecution("pwe_b", 1_790_000_000_000L);
        when(executions.listHistory("0", "client-a", "owner-a", null, null, 3))
                .thenReturn(List.of(upper, later, lower));

        var first = service.list(new PersonalWorkspaceExecutionService.OwnerScope(
                "0", "client-a", "owner-a"), 2, null, null);

        assertEquals(List.of("pwe_b", "pwe_a"), first.items().stream()
                .map(PersonalWorkspaceExecutionService.ExecutionSummary::executionId).toList());
        assertEquals(new PersonalWorkspaceExecutionService.ExecutionCursor(
                1_790_000_000_000L, "pwe_a"), first.nextCursor());
        assertTrue(first.items().stream().noneMatch(item -> item.toString().contains("secret")));
        verify(executions, never()).listInputs(any(), any(), any(), any());

        when(executions.listHistory("0", "client-a", "owner-a",
                1_790_000_000_000L, "pwe_a", 3)).thenReturn(List.of(upper));
        var second = service.list(new PersonalWorkspaceExecutionService.OwnerScope(
                        "0", "client-a", "owner-a"), 2,
                1_790_000_000_000L, "pwe_a");

        assertEquals(List.of("pwe_A"), second.items().stream()
                .map(PersonalWorkspaceExecutionService.ExecutionSummary::executionId).toList());
        assertEquals(null, second.nextCursor());
        assertTrue(first.items().stream().noneMatch(second.items()::contains));
    }

    @Test
    void ownerHistoryRejectsDuplicateOrNonAdvancingDaoRows() {
        var owner = new PersonalWorkspaceExecutionService.OwnerScope("0", "client-a", "owner-a");
        PersonalWorkspaceExecutionEntity duplicate = historyExecution("pwe_b", 1000L);
        when(executions.listHistory("0", "client-a", "owner-a", null, null, 3))
                .thenReturn(List.of(duplicate, duplicate));
        var duplicated = assertThrows(PersonalWorkspaceExecutionService.Failure.class,
                () -> service.list(owner, 2, null, null));
        assertEquals(PersonalWorkspaceExecutionService.Reason.STORAGE_UNAVAILABLE,
                duplicated.getReason());

        when(executions.listHistory("0", "client-a", "owner-a", 1000L, "pwe_b", 3))
                .thenReturn(List.of(historyExecution("pwe_b", 1000L)));
        var nonAdvancing = assertThrows(PersonalWorkspaceExecutionService.Failure.class,
                () -> service.list(owner, 2, 1000L, "pwe_b"));
        assertEquals(PersonalWorkspaceExecutionService.Reason.STORAGE_UNAVAILABLE,
                nonAdvancing.getReason());
    }

    @Test
    void ownerHistoryRejectsMalformedBoundsBeforeDao() {
        var owner = new PersonalWorkspaceExecutionService.OwnerScope("0", "client-a", "owner-a");
        for (Object[] input : List.of(
                new Object[] {0, null, null},
                new Object[] {101, null, null},
                new Object[] {20, 10L, null},
                new Object[] {20, null, "pwe_a"},
                new Object[] {20, -1L, "pwe_a"},
                new Object[] {20, 10L, " pwe_a"})) {
            var failure = assertThrows(PersonalWorkspaceExecutionService.Failure.class,
                    () -> service.list(owner, (Integer) input[0], (Long) input[1], (String) input[2]));
            assertEquals(PersonalWorkspaceExecutionService.Reason.BAD_REQUEST, failure.getReason());
        }
        verify(executions, never()).listHistory(any(), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void ownerHistoryFailsClosedOnOtherTenantOwnerClientCaseOrSpaceDaoRows() {
        var owner = new PersonalWorkspaceExecutionService.OwnerScope("0", "client-a", "owner-a");
        List<PersonalWorkspaceExecutionEntity> contaminated = List.of(
                historyExecution("pwe_other_tenant", 7L),
                historyExecution("pwe_tenant_space", 6L),
                historyExecution("pwe_other_owner", 5L).setOwnerJiacn("owner-b"),
                historyExecution("pwe_other_client", 6L),
                historyExecution("pwe_owner_case", 5L).setOwnerJiacn("Owner-a"),
                historyExecution("pwe_owner_space", 4L).setOwnerJiacn("owner-a "),
                historyExecution("pwe_client_case", 3L),
                historyExecution("pwe_client_space", 2L));
        contaminated.get(0).setTenantId("1");
        contaminated.get(1).setTenantId("0 ");
        contaminated.get(3).setClientId("client-b");
        contaminated.get(6).setClientId("Client-a");
        contaminated.get(7).setClientId("client-a ");
        for (PersonalWorkspaceExecutionEntity leaked : contaminated) {
            when(executions.listHistory("0", "client-a", "owner-a", null, null, 21))
                    .thenReturn(List.of(leaked));
            var failure = assertThrows(PersonalWorkspaceExecutionService.Failure.class,
                    () -> service.list(owner, 20, null, null));
            assertEquals(PersonalWorkspaceExecutionService.Reason.STORAGE_UNAVAILABLE,
                    failure.getReason());
        }
    }

    @Test
    void idempotencyRecoveryIsReadOnlyAndRequiresExactPersistedScopeAndKey() throws Exception {
        var owner = new PersonalWorkspaceExecutionService.OwnerScope("0", "client-a", "owner-a");
        PersonalWorkspaceExecutionEntity existing = historyExecution("pwe_existing", 10L)
                .setTaskId("pwe_task_1").setRunId("pwe_run_1").setConversationId(null)
                .setGrantRevision(1L).setIdempotencyKey("paid-create-key")
                .setExecutionMode("PRIVATE");
        when(executions.findRequestByIdempotency(
                "0", "client-a", "owner-a", "paid-create-key")).thenReturn(existing);
        when(executions.listInputs("0", "client-a", "owner-a", "pwe_existing"))
                .thenReturn(List.of());

        var recovered = service.getByIdempotencyKey(owner, "paid-create-key");

        assertEquals("pwe_existing", recovered.executionId());
        verify(executions, never()).insert(any(PersonalWorkspaceExecutionEntity.class));
        verify(executions, never()).insertInput(any(PersonalWorkspaceExecutionInputEntity.class));
        verify(runtimes, never()).findCandidateRosterByOwner(any(), any());
        var method = PersonalWorkspaceExecutionServiceImpl.class.getMethod(
                "getByIdempotencyKey", PersonalWorkspaceExecutionService.OwnerScope.class, String.class);
        assertTrue(method.getAnnotation(org.springframework.transaction.annotation.Transactional.class).readOnly());
    }

    @Test
    void idempotencyRecoveryTreatsContaminatedDaoRowsAsNotFoundWithoutViewExpansion() {
        var owner = new PersonalWorkspaceExecutionService.OwnerScope("0", "client-a", "owner-a");
        List<PersonalWorkspaceExecutionEntity> contaminated = List.of(
                historyExecution("pwe_other_tenant", 8L).setIdempotencyKey("paid-create-key"),
                historyExecution("pwe_tenant_space", 7L).setIdempotencyKey("paid-create-key"),
                historyExecution("pwe_other_owner", 5L).setOwnerJiacn("owner-b")
                        .setIdempotencyKey("paid-create-key"),
                historyExecution("pwe_other_client", 7L).setIdempotencyKey("paid-create-key"),
                historyExecution("pwe_owner_case", 6L).setOwnerJiacn("Owner-a")
                        .setIdempotencyKey("paid-create-key"),
                historyExecution("pwe_owner_space", 5L).setOwnerJiacn("owner-a ")
                        .setIdempotencyKey("paid-create-key"),
                historyExecution("pwe_client_case", 4L).setIdempotencyKey("paid-create-key"),
                historyExecution("pwe_client_space", 3L).setIdempotencyKey("paid-create-key"),
                historyExecution("pwe_other_key", 1L).setIdempotencyKey("Paid-create-key"));
        contaminated.get(0).setTenantId("1");
        contaminated.get(1).setTenantId("0 ");
        contaminated.get(3).setClientId("client-b");
        contaminated.get(6).setClientId("Client-a");
        contaminated.get(7).setClientId("client-a ");
        for (PersonalWorkspaceExecutionEntity leaked : contaminated) {
            when(executions.findRequestByIdempotency(
                    "0", "client-a", "owner-a", "paid-create-key")).thenReturn(leaked);
            var failure = assertThrows(PersonalWorkspaceExecutionService.Failure.class,
                    () -> service.getByIdempotencyKey(owner, "paid-create-key"));
            assertEquals(PersonalWorkspaceExecutionService.Reason.NOT_FOUND, failure.getReason());
        }
        verify(executions, never()).listInputs(any(), any(), any(), any());
    }

    @Test
    void idempotencyRecoveryRejectsInputRowsOutsideExactExecutionScope() {
        var owner = new PersonalWorkspaceExecutionService.OwnerScope("0", "client-a", "owner-a");
        PersonalWorkspaceExecutionEntity existing = historyExecution("pwe_existing", 10L)
                .setTaskId("pwe_task_1").setRunId("pwe_run_1").setConversationId(null)
                .setGrantRevision(1L).setIdempotencyKey("paid-create-key")
                .setExecutionMode("PRIVATE");
        when(executions.findRequestByIdempotency(
                "0", "client-a", "owner-a", "paid-create-key")).thenReturn(existing);
        List<PersonalWorkspaceExecutionInputEntity> contaminated = List.of(
                input("pwe_existing").setExecutionId("pwe_other"),
                input("pwe_existing").setOwnerJiacn("owner-b"),
                input("pwe_existing"),
                input("pwe_existing"));
        contaminated.get(2).setClientId("Client-a");
        contaminated.get(3).setTenantId("0 ");
        for (PersonalWorkspaceExecutionInputEntity leaked : contaminated) {
            when(executions.listInputs("0", "client-a", "owner-a", "pwe_existing"))
                    .thenReturn(List.of(leaked));
            var failure = assertThrows(PersonalWorkspaceExecutionService.Failure.class,
                    () -> service.getByIdempotencyKey(owner, "paid-create-key"));
            assertEquals(PersonalWorkspaceExecutionService.Reason.NOT_FOUND, failure.getReason());
        }
    }

    @Test
    void retiredAndUnknownProtocolsRemainReadableButCannotClaimNativeExecution() {
        var owner = new PersonalWorkspaceExecutionService.OwnerScope("0", "client-a", "owner-a");
        for (Integer protocol : java.util.Arrays.asList(null, 1, 2, 3, 4, 5, 7)) {
            var row = execution("pwe_old", "agent-a", "QUEUED")
                    .setExecutionProtocolVersion(protocol).setExecutionMode("CONVERSATION")
                    .setBusinessScope("PRIVATE").setConversationId("42").setTaskId(null);
            when(executions.find("0", "client-a", "owner-a", "pwe_old")).thenReturn(row);
            when(executions.listInputs("0", "client-a", "owner-a", "pwe_old")).thenReturn(List.of());
            when(executions.findForRuntime("0", "client-a", "owner-a", "agent-a", "pwe_old", "pwe_run_1")).thenReturn(row);
            assertEquals("UNSUPPORTED_RETIRED_PROTOCOL", service.get(owner, "pwe_old").recoveryStatus());
            var failure = assertThrows(PersonalWorkspaceExecutionService.Failure.class,
                    () -> service.claimConversationStart(RUNTIME, "pwe_old", "pwe_run_1", "command", "message"));
            assertEquals(PersonalWorkspaceExecutionService.Reason.NOT_FOUND, failure.getReason());
        }
        verify(executions, never()).insert(any());
        verify(executions, never()).update(any());
        org.mockito.Mockito.verifyNoInteractions(writes, storage);
    }

    @Test
    void retiredWriteMethodsAreNotPartOfTheServiceContract() {
        var retired = java.util.Set.of("start", "runtimeQueuedCommands", "createConversation", "revokeInputs",
                "stageOutput", "commitOutput", "fail");
        for (var method : PersonalWorkspaceExecutionService.class.getMethods())
            assertFalse(retired.contains(method.getName()), method.toString());
        assertFalse(java.util.Arrays.stream(PersonalWorkspaceExecutionService.class.getDeclaredClasses())
                .anyMatch(type -> type.getSimpleName().equals("CreateCommand")));
    }

    private static PersonalWorkspaceExecutionEntity historyExecution(String id, long createdAt) {
        PersonalWorkspaceExecutionEntity item = execution(id, "agent-a", "QUEUED")
                .setCreatedAt(createdAt).setInstruction("secret instruction")
                .setLeaseToken("secret runtime credential");
        return item;
    }

    private static PersonalWorkspaceExecutionEntity execution(String id, String agent, String state) {
        PersonalWorkspaceExecutionEntity item = new PersonalWorkspaceExecutionEntity()
                .setExecutionId(id).setOwnerJiacn("owner-a").setTaskId("pwe_task_1").setRunId("pwe_run_1")
                .setTargetAgentId(agent).setInstruction("请保留标题并改正文")
                .setOutputContentMimeType(PersonalWorkspaceExecutionProperties.DOCX).setExecutionState(state).setGrantRevision(1L);
        item.setTenantId("0"); item.setClientId("client-a");
        return item;
    }

    private static PersonalWorkspaceExecutionInputEntity input(String executionId) {
        return input(executionId, "input_1", "pws_1", 1, "source.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    }

    private static PersonalWorkspaceExecutionInputEntity input(String executionId, String inputRef, String fileId,
            int version, String filename, String contentMimeType) {
        PersonalWorkspaceExecutionInputEntity input = new PersonalWorkspaceExecutionInputEntity()
                .setExecutionId(executionId).setInputRef(inputRef).setFileId(fileId).setFileVersion(version)
                .setOriginalFilename(filename).setContentMimeType(contentMimeType)
                .setByteLength(7L).setContentHash(sha("content"));
        input.setTenantId("0"); input.setClientId("client-a"); input.setOwnerJiacn("owner-a");
        return input;
    }

    private static String sha(String text) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is required for workspace fixture hashes", unavailable);
        }
    }

    /** Existing fixture has one authenticated owner. Cross-owner fixtures supply explicit grant authority separately. */
}
