package cn.jia.agent.service.impl;

import cn.jia.agent.common.WaterMarginPersonaCatalog;
import cn.jia.agent.dao.AgentPersonaDao;
import cn.jia.agent.entity.AgentPersonaEntity;
import cn.jia.agent.service.AgentOwnerScope;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Catalog mapping tests; no runtime registration, provider request or database mutation. */
class AgentPersonaPublicScopeTest {
    @ParameterizedTest @ValueSource(strings = {"0", "1", "tenant-blue"})
    void everyTenantUsesSameGlobalDefinitionsAndOnlyItsOwnOverrides(String tenant) {
        var dao = mock(AgentPersonaDao.class);
        var service = service(dao);
        var override = WaterMarginPersonaCatalog.publicPersona("wuyong");
        override.setTenantId(tenant);
        override.setClientId("client");
        override.setName("private name");
        when(dao.findCatalogProjection(tenant, "client")).thenReturn(List.of(override));
        List<AgentPersonaEntity> catalog = ReflectionTestUtils.invokeMethod(service, "loadPersonaCatalog",
                new AgentOwnerScope(tenant, "client", "owner"));
        assertNotNull(catalog);
        assertEquals(108, catalog.size());
        assertEquals("private name", catalog.stream().filter(p -> "wuyong".equals(p.getPersonaCode()))
                .findFirst().orElseThrow().getName());
        assertEquals(107, catalog.stream().filter(WaterMarginPersonaCatalog::isPublicDefinition).count());
        assertEquals("吴用", WaterMarginPersonaCatalog.publicPersona("wuyong").getName());
        verify(dao).findCatalogProjection(tenant, "client");
        verifyNoMoreInteractions(dao);
    }

    @ParameterizedTest @ValueSource(strings = {"0", "1", "tenant-blue"})
    void missingPrivateDefinitionResolvesBuiltinWithoutProductionLookup(String tenant) {
        var dao = mock(AgentPersonaDao.class);
        var service = service(dao);
        AgentPersonaEntity persona = ReflectionTestUtils.invokeMethod(service, "requireScopedPersona",
                tenant, "client", "wuyong");
        assertTrue(WaterMarginPersonaCatalog.isPublicDefinition(persona));
        verify(dao).findCatalogPersona(tenant, "client", "wuyong");
        verifyNoMoreInteractions(dao);
    }

    @Test void scopedPrivateDefinitionUsesOneLookupAndMissingPersonaStillFails() {
        var dao = mock(AgentPersonaDao.class);
        var service = service(dao);
        var row = WaterMarginPersonaCatalog.publicPersona("wuyong");
        row.setTenantId("tenant-blue");
        row.setClientId("client");
        when(dao.findCatalogPersona("tenant-blue", "client", "wuyong")).thenReturn(row);
        assertSame(row, ReflectionTestUtils.invokeMethod(service, "requireScopedPersona",
                "tenant-blue", "client", "wuyong"));
        assertThrows(AgentServiceImpl.AgentBizException.class, () -> ReflectionTestUtils.invokeMethod(
                service, "requireScopedPersona", "tenant-blue", "client", "missing-persona"));
        verify(dao).findCatalogPersona("tenant-blue", "client", "wuyong");
        verify(dao).findCatalogPersona("tenant-blue", "client", "missing-persona");
        verifyNoMoreInteractions(dao);
    }

    @Test void personaConfigurationIsPublicMetadataNotAnExecutionModelOrAnAuthorization() {
        var service = service(mock(AgentPersonaDao.class));
        var scope = new AgentOwnerScope("tenant", "client", "owner");
        var catalog = new cn.jia.agent.cache.AgentPersonaCatalogCache().get("tenant", "client",
                WaterMarginPersonaCatalog::publicPersonas);
        for (var entry : catalog.entries()) {
            cn.jia.agent.entity.AgentRuntimeDTO dto = ReflectionTestUtils.invokeMethod(service,
                    "toCatalogDTO", entry, null, scope);
            assertNotNull(dto);
            var hero = WaterMarginPersonaCatalog.definition(entry.personaCode());
            assertEquals(hero.modelId(), dto.getConfiguredModel());
            assertEquals(WaterMarginPersonaCatalog.MODEL_ASSIGNMENT_VERSION, dto.getModelAssignmentVersion());
            assertEquals(Long.toString(cn.jia.economy.hosting.HostingModelPriceBook.forModel(hero.modelId()).amountWen()),
                    dto.getPlatformHostingAmountWen());
            assertEquals("2592000", dto.getPlatformHostingPeriodSeconds());
            assertEquals("hero-hosting-wen-v1", dto.getPlatformHostingPriceVersion());
            assertNull(dto.getExecutionModel());
            assertFalse(dto.getCanOperate());
            assertEquals(!entry.systemAgent(), dto.getCanBind());
        }
        var privateAgent = new cn.jia.agent.entity.AgentRuntimeDTO();
        privateAgent.setPersonaCode("custom-persona"); privateAgent.setExecutionModel("private-runtime-model");
        ReflectionTestUtils.invokeMethod(service, "applyPersonaModelConfiguration", privateAgent);
        assertNull(privateAgent.getConfiguredModel()); assertNull(privateAgent.getPlatformHostingAmountWen());
        assertEquals("private-runtime-model", privateAgent.getExecutionModel());
    }

    private AgentServiceImpl service(AgentPersonaDao dao) {
        var service = mock(AgentServiceImpl.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(service, "agentPersonaDao", dao);
        return service;
    }
}
