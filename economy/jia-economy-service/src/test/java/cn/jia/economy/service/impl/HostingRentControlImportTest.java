package cn.jia.economy.service.impl;

import cn.jia.economy.config.EconomyPreviewGate;
import cn.jia.economy.entity.EconomyHostingLeaseEntity;
import cn.jia.economy.mapper.EconomyHostingRentMapper;
import cn.jia.economy.service.EconomyScope;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HostingRentControlImportTest {
    private final EconomyHostingRentMapper leases = mock(EconomyHostingRentMapper.class);
    private final EconomyPreviewGate gate = mock(EconomyPreviewGate.class);
    private final HostingRentRenewalServiceImpl service = new HostingRentRenewalServiceImpl(
            leases, null, null, null, gate, mock(PlatformTransactionManager.class));
    private final EconomyScope scope = new EconomyScope("0", "client");

    @Test void requiresCallerTransactionBeforeTakingLeaseLock() {
        assertThrows(IllegalStateException.class, () -> service.lockLatestLease(scope, "agent"));
        verifyNoInteractions(leases);
    }

    @Test void locksExactScopeWithoutChangingLeaseOrMoney() {
        var lease = new EconomyHostingLeaseEntity().setLeaseId("lease").setPaidThrough(123L);
        when(leases.selectLatestLeaseForUpdate("0", "client", "agent")).thenReturn(lease);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertSame(lease, service.lockLatestLease(scope, "agent"));
            assertEquals(123L, lease.getPaidThrough());
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        verify(gate).requireMutationAllowed("0", "client");
        verify(leases).selectLatestLeaseForUpdate("0", "client", "agent");
        verifyNoMoreInteractions(leases);
    }

    @Test void missingLeaseIsNotSynthesized() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertNull(service.lockLatestLease(scope, "missing"));
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        verify(leases).selectLatestLeaseForUpdate("0", "client", "missing");
        verifyNoMoreInteractions(leases);
    }
}
