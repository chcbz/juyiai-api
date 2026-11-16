package cn.jia.chat.service;

import cn.jia.agent.service.AgentTaskBountyBootstrapOutboxService;
import cn.jia.chat.config.ChatTypedDeliberationReadiness.Readiness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.transaction.CannotCreateTransactionException;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.ArrayList;
import java.util.List;
import cn.jia.agent.entity.AgentTaskBountyBootstrapWakeup;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Regression for the real 2026-10-26 DB outage leaving all later normal starts PENDING. */
class ChatBountyBootstrapRelayRecoveryTest {
    private final AgentTaskBountyBootstrapOutboxService outbox = mock(AgentTaskBountyBootstrapOutboxService.class);
    private final ChatBountyBootstrapAdmissionService admission = mock(ChatBountyBootstrapAdmissionService.class);
    private final ChatTypedDeliberationContextService contexts = mock(ChatTypedDeliberationContextService.class);
    private final cn.jia.task.scheduling.ScheduleService scheduler = mock(cn.jia.task.scheduling.ScheduleService.class);
    private final org.springframework.transaction.PlatformTransactionManager transactions = mock(org.springframework.transaction.PlatformTransactionManager.class);
    private final cn.jia.task.scheduling.ScheduleKey key = new cn.jia.task.scheduling.ScheduleKey("",ChatBountyBootstrapRelay.HANDLER,"recovery","node-a");
    private final AtomicLong now = new AtomicLong(1_000L);
    private ChatBountyBootstrapRelay relay;
    private Runnable tick;
    private final List<Runnable> callbacks = new ArrayList<>();
    private final List<Long> delays = new ArrayList<>();

    @BeforeEach void start() {
        when(contexts.bootstrapReadiness()).thenReturn(Readiness.READY);
        when(transactions.getTransaction(any())).thenReturn(new org.springframework.transaction.support.SimpleTransactionStatus());
        doAnswer(call -> {
            assertEquals(key,call.getArgument(0));
            callbacks.add(() -> relay.executeScheduled(new cn.jia.task.scheduling.ScheduledInvocation("0",key,1)));
            delays.add((Long)call.getArgument(1)-now.get());
            return null;
        }).when(scheduler).signal(any(), anyLong());
        relay = new ChatBountyBootstrapRelay(outbox, admission, contexts, scheduler, transactions,
                new cn.jia.core.security.FixedTenantAdmissionPolicy("0",null), "node-a", now::get);
        relay.start();
        verifyNoInteractions(scheduler, outbox);
        relay.onApplicationReady(null);
        assertEquals(List.of(0L), delays);
        tick = () -> callbacks.get(callbacks.size() - 1).run();
    }

    private static cn.jia.agent.entity.AgentTaskBountyBootstrapClaimDTO claim(String task) {
        return new cn.jia.agent.entity.AgentTaskBountyBootstrapClaimDTO("bootstrap_"+task,"0","client","owner",task,
                "action_"+task,1,"TASK_REQUIREMENT_REVISION_V1",1,"agent","grant_"+task,1,"DELIBERATE",
                List.of(),"a".repeat(64),"worker",10000,1,1);
    }

    private void echoReconciliation() {
        when(outbox.reconcile(any(),any(),anyLong())).thenAnswer(call -> {
            cn.jia.agent.entity.AgentTaskBountyBootstrapReconcileDTO command=call.getArgument(1);
            String status=switch(command.outcome()) {
                case ADMITTED -> "ADMITTED";
                case RETRYABLE_FAILURE -> "RETRY";
                case TERMINAL_FAILURE -> "DEAD";
            };
            return new cn.jia.agent.entity.AgentTaskBountyBootstrapReconcileResultDTO(command.bootstrapId(),status,
                    command.expectedOutboxVersion()+1,command.conversationId(),command.initialRequestId());
        });
    }

