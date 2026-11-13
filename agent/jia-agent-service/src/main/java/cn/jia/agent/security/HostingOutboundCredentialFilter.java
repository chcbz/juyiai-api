package cn.jia.agent.security;

import cn.jia.agent.hosting.HostingOutboundExchangeService;
import cn.jia.core.entity.JsonResult;
import cn.jia.core.filter.RequestIdFilter;
import cn.jia.core.util.JsonUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.Collections;
import java.util.Locale;

/** Closed native credential lane. Syntax only here; persistent authentication and revocation
 * are checked once, under the grant lock in exchange(), together with delivery/result commit. */
public final class HostingOutboundCredentialFilter extends OncePerRequestFilter {
    private static final String ATTRIBUTE = HostingOutboundCredentialFilter.class.getName() + ".credential";
    private record Credential(String token) { @Override public String toString() { return "RuntimeHosting[REDACTED]"; } }
    public static boolean selects(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return path.startsWith("/agent/runtime/v1/hosting/") || Collections.list(request.getHeaders("Authorization")).stream()
                .anyMatch(value -> value.toLowerCase(Locale.ROOT).startsWith("runtimehosting"));
    }
    public static String credential(HttpServletRequest request) {
        if (!(request.getAttribute(ATTRIBUTE) instanceof Credential value)) throw new IllegalArgumentException("Native hosting credential required");
        return value.token();
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Cache-Control", "private, no-store");
        var values = Collections.list(request.getHeaders("Authorization"));
        String path = request.getRequestURI().substring(request.getContextPath().length());
        boolean exchange="POST".equals(request.getMethod()) && HostingOutboundExchangeService.PATH.equals(path)
                && request.getQueryString()==null && request.getParameterMap().isEmpty();
        boolean directory="GET".equals(request.getMethod()) && HostingOutboundExchangeService.DIRECTORY_PATH.equals(path)
                && request.getContentLengthLong()<=0 && request.getHeader("Transfer-Encoding")==null
                && request.getParameterMap().entrySet().stream().allMatch(e -> java.util.Set.of("cursor","pageSize").contains(e.getKey()) && e.getValue().length==1);
        if ((!exchange && !directory)
                || request.getHeader("Origin") != null || request.getHeader("X-API-Key") != null
                || Collections.list(request.getHeaderNames()).stream().anyMatch(name -> name.toLowerCase(Locale.ROOT).startsWith("x-agent-"))
                || values.size() != 1 || !values.getFirst().matches("RuntimeHosting rth1_[0-9a-f]{64}")) {
            String requestId = RequestIdFilter.current(request);
            var body = JsonResult.failure("HOSTING_OUTBOUND_REJECTED", "Native hosting credential required"); body.setRequestId(requestId);
            response.setStatus(403); response.setContentType("application/json"); response.setCharacterEncoding("UTF-8");
            response.setHeader(RequestIdFilter.HEADER_NAME, requestId); response.getWriter().write(JsonUtil.toJson(body)); return;
        }
        request.setAttribute(ATTRIBUTE, new Credential(values.getFirst().substring("RuntimeHosting ".length())));
        try { chain.doFilter(request, response); } finally { request.removeAttribute(ATTRIBUTE); }
    }
}
