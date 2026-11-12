package cn.jia.agent.hosting;

import cn.jia.agent.config.AgentHostingRentProperties;
import cn.jia.agent.dao.AgentPersonaBindingDao;
import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.entity.*;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.agent.service.AgentRuntimeV1Service;
import cn.jia.core.security.FixedTenantAdmissionPolicy;
import cn.jia.economy.hosting.HostingRentRenewalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.ArrayList;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HostingRentTransitionApplicationTest {
    private final HostingRentOwnerResolver owners = mock(HostingRentOwnerResolver.class);
    private final AgentIdentityService identities = mock(AgentIdentityService.class);
    private final AgentPersonaBindingDao bindings = mock(AgentPersonaBindingDao.class);
    private final AgentRuntimeDao runtimes = mock(AgentRuntimeDao.class);
    private final AgentRuntimeV1Service installations = mock(AgentRuntimeV1Service.class);
    private final HostingRentRenewalService renewal = mock(HostingRentRenewalService.class);
    private final HostingRentHttp.Actor actor = new HostingRentHttp.Actor("login", "tenant", "client", "owner");
    private final String agent = "jyt-fixture-husanniang", installation = "rti_" + "1".repeat(32);
    private final long cutover = System.currentTimeMillis() - 1000;
    private AgentPersonaBindingEntity binding;
    private AgentRuntimeEntity projection;
    private HostingRentTransitionApplication application;
    private final ArrayList<HostingRentRenewalService.Transition> registered = new ArrayList<>();
    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:transition_" + UUID.randomUUID(), "sa", "");
        application = new HostingRentTransitionApplication(owners, identities, bindings, runtimes, installations,
                renewal, new AgentHostingRentProperties(true, "1", "500", "2592000", "1", "50"),
                new FixedTenantAdmissionPolicy("tenant", "client"), new DataSourceTransactionManager(ds));
        when(owners.requireOwner(actor)).thenReturn("owner");
        when(installations.lockExistingInstallation("tenant", "client", "owner", installation)).thenReturn(
                new AgentRuntimeV1InstallationView(installation, "tenant", "client", agent, "1", "a".repeat(64), 0, "ACTIVE", cutover));
        when(identities.requireRegistrationIdentityInScope("tenant", "client", "owner", agent))
                .thenReturn(new AgentIdentityRegistryEntity().setCanonicalAgentId(agent).setBindingId(7L));
        binding = new AgentPersonaBindingEntity().setId(7L).setAgentId(agent).setPersonaCode("husanniang").setJiacn("owner").setStatus(1);
        binding.setClientId("client"); binding.setTenantId("tenant");
        when(bindings.findByIdForUpdate(7L)).thenReturn(binding);
        projection = new AgentRuntimeEntity().setBindingId(7L).setOwnerJiacn("owner")
                .setRuntimeInstallationId(installation).setStatus("offline").setLastSeenAt(1L);
        when(runtimes.lockInScope("tenant", "client", agent)).thenReturn(projection);
        when(renewal.registerTransition(any())).thenAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            HostingRentRenewalService.Transition terms = call.getArgument(0); registered.add(terms); return terms.leaseId();
        });
    }
    private HostingRentTransitionApplication.Request request(String plan) {
        return new HostingRentTransitionApplication.Request(actor, agent, installation, plan, cutover);
    }
    private HostingRentTransitionApplication.Request request() { return request("agent-self-hosted"); }
    @Test void previewChecksOriginalSubjectWithoutGrantOrRuntimeMutation() {
        var terms = application.preview(request());
        assertEquals("agent-self-hosted", terms.planId()); assertEquals(cutover, terms.cutoverAt());
        assertEquals(installation, terms.installationId()); verifyNoInteractions(renewal);
        verify(installations, never()).ensureInstallation(anyString(), anyString(), anyString(), any(), anyLong());
    }
    @Test void offlineHostWithoutAddressOrRouteGetsStableTermsInsideOriginalTransaction() {
        assertNull(projection.getRuntimeHostId());
        assertEquals(application.register(request()), application.register(request()));
        assertEquals(2, registered.size()); assertEquals(registered.getFirst(), registered.getLast());
        assertEquals("agent-self-hosted", registered.getFirst().planId());
        assertEquals(installation, registered.getFirst().installationId());
        assertEquals(agent, registered.getFirst().agentId()); assertEquals(cutover, registered.getFirst().cutoverAt());
        var order = inOrder(installations, bindings, runtimes, renewal);
        order.verify(installations).lockExistingInstallation("tenant", "client", "owner", installation);
        order.verify(bindings).findByIdForUpdate(7L); order.verify(runtimes).lockInScope("tenant", "client", agent);
        order.verify(renewal).registerTransition(any());
        verify(installations, never()).ensureInstallation(anyString(), anyString(), anyString(), any(), anyLong());
        verify(bindings, never()).insert(any());
    }
    @Test void managedPlanIsExplicitNotInferredFromTheHost() {
        application.register(request("agent-hosting-preview"));
        assertEquals("agent-hosting-preview", registered.getFirst().planId());
        assertEquals(cutover, registered.getFirst().cutoverAt());
    }
    @Test void missingOrUnknownPlanFailsBeforeIdentityOrFinancialCalls() {
        for (String plan : new String[]{null, "", "unknown", " agent-self-hosted"})
            assertThrows(IllegalArgumentException.class, () -> application.register(request(plan)));
        verifyNoInteractions(owners, installations, bindings, renewal);
    }
    @Test void changedOwnerTenantOrBindingCannotReceiveGrant() {
        binding.setJiacn("other"); assertThrows(IllegalStateException.class, () -> application.register(request()));
        binding.setJiacn("owner"); binding.setTenantId("other");
        assertThrows(IllegalStateException.class, () -> application.register(request()));
        binding.setTenantId("tenant"); binding.setStatus(0);
        assertThrows(IllegalStateException.class, () -> application.register(request())); verifyNoInteractions(renewal);
    }
    @Test void changedInstallationProjectionCannotReceiveGrant() {
        projection.setRuntimeInstallationId("another-installation");
        assertThrows(IllegalStateException.class, () -> application.register(request())); verifyNoInteractions(renewal);
    }
    @Test void wrongInstallationOrInvalidCutoverNeverRegisters() {
        when(installations.lockExistingInstallation("tenant", "client", "owner", installation)).thenReturn(null);
        assertThrows(IllegalStateException.class, () -> application.register(request()));
        assertThrows(IllegalArgumentException.class, () -> application.register(
                new HostingRentTransitionApplication.Request(actor, agent, installation, "agent-self-hosted", 0)));
        verifyNoInteractions(renewal);
    }
}
