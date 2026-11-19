package cn.jia.agent.api;

import cn.jia.agent.entity.AgentIdentityRegistryEntity;
import cn.jia.agent.mapper.hosting.PersonaModelHostingStore;
import cn.jia.agent.position.PositionSkillException;
import cn.jia.agent.position.PositionSkillService;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.agent.service.InstalledSkillDescriptorLookup;
import cn.jia.agent.service.PositionAuthority;
import cn.jia.agent.service.SkillCapabilityLookup;
import cn.jia.chat.service.ConversationToolProvider;
import cn.jia.chat.service.PrivateConversationToolAccess;
import cn.jia.economy.entity.EconomyModelPriceRow;
import cn.jia.economy.mapper.EconomyPriceCatalogMapper;
import cn.jia.economy.service.impl.DatabaseModelPriceMaintenance;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ModelConversationToolsTest {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final String MODEL="gpt-fixture-sol";
    private static final PositionAuthority.Scope SCOPE=new PositionAuthority.Scope("fixture","client","owner");
    private static final SkillCapabilityLookup.Scope SKILL=new SkillCapabilityLookup.Scope("fixture","client","owner","agent");
    private static final ConversationToolProvider.Context CONTEXT=new ConversationToolProvider.Context(
            new PrivateConversationToolAccess.Subject("fixture","client","owner","agent"),"runtime","host");
    private static final ConversationToolProvider.Boundary BOUNDARY=new ConversationToolProvider.Boundary() {
        public <T>T execute(Supplier<T> action){return action.get();}
    };
    private final PositionSkillService positions=mock(PositionSkillService.class);
    private final AgentIdentityService identities=mock(AgentIdentityService.class);
    private final InstalledSkillDescriptorLookup installed=mock(InstalledSkillDescriptorLookup.class);
    private final PersonaModelHostingStore bindings=mock(PersonaModelHostingStore.class);
    private final EconomyPriceCatalogMapper prices=mock(EconomyPriceCatalogMapper.class);
    private final BaseLlmModelPriceSource source=mock(BaseLlmModelPriceSource.class);
    private final ModelConversationToolAdapter adapter=new ModelConversationToolAdapter(positions,identities,installed,bindings,new DatabaseModelPriceMaintenance(prices,JSON),source);

    private static byte[] fixture(String expression) {
        return JSON.writeValueAsString(Map.of("success",true,"data",Map.of("billing_mode",Map.of(MODEL,"tiered_expr"),
                "billing_expr",Map.of(MODEL,expression)))).getBytes(StandardCharsets.UTF_8);
    }
    private static final String EXPR="len <= 272000 ? tier(\"0_272k\", p * 2 + cr * 0.1 + cc * 2.5 + cc12h * 5 + c * 10) : tier(\"272k_plus\", p * 4 + cr * 0.2 + cc * 5 + cc12h * 10 + c * 15)";
    private static BaseLlmModelPriceSource.Quote quote(){return new BaseLlmModelPriceSource().parse(fixture(EXPR),MODEL);}
    private void allow() {
        when(positions.hasModelMaintenancePosition(SCOPE,"agent")).thenReturn(true);
        when(identities.requireRegistrationIdentityInScope("fixture","client","owner","agent"))
                .thenReturn(new AgentIdentityRegistryEntity().setCanonicalAgentId("agent").setBindingId(7L));
        doReturn(Set.of("si")).when(positions).modelMaintenanceInstallations(eq(SCOPE),eq("agent"),eq(7L),eq("runtime"),eq("host"),anyBoolean());
        when(installed.withPreparedRead(eq(SKILL),any())).thenAnswer(i->i.<Supplier<?>>getArgument(1).get());
        when(installed.readCurrent(SKILL)).thenReturn(new InstalledSkillDescriptorLookup.Snapshot(List.of(
                new InstalledSkillDescriptorLookup.Descriptor("model-maintenance","1.0.0","si","sha",null,null,"runtime","host",null))));
    }
    @Test void exactTierParsingKeepsReadWriteAndLongContextDistinct() {
        var q=quote();
        assertEquals(272000L,q.threshold());assertEquals("0.1",q.shortUsd().get("CACHED_INPUT"));
        assertEquals("2.5",q.shortUsd().get("cc"));assertEquals("15",q.longUsd().get("OUTPUT"));
        assertEquals("100.3185",((Map<?,?>)q.priceData().get("longContextYuanPerMillionTokens")).get("OUTPUT"));
        assertFalse(((Map<?,?>)q.priceData().get("yuanPerMillionTokens")).containsKey("CACHE_WRITE_30M"));
        var reader=new BaseLlmModelPriceSource();
        assertEquals(404,assertThrows(PositionSkillException.class,()->reader.parse(fixture(EXPR),"missing")).status());
        assertEquals(422,assertThrows(PositionSkillException.class,()->reader.parse(fixture(EXPR.replace("cr * 0.1 + ","")),MODEL)).status());
    }
    @Test void catalogOmitsUnappointedAndPriceAuthorityIsSeparate() {
        assertTrue(adapter.catalog(CONTEXT,BOUNDARY).isEmpty());
        verifyNoInteractions(installed);
        allow();
        when(positions.modelMaintenanceInstallations(SCOPE,"agent",7L,"runtime","host",true))
                .thenThrow(new PositionSkillException(403,"POSITION_PUBLISH_FORBIDDEN"));
        assertEquals(List.of("model_read_hero_binding","model_set_hero_binding"),adapter.catalog(CONTEXT,BOUNDARY).stream().map(t->t.get("name")).toList());
        assertThrows(PositionSkillException.class,()->adapter.call(CONTEXT,BOUNDARY,"model_add_price",Map.of("model",MODEL)));
        verifyNoInteractions(source,prices);
    }
    @Test void callbacksRecheckInstallationAndRevocationBeforePriceCommit() {
        allow();
        when(source.read(MODEL)).thenAnswer(i->{when(positions.modelMaintenanceInstallations(SCOPE,"agent",7L,"runtime","host",true))
                .thenThrow(new PositionSkillException(403,"MODEL_POSITION_REQUIRED"));return quote();});
        assertThrows(PositionSkillException.class,()->adapter.call(CONTEXT,BOUNDARY,"model_add_price",Map.of("model",MODEL)));
        verifyNoInteractions(prices);
        allow();
        when(installed.readCurrent(SKILL)).thenReturn(new InstalledSkillDescriptorLookup.Snapshot(List.of()));
        assertThrows(PositionSkillException.class,()->adapter.call(CONTEXT,BOUNDARY,"model_read_price",Map.of("model",MODEL)));
    }
    @Test void addIsScopedAndExistingDifferentVersionIsNotOverwritten() {
        allow();when(source.read(MODEL)).thenReturn(quote());
        when(prices.insertModelVersion(any())).thenReturn(1);when(prices.insertCurrentModel(any())).thenReturn(1);
        assertEquals("CREATED",((Map<?,?>)adapter.call(CONTEXT,BOUNDARY,"model_add_price",Map.of("model",MODEL))).get("disposition"));
        verify(prices).insertModelVersion(argThat(r->"fixture".equals(r.getTenantId())&&"client".equals(r.getClientId())&&MODEL.equals(r.getModel())));
        var old=new EconomyModelPriceRow();old.setPriceVersion("other-price");
        when(prices.findCurrentModel("fixture","client","public-api-reference",MODEL,"standard")).thenReturn(old);
        assertEquals(409,assertThrows(PositionSkillException.class,()->adapter.call(CONTEXT,BOUNDARY,"model_add_price",Map.of("model",MODEL))).status());
        verify(prices,times(1)).insertCurrentModel(any());
    }
    @Test void bindingUsesScopedCasNotModelSuppliedScope() {
        allow();var args=Map.<String,Object>of("personaCode","sunerniang","expectedModel","before","model",MODEL);
        assertEquals(409,assertThrows(PositionSkillException.class,()->adapter.call(CONTEXT,BOUNDARY,"model_set_hero_binding",args)).status());
        when(bindings.changeModel("fixture","client","sunerniang","before",MODEL)).thenReturn(true);
        assertEquals(MODEL,((Map<?,?>)adapter.call(CONTEXT,BOUNDARY,"model_set_hero_binding",args)).get("configuredModel"));
        assertEquals(400,assertThrows(PositionSkillException.class,()->adapter.call(CONTEXT,BOUNDARY,"model_set_hero_binding",Map.of(
                "personaCode","sunerniang","expectedModel","before","model",MODEL,"tenantId","other"))).status());
    }
}
