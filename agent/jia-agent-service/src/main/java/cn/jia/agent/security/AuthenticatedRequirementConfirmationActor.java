package cn.jia.agent.security;

import cn.jia.agent.service.AgentTaskRequirementConfirmationActor;
import cn.jia.core.context.EsContextHolder;
import jakarta.inject.Named;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Adapts the authenticated JWT and request context into a framework-free actor. */
@Named
public final class AuthenticatedRequirementConfirmationActor implements AgentTaskRequirementConfirmationActor {
    @Override public Owner authenticatedOwner() {
        var principal = SecurityContextHolder.getContext().getAuthentication();
        var context = EsContextHolder.getContext();
        if (!(principal instanceof JwtAuthenticationToken jwt) || !jwt.isAuthenticated()
                || context == null
                || !(jwt.getToken().getClaims().get("jiacn") instanceof String owner)
                || !(jwt.getToken().getClaims().get("client_id") instanceof String client)
                || !owner.equals(context.getJiacn()) || !client.equals(context.getClientId()))
            throw new IllegalArgumentException("Authenticated task owner required for re-confirmation");
        return new Owner(owner, client);
    }
}
