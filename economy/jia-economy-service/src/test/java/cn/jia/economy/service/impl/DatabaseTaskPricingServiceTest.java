package cn.jia.economy.service.impl;

import cn.jia.economy.entity.EconomyModelPriceRow;
import cn.jia.economy.entity.EconomyTaskModelTariffRow;
import cn.jia.economy.entity.EconomyToolPriceRow;
import cn.jia.economy.mapper.EconomyPriceCatalogMapper;
import cn.jia.economy.pricing.*;
import cn.jia.economy.service.EconomyScope;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Map;
import static cn.jia.economy.pricing.TaskPricingService.Unavailable.Reason.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DatabaseTaskPricingServiceTest {
    private final EconomyScope scope = new EconomyScope("tenant", "client");
    private final EconomyPriceCatalogMapper mapper = mock(EconomyPriceCatalogMapper.class);
    private final DatabaseTaskPricingService service = new DatabaseTaskPricingService(mapper, new ObjectMapper());
    private final TaskWorkload work = new TaskWorkload("arbitrary-kind", 100, 0, 2, Map.of());
    private static final String DATA = """
            {"basis":"synthetic","yuanPerMillionTokens":{"INPUT":"0.668790123456789","OUTPUT":"3.343950"},
             "shortContextMaxInputTokens":100,"longContextYuanPerMillionTokens":{"INPUT":"1.337580","OUTPUT":"5.015925"}}
            """;
    private EconomyModelPriceRow price(String data) {
        var row = new EconomyModelPriceRow();
        row.setChannel("channel"); row.setModel("model"); row.setTier("standard"); row.setPriceVersion("v1"); row.setPriceDataJson(data);
        when(mapper.findCurrentModel("tenant", "client", "channel", "model", "standard")).thenReturn(row);
        return row;
    }
    private void tariff(String tools) {
        var row = new EconomyTaskModelTariffRow(); row.setChannel("channel"); row.setTier("standard"); row.setToolsJson(tools);
        when(mapper.findTaskTariff("tenant", "client", "model")).thenReturn(row);
    }
    private static String tools(String units) {
        return "[{\"channel\":\"tool-channel\",\"tool\":\"render\",\"unit\":\"image\",\"unitsPerDeliverable\":\"" + units + "\"}]";
    }
    @Test void missingModelsAndRoutesAreNotImplicitPrices() {
        assertEquals(BILLING_ROUTE_MISSING, assertThrows(TaskPricingService.Unavailable.class, () -> service.prepare(scope, work, null)).reason());
        verifyNoInteractions(mapper);
        assertEquals(BILLING_ROUTE_MISSING, assertThrows(TaskPricingService.Unavailable.class, () -> service.prepare(scope, work, "model")).reason());
        tariff("[]");
        assertEquals(MODEL_PRICE_MISSING, assertThrows(TaskPricingService.Unavailable.class, () -> service.prepare(scope, work, "model")).reason());
    }
    @Test void exactDecimalsAndWholeTaskBaseRatesArePreserved() {
        tariff("[]"); price(DATA);
        var prepared = service.prepare(scope, new TaskWorkload("any-kind", 120, 20, 1, Map.of()), "model");
        assertEquals(new BigDecimal("0.668790123456789"), prepared.modelPrice().yuanPerMillionTokens().get(ModelTokenPrice.Category.INPUT));
        assertEquals(100L, prepared.modelPrice().shortContextMaxInputTokens());
        assertTrue(prepared.modelPrice().cost(prepared.estimate().tokens()).compareTo(prepared.modelPrice().estimateCost(prepared.estimate().tokens())) > 0);
        assertEquals(1, TaskQuoteCalculator.calculate(prepared.estimate(), prepared.modelPrice(), prepared.toolPrices()).amountWen());
    }
    @Test void costReadsNeverGuessScopeChannelTierOrModel() {
        price(DATA);
        assertTrue(service.find(scope,"other-channel","model","standard").isEmpty());
        assertTrue(service.find(scope,"channel","other-model","standard").isEmpty());
        assertTrue(service.find(scope,"channel","model","batch").isEmpty());
        assertTrue(service.find(new EconomyScope("other", "client"),"channel","model","standard").isEmpty());
        assertTrue(service.find(scope,"channel"," model","standard").isEmpty());
        assertTrue(service.find(scope,null,"model","standard").isEmpty());
        assertTrue(service.findVersion(scope,"channel","model","standard",null).isEmpty());
    }
    @Test void historicalVersionDoesNotRequireCurrentPointer() {
        var row = price(DATA); reset(mapper);
        when(mapper.findModelVersion("tenant","client","channel","model","standard","v1")).thenReturn(row);
        assertEquals("v1", service.findVersion(scope,"channel","model","standard","v1").orElseThrow().version());
        assertTrue(service.find(scope,"channel","model","standard").isEmpty());
    }
    @Test void missingTokenCategoryIsNotZeroButExplicitZeroIsValid() {
        tariff("[]"); price(DATA.replace("\"INPUT\":\"0.668790123456789\",", ""));
        assertEquals(TOKEN_PRICE_MISSING, assertThrows(TaskPricingService.Unavailable.class, () -> service.prepare(scope,work,"model")).reason());
        price(DATA.replace("0.668790123456789", "0"));
        var prepared = service.prepare(scope,work,"model");
        assertEquals(0, TaskQuoteCalculator.calculate(prepared.estimate(), prepared.modelPrice(), prepared.toolPrices()).amountWen());
    }
    @Test void tariffOwnsToolQuantitiesAndMultipliesDeliverablesOnce() {
        tariff(tools("0.25")); price(DATA);
        var row = new EconomyToolPriceRow(); row.setPriceVersion("tool-v1"); row.setPriceDataJson("{\"basis\":\"synthetic\",\"yuanPerUnit\":\"0.0123456789\"}");
        when(mapper.findCurrentTool("tenant","client","tool-channel","render","image")).thenReturn(row);
        var prepared = service.prepare(scope,work,"model");
        var key = new TaskQuoteCalculator.ToolKey("tool-channel","render","image");
        assertEquals(new BigDecimal("0.50"), prepared.workload().toolUnits().get(key));
        assertEquals(prepared.workload().toolUnits(), prepared.estimate().toolUnits());
        assertEquals(new BigDecimal("0.0123456789"), prepared.toolPrices().get(key).yuanPerUnit());
    }
    @Test void nonzeroToolNeedsPriceButZeroDoesNot() {
        tariff(tools("1")); price(DATA);
        assertEquals(TOOL_PRICE_MISSING, assertThrows(TaskPricingService.Unavailable.class, () -> service.prepare(scope,work,"model")).reason());
        clearInvocations(mapper); tariff(tools("0"));
        assertTrue(service.prepare(scope,work,"model").toolPrices().isEmpty());
        verify(mapper,never()).findCurrentTool(any(),any(),any(),any(),any());
    }
    @Test void duplicateOrInvalidToolsAreConfigurationErrors() {
        price(DATA); String one = tools("1"); tariff(one.substring(0,one.length()-1)+","+one.substring(1));
        assertThrows(IllegalArgumentException.class, () -> service.prepare(scope,work,"model"));
        tariff("null"); assertThrows(IllegalArgumentException.class, () -> service.prepare(scope,work,"model"));
        tariff(tools("-1")); assertThrows(IllegalArgumentException.class, () -> service.prepare(scope,work,"model"));
    }
    @Test void corruptPriceIsNotMisreportedAsUnavailable() {
        price("{broken");
        var failure = assertThrows(RuntimeException.class, () -> service.find(scope,"channel","model","standard"));
        assertFalse(failure instanceof TaskPricingService.Unavailable);
        price(DATA.replace("INPUT", "UNKNOWN"));
        assertThrows(IllegalArgumentException.class, () -> service.find(scope,"channel","model","standard"));
    }
    @Test void newCatalogReadsDoNotMutatePreparedSnapshot() {
        tariff("[]"); price(DATA); var first = service.prepare(scope,work,"model");
        price(DATA.replace("0.668790123456789", "10")); var second = service.prepare(scope,work,"model");
        assertNotEquals(first.modelPrice(), second.modelPrice());
        assertEquals(new BigDecimal("0.668790123456789"), first.modelPrice().yuanPerMillionTokens().get(ModelTokenPrice.Category.INPUT));
        assertThrows(UnsupportedOperationException.class, () -> first.modelPrice().yuanPerMillionTokens().clear());
    }
}