    @Test void twoRejectedIntentsDoNotStopTheFollowingNormalTask() {
        var first=claim("683");var second=claim("684");var next=claim("689");
        when(outbox.claimNextAvailable(anyString(),anyLong())).thenReturn(first,second,next);
        // Deterministic invalid input/route is local even when different users hit it consecutively.
        when(admission.admit(first)).thenThrow(new ChatDeliberationException(ChatDeliberationException.Reason.INVALID_REQUEST,"invalid immutable input"));
        when(admission.admit(second)).thenThrow(new ChatDeliberationException(ChatDeliberationException.Reason.INVALID_ROUTE,"invalid route"));
        when(admission.admit(next)).thenReturn(new ChatBountyBootstrapAdmissionService.Admission("conversation",1,"request","message","step",false));
        echoReconciliation();
        tick.run();tick.run();tick.run();
        assertTrue(relay.isRunning());verify(admission).admit(next);
        var commands=org.mockito.ArgumentCaptor.forClass(cn.jia.agent.entity.AgentTaskBountyBootstrapReconcileDTO.class);
        verify(outbox,times(3)).reconcile(any(),commands.capture(),anyLong());
        assertEquals(List.of(cn.jia.agent.entity.AgentTaskBountyBootstrapReconcileDTO.Outcome.TERMINAL_FAILURE,
                cn.jia.agent.entity.AgentTaskBountyBootstrapReconcileDTO.Outcome.TERMINAL_FAILURE,
                cn.jia.agent.entity.AgentTaskBountyBootstrapReconcileDTO.Outcome.ADMITTED),commands.getAllValues().stream().map(cn.jia.agent.entity.AgentTaskBountyBootstrapReconcileDTO::outcome).toList());
        assertEquals("BOUNTY_INVALID_REQUEST",commands.getAllValues().getFirst().errorCode());
        verify(scheduler,never()).cancel(any());
    }

    @Test void terminalRejectionWithoutDurableAcknowledgmentStillFailsClosed() {
        var bad=claim("bad");when(outbox.claimNextAvailable(anyString(),anyLong())).thenReturn(bad);
        when(admission.admit(bad)).thenThrow(new ChatDeliberationException(ChatDeliberationException.Reason.INVALID_REQUEST,"invalid input"));
        when(outbox.reconcile(any(),any(),anyLong())).thenReturn(
                new cn.jia.agent.entity.AgentTaskBountyBootstrapReconcileResultDTO(bad.bootstrapId(),"RETRY",2,null,null));
        tick.run();tick.run();assertFalse(relay.isRunning());
    }

    @Test void persistenceFailureIsNotMistakenForAUserRejection() {
        var bad=claim("bad");when(outbox.claimNextAvailable(anyString(),anyLong())).thenReturn(bad);
        when(admission.admit(bad)).thenThrow(new ChatDeliberationException(ChatDeliberationException.Reason.PERSISTENCE_ERROR,"database unavailable"));
        echoReconciliation();tick.run();tick.run();assertFalse(relay.isRunning());
        verify(outbox,times(2)).reconcile(any(),argThat(command -> command.outcome()==cn.jia.agent.entity.AgentTaskBountyBootstrapReconcileDTO.Outcome.RETRYABLE_FAILURE),anyLong());
    }

    @Test void twoConnectionFailuresDoNotPermanentlyDisableNormalTaskAdmission() {
        var unavailable = new CannotCreateTransactionException("JDBC unavailable",
                new SQLTransientConnectionException("pool unavailable", "08001"));
        when(outbox.claimNextAvailable(anyString(), anyLong())).thenThrow(unavailable).thenThrow(unavailable).thenReturn(null);
        tick.run();
        assertEquals(1_000L, delays.get(delays.size() - 1).longValue());
        now.set(1_500); tick.run(); // No repeated DB access during recovery backoff.
        verify(outbox, times(1)).claimNextAvailable(anyString(), anyLong());
        now.set(2_000); tick.run();
        assertEquals(2_000L, delays.get(delays.size() - 1).longValue());
        assertTrue(relay.isRunning());
        now.set(3_999); tick.run();
        verify(outbox, times(2)).claimNextAvailable(anyString(), anyLong());
        now.set(4_000); tick.run();
        verify(outbox, times(3)).claimNextAvailable(anyString(), anyLong());
        assertTrue(relay.isRunning());
        verifyNoInteractions(admission); // Never bypass lease acquisition with a direct dispatch.
        verify(scheduler, never()).cancel(any());
    }

