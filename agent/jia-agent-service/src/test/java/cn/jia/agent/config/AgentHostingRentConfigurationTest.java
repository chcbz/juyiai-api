package cn.jia.agent.config;

import cn.jia.agent.service.HostingRentAdmissionException;
import cn.jia.agent.service.HostingRentAdmissionService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentHostingRentConfigurationTest {
    private static final ApplicationContextRunner RUNNER = new ApplicationContextRunner()
            .withUserConfiguration(AgentHostingRentConfiguration.class);

    @Test
    void absentConfigurationIsDisabledAndNotConfigured() {
        RUNNER.run(context -> {
            assertNull(context.getStartupFailure());
            AgentHostingRentProperties properties = context.getBean(AgentHostingRentProperties.class);
            assertFalse(properties.enabled());
            assertFalse(properties.descriptorConfigured());
            assertEquals("1", properties.planVersion());
            assertEquals("", properties.amountWen());
            assertEquals("2592000", properties.periodSeconds());
            assertFalse(properties.configured());
            assertReason(context.getBean(HostingRentAdmissionService.class),
                    HostingRentAdmissionException.Reason.HOSTING_RENT_NOT_CONFIGURED);
        });
    }

    @Test
    void disabledOrIncompleteConfigurationCannotActivateAdmission() {
        RUNNER.withPropertyValues(
                        "agent.hosting-rent.enabled=false",
                        "agent.hosting-rent.plan-version=1",
                        "agent.hosting-rent.amount-wen=1000000000",
                        "agent.hosting-rent.period-seconds=2592000")
                .run(context -> assertReason(context.getBean(HostingRentAdmissionService.class),
                        HostingRentAdmissionException.Reason.HOSTING_RENT_NOT_CONFIGURED));

        RUNNER.withPropertyValues(
                        "agent.hosting-rent.enabled=true",
                        "agent.hosting-rent.plan-version=1",
                        "agent.hosting-rent.amount-wen=01",
                        "agent.hosting-rent.period-seconds=2592000")
                .run(context -> assertReason(context.getBean(HostingRentAdmissionService.class),
                        HostingRentAdmissionException.Reason.HOSTING_RENT_NOT_CONFIGURED));
    }

    @Test
    void completeVersionedConfigurationStillCannotOpenLegacyFreeHosting() {
        RUNNER.withPropertyValues(
                        "agent.hosting-rent.enabled=true",
                        "agent.hosting-rent.plan-version=1",
                        "agent.hosting-rent.amount-wen=1000000000",
                        "agent.hosting-rent.period-seconds=2592000")
                .run(context -> {
                    AgentHostingRentProperties properties = context.getBean(AgentHostingRentProperties.class);
                    assertTrue(properties.descriptorConfigured());
                    assertTrue(properties.configured());
                    assertEquals("1", properties.planVersion());
                    assertEquals("1000000000", properties.amountWen());
                    assertEquals("2592000", properties.periodSeconds());
                    assertReason(context.getBean(HostingRentAdmissionService.class),
                            HostingRentAdmissionException.Reason.HOSTING_RENT_NOT_READY);
                });
    }

    @Test void bindsOpaqueModelKeysAndPricesFromExternalProperties() {
        RUNNER.withPropertyValues(
                "agent.hosting-rent.enabled=true", "agent.hosting-rent.amount-wen=500",
                "agent.hosting-rent.persona-models[fixture-hero]=vendor.future-42",
                "agent.hosting-rent.model-plans[vendor.future-42].plan-version=17",
                "agent.hosting-rent.model-plans[vendor.future-42].amount-wen=730")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    var properties = context.getBean(AgentHostingRentProperties.class);
                    assertTrue(properties.configured());
                    assertEquals("vendor.future-42", properties.configuredModel("fixture-hero"));
                    assertEquals("730", properties.modelPlan("fixture-hero").amountWen());
                    assertEquals("17", properties.modelPlan("fixture-hero").planVersion());
                    assertNull(properties.modelPlan("unknown"));
                });
    }

    @Test void conflictingVersionsOrUnpricedMappingsRejectConfiguration() {
        var plan = new AgentHostingRentProperties.ModelPlan("22", "730");
        assertThrows(IllegalArgumentException.class, () -> new AgentHostingRentProperties(false, "1", "500", null, null, null,
                java.util.Map.of("hero", "missing"), java.util.Map.of("a", plan)));
        assertThrows(IllegalArgumentException.class, () -> new AgentHostingRentProperties(false, "1", "500", null, null, null,
                java.util.Map.of(), java.util.Map.of("a", plan, "b", plan)));
        assertThrows(IllegalArgumentException.class, () -> new AgentHostingRentProperties(false, "22", "500", null, null, null,
                java.util.Map.of(), java.util.Map.of("a", plan)));
        assertThrows(IllegalArgumentException.class, () -> new AgentHostingRentProperties.ModelPlan("22", "0"));
        assertFalse(new AgentHostingRentProperties(false, null, null, null, null, null).differentiated());
    }

    private static void assertReason(HostingRentAdmissionService service,
            HostingRentAdmissionException.Reason expected) {
        HostingRentAdmissionException failure = assertThrows(
                HostingRentAdmissionException.class, service::requireServerBindAvailable);
        assertEquals(expected, failure.reason());
    }
}
