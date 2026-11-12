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
@ConditionalOnProperty(name = "agent.hosting-outbound.enabled", havingValue = "true")
public class HostingOutboundConfiguration {
    @Bean HostingOutboundStore hostingOutboundStore(JdbcTemplate jdbc) { return new HostingOutboundStore(jdbc); }
    @Bean HostingOutboundExchangeService hostingOutboundExchangeService(HostingOutboundStore store,
            FixedTenantAdmissionPolicy scope, PlatformTransactionManager manager) {
        return new HostingOutboundExchangeService(store, scope, manager);
    }
}
