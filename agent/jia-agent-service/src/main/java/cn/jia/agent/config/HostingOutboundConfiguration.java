package cn.jia.agent.config;

import cn.jia.agent.hosting.HostingOutboundExchangeService;
import cn.jia.agent.mapper.hosting.HostingOutboundStore;
import cn.jia.core.security.FixedTenantAdmissionPolicy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Explicit feature activation after authorized schema maintenance; never creates schema at startup. */
@Configuration(proxyBeanMethods = false)
@org.springframework.boot.context.properties.EnableConfigurationProperties(HostingSetupProperties.class)
@ConditionalOnProperty(name = "agent.hosting-outbound.enabled", havingValue = "true")
public class HostingOutboundConfiguration {
    @Bean cn.jia.agent.mapper.hosting.HostingSetupStore hostingSetupStore(JdbcTemplate jdbc) {
        return new cn.jia.agent.mapper.hosting.HostingSetupStore(jdbc);
    }
    @Bean cn.jia.agent.hosting.HostingSetupService hostingSetupService(cn.jia.agent.mapper.hosting.HostingSetupStore setups,
            HostingOutboundStore store, HostingOutboundExchangeService exchanges, FixedTenantAdmissionPolicy scope, HostingSetupProperties properties) {
        return new cn.jia.agent.hosting.HostingSetupService(setups, store, exchanges, scope, properties);
    }
    @Bean cn.jia.agent.hosting.HostingOutboundProvisioning hostingOutboundProvisioning(cn.jia.agent.mapper.hosting.HostingSetupStore setups,
            HostingOutboundStore store, HostingOutboundExchangeService exchanges, FixedTenantAdmissionPolicy scope, PlatformTransactionManager manager) {
        return new cn.jia.agent.hosting.HostingOutboundProvisioning(setups, store, exchanges, scope, manager);
    }
    @Bean HostingOutboundStore hostingOutboundStore(JdbcTemplate jdbc) { return new HostingOutboundStore(jdbc); }
    @Bean HostingOutboundExchangeService hostingOutboundExchangeService(HostingOutboundStore store,
            FixedTenantAdmissionPolicy scope, PlatformTransactionManager manager, cn.jia.agent.mapper.hosting.HostingSetupStore setups) {
        var service = new HostingOutboundExchangeService(store, scope, manager);
        service.setSetups(setups);
        return service;
    }
}
