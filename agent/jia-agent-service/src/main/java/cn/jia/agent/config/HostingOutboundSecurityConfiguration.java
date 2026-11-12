package cn.jia.agent.config;

import cn.jia.agent.security.HostingOutboundCredentialFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.savedrequest.NullRequestCache;

@Configuration(proxyBeanMethods = false)
public class HostingOutboundSecurityConfiguration {
    @Bean @Order(-1)
    public SecurityFilterChain hostingOutboundSecurityFilterChain(HttpSecurity http) throws Exception {
        return http.securityMatcher(HostingOutboundCredentialFilter::selects)
                .addFilterBefore(new HostingOutboundCredentialFilter(), UsernamePasswordAuthenticationFilter.class)
                // Like native enrollment, authentication is transactional at the sole controller/service.
                .authorizeHttpRequests(a -> a.anyRequest().permitAll())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(c -> c.securityContextRepository(new NullSecurityContextRepository()))
                .requestCache(c -> c.requestCache(new NullRequestCache())).build();
    }
}