    @Test void successfulClaimPollResetsRecoveryDelay() {
        var unavailable = new TransientDataAccessResourceException("DB restarting");
        when(outbox.claimNextAvailable(anyString(), anyLong()))
                .thenThrow(unavailable).thenThrow(unavailable).thenReturn(null).thenThrow(unavailable).thenReturn(null);
        tick.run(); now.set(2_000); tick.run(); now.set(4_000); tick.run();
        now.set(4_500); tick.run();
        now.set(5_499); tick.run();
        verify(outbox, times(4)).claimNextAvailable(anyString(), anyLong());
        now.set(5_500); tick.run();
        verify(outbox, times(5)).claimNextAvailable(anyString(), anyLong());
        assertTrue(relay.isRunning());
    }

    @Test void connectionSqlStateInsideTransactionFailureIsRecoverable() {
        when(outbox.claimNextAvailable(anyString(), anyLong())).thenThrow(
                new CannotCreateTransactionException("open failed", new SQLException("connection unavailable", "08006")));
        tick.run(); now.set(2_000); tick.run();
        assertTrue(relay.isRunning());
        verifyNoInteractions(admission);
    }

    @Test void recoveryBackoffIsCappedWithoutExpiringOrClaimingWorkEarly() {
        when(outbox.claimNextAvailable(anyString(), anyLong())).thenThrow(
                new TransientDataAccessResourceException("DB unavailable"));
        long delay = ChatBountyBootstrapRelay.CLAIM_RETRY_BASE_MILLIS;
        for (int i = 0; i < 13; i++) {
            long attemptAt = now.get();
            tick.run();
            verify(outbox, times(i + 1)).claimNextAvailable(anyString(), anyLong());
            now.set(attemptAt + delay - 1); tick.run();
            verify(outbox, times(i + 1)).claimNextAvailable(anyString(), anyLong());
            now.set(attemptAt + delay);
            delay = Math.min(ChatBountyBootstrapRelay.CLAIM_RETRY_MAX_MILLIS, delay * 2);
        }
        assertTrue(relay.isRunning());
        verifyNoInteractions(admission);
    }

    @Test void schemaFailureIsNotClassifiedAsTransientInfrastructure() {
        when(outbox.claimNextAvailable(anyString(), anyLong())).thenThrow(
                new DataAccessResourceFailureException("schema missing", new SQLException("missing table", "42S02")));
        tick.run(); tick.run();
        assertFalse(relay.isRunning());
        verify(scheduler, never()).cancel(any());
        verifyNoInteractions(admission);
    }

    @Test void authorizationFailureStillFailsClosed() {
        when(outbox.claimNextAvailable(anyString(), anyLong())).thenThrow(
                new DataAccessResourceFailureException("access denied", new SQLException("denied", "28000")));
        tick.run(); tick.run();
        assertFalse(relay.isRunning());
        verifyNoInteractions(admission);
    }

    @Test void stoppedSchedulerCannotResumeClaimsAfterDatabaseRecovery() {
        when(outbox.claimNextAvailable(anyString(), anyLong())).thenThrow(
                new TransientDataAccessResourceException("DB restarting"));
        tick.run(); relay.stop(); now.set(1_000_000); tick.run();
        verify(outbox, times(1)).claimNextAvailable(anyString(), anyLong());
        assertFalse(relay.isRunning());
        verifyNoInteractions(admission);
    }

