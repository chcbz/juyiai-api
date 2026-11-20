package cn.jia.agent.api;

import cn.jia.agent.entity.AgentIdentityRegistryEntity;
import cn.jia.agent.mapper.hosting.PersonaModelHostingStore;
import cn.jia.agent.position.PositionSkillException;
import cn.jia.agent.position.PositionSkillService;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.agent.service.PositionAuthority;
import cn.jia.economy.entity.EconomyModelPriceRow;
import cn.jia.economy.mapper.EconomyPriceCatalogMapper;
import cn.jia.economy.service.impl.DatabaseModelPriceMaintenance;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ModelMaintenanceOperationsTest {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final String MODEL="gpt-fixture-sol";
    private static final PositionAuthority.Scope SCOPE=new PositionAuthority.Scope("fixture","client","owner");
    private final PositionSkillService positions=mock(PositionSkillService.class);
    private final AgentIdentityService identities=mock(AgentIdentityService.class);
    private final cn.jia.agent.mapper.skill.SkillManagementMapper managed=mock(cn.jia.agent.mapper.skill.SkillManagementMapper.class);
    private final PersonaModelHostingStore bindings=mock(PersonaModelHostingStore.class);
    private final EconomyPriceCatalogMapper prices=mock(EconomyPriceCatalogMapper.class);
    private final BaseLlmModelPriceSource source=mock(BaseLlmModelPriceSource.class);
    private final ModelMaintenanceOperations operations=new ModelMaintenanceOperations(bindings,new DatabaseModelPriceMaintenance(prices,JSON),source);
    private final ModelMaintenanceAuthority authority=new ModelMaintenanceAuthority(positions,identities,managed);

    private static byte[] fixture(String expression) {
        return JSON.writeValueAsString(Map.of("success",true,"data",Map.of("billing_mode",Map.of(MODEL,"tiered_expr"),
                "billing_expr",Map.of(MODEL,expression)))).getBytes(StandardCharsets.UTF_8);
    }
    private static final String EXPR="len <= 272000 ? tier(\"0_272k\", p * 2 + cr * 0.1 + cc * 2.5 + cc12h * 5 + c * 10) : tier(\"272k_plus\", p * 4 + cr * 0.2 + cc * 5 + cc12h * 10 + c * 15)";
    private static BaseLlmModelPriceSource.Quote quote(){return new BaseLlmModelPriceSource().parse(fixture(EXPR),MODEL);}
    @Test void exactTierParsingKeepsReadWriteAndLongContextDistinct() {
        var q=quote();
        assertEquals("basellm-"+PositionSkillService.hash("v1\n"+MODEL+"\n6.6879\n272000\n"
                +"{CACHED_INPUT=0.1, INPUT=2, OUTPUT=10, cc=2.5, cc12h=5}\n"
                +"{CACHED_INPUT=0.2, INPUT=4, OUTPUT=15, cc=5, cc12h=10}"),q.version());
        assertEquals(272000L,q.threshold());assertEquals("0.1",q.shortUsd().get("CACHED_INPUT"));
        assertEquals("2.5",q.shortUsd().get("cc"));assertEquals("15",q.longUsd().get("OUTPUT"));
        assertEquals("100.3185",((Map<?,?>)q.priceData().get("longContextYuanPerMillionTokens")).get("OUTPUT"));
        assertFalse(((Map<?,?>)q.priceData().get("yuanPerMillionTokens")).containsKey("CACHE_WRITE_30M"));
        var reader=new BaseLlmModelPriceSource();
        assertEquals(404,assertThrows(PositionSkillException.class,()->reader.parse(fixture(EXPR),"missing")).status());
        assertEquals(422,assertThrows(PositionSkillException.class,()->reader.parse(fixture(EXPR.replace("cr * 0.1 + ","")),MODEL)).status());
    }
    @Test void importJoinsExactActiveModelAndVendorWithoutChangingPriceIdentity(){
        var models=JSON.writeValueAsBytes(Map.of("success",true,"data",List.of(Map.of("model_name",MODEL,"description","测试","status",1,"vendor_name","Fixture"))));
        var vendors=JSON.writeValueAsBytes(Map.of("success",true,"data",List.of(Map.of("name","Fixture","status",1))));
        var source=new BaseLlmModelPriceSource();var q=source.resolve(MODEL,models,vendors,fixture(EXPR));
        assertEquals(quote().version(),q.version());assertEquals("Fixture",q.catalog().get("vendor"));assertEquals("测试",q.catalog().get("description"));
        assertEquals(404,assertThrows(PositionSkillException.class,()->source.resolve(MODEL,models,models,fixture(EXPR))).status());
    }
    @Test void domainRequiresLiveInstallationAndSeparatePriceAuthority() {
        when(identities.requireRegistrationIdentityInScope("fixture","client","owner","agent"))
            .thenReturn(new AgentIdentityRegistryEntity().setCanonicalAgentId("agent").setBindingId(7L));
        when(positions.positionSkillInstallations(SCOPE,"agent",7L,"runtime","host",ModelMaintenanceAuthority.REQUIRED,true))
            .thenThrow(new PositionSkillException(403,"POSITION_PUBLISH_FORBIDDEN"));
        assertEquals(403,assertThrows(PositionSkillException.class,()->authority.requireInstalled(SCOPE,"agent","runtime","host",true,List.of())).status());
        verifyNoInteractions(managed);
        assertEquals(403,assertThrows(PositionSkillException.class,()->authority.requireInstalled(SCOPE,"agent","runtime","host",false,List.of())).status());
        verify(managed).lockState("fixture","client","agent","model-maintenance");
    }
    @Test void addIsScopedAndExistingDifferentVersionIsNotOverwritten() {
        when(source.read(MODEL)).thenReturn(quote());
        when(prices.insertModelVersion(any())).thenReturn(1);when(prices.insertCurrentModel(any())).thenReturn(1);
        assertEquals("CREATED",((Map<?,?>)operations.prepareAddPrice(MODEL).apply(SCOPE)).get("disposition"));
        verify(prices).insertModelVersion(argThat(r->"fixture".equals(r.getTenantId())&&"client".equals(r.getClientId())&&MODEL.equals(r.getModel())));
        var old=new EconomyModelPriceRow();old.setPriceVersion("other-price");
        when(prices.findCurrentModel("fixture","client","public-api-reference",MODEL,"standard")).thenReturn(old);
        assertEquals(409,assertThrows(PositionSkillException.class,()->operations.prepareAddPrice(MODEL).apply(SCOPE)).status());
        verify(prices,times(1)).insertCurrentModel(any());
    }
    @Test void bindingUsesScopedCasNotModelSuppliedScope() {
        assertEquals(409,assertThrows(PositionSkillException.class,()->operations.changeBinding(SCOPE,"sunerniang","before",MODEL)).status());
        when(bindings.changeModel("fixture","client","sunerniang","before",MODEL)).thenReturn(true);
        assertEquals(MODEL,((Map<?,?>)operations.changeBinding(SCOPE,"sunerniang","before",MODEL)).get("configuredModel"));
    }
}
