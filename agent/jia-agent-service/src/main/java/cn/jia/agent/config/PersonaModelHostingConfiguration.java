package cn.jia.agent.config;

import cn.jia.agent.mapper.hosting.PersonaModelHostingStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Registers a database reader only. Schema and data are maintained explicitly, never at boot. */
@Configuration(proxyBeanMethods = false)
public class PersonaModelHostingConfiguration {
    @Bean PersonaModelHostingStore personaModelHostingStore(JdbcTemplate jdbc) {
        return new PersonaModelHostingStore(jdbc);
    }
}