    @Test void explicitLifecycleRestartDoesNotInheritOldBackoff() {
        when(outbox.claimNextAvailable(anyString(), anyLong()))
                .thenThrow(new TransientDataAccessResourceException("DB restarting")).thenReturn(null);
        tick.run(); relay.stop(); relay.start(); relay.onApplicationReady(null);
        tick.run();
        verify(outbox, times(2)).claimNextAvailable(anyString(), anyLong());
        assertTrue(relay.isRunning());
    }

    @Test void emptyQueueSleepsAndCommittedHintWakesWithoutWaitingForRecovery() {
        tick.run();
        assertEquals(5_000L, delays.get(delays.size() - 1));
        now.set(1_100);
        relay.onWorkAvailable(new AgentTaskBountyBootstrapWakeup(now.get()));
        assertEquals(0L, delays.get(delays.size() - 1));
        // Claim fencing/cancelled wake races now belong to task, not a second local timer.
        verify(outbox, times(1)).claimNextAvailable(anyString(), anyLong());
        tick.run();
        verify(outbox, times(2)).claimNextAvailable(anyString(), anyLong());
        verify(scheduler, never()).reschedule(any(),anyLong());
    }

    @Test void notificationStormCoalescesAndInFlightHintIsNotLost() {
        tick.run();
        int before = callbacks.size();
        for (int i = 0; i < 1000; i++) relay.onWorkAvailable(new AgentTaskBountyBootstrapWakeup(now.get()));
        assertEquals(before + 1, callbacks.size());
        when(outbox.claimNextAvailable(anyString(), anyLong())).thenAnswer(ignored -> {
            relay.onWorkAvailable(new AgentTaskBountyBootstrapWakeup(now.get()));
            return null;
        });
        tick.run();
        assertEquals(0L, delays.get(delays.size() - 1));
        assertEquals(before + 2, callbacks.size());
    }

    @Test void retryHintSurvivesImmediateDrainAndWakesAtItsOriginalDueTime() {
        relay.onWorkAvailable(new AgentTaskBountyBootstrapWakeup(2_000));
        tick.run();
        assertEquals(1_000L, delays.get(delays.size() - 1));
        now.set(2_000); tick.run();
        assertEquals(5_000L, delays.get(delays.size() - 1));
    }

    @Test void stoppedLifecycleRejectsCallbacksAndHintsThenExplicitRestartResumes() {
        Runnable old = callbacks.get(0);
        relay.stop();
        int before = callbacks.size();
        relay.onWorkAvailable(new AgentTaskBountyBootstrapWakeup(now.get()));
        assertEquals(before, callbacks.size());
        old.run(); verifyNoInteractions(outbox);
        relay.start(); relay.onApplicationReady(null);
        tick.run();
        verify(outbox).claimNextAvailable(anyString(), anyLong());
    }

    @Test void initializingSchemaNeverConsumesBusinessClaim() {
        when(contexts.bootstrapReadiness()).thenReturn(Readiness.INITIALIZING);
        tick.run();
        verifyNoInteractions(outbox,admission);
        assertEquals(500L,delays.getLast());
        when(contexts.bootstrapReadiness()).thenReturn(Readiness.READY);
        now.addAndGet(500);tick.run();
        verify(outbox).claimNextAvailable(anyString(),anyLong());
    }

    @Test void afterCommitHintUsesIndependentTransactionBeforeReportingScheduled() {
        tick.run();
        relay.onWorkAvailable(new AgentTaskBountyBootstrapWakeup(now.get()));
        var definition=org.mockito.ArgumentCaptor.forClass(org.springframework.transaction.TransactionDefinition.class);
        verify(transactions).getTransaction(definition.capture());
        assertEquals(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW,
                definition.getValue().getPropagationBehavior());
        verify(transactions).commit(any());
    }

}
