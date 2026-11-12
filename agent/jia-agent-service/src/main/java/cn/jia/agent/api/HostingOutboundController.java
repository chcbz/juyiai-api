package cn.jia.agent.api;

import cn.jia.agent.hosting.HostingOutboundExchangeService;
import cn.jia.agent.hosting.HostingRentErrors;
import cn.jia.agent.security.HostingOutboundCredentialFilter;
import cn.jia.core.entity.JsonRequest;
import cn.jia.core.entity.JsonResult;
import cn.jia.core.filter.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;
import java.util.Set;

@RestController
@ConditionalOnProperty(name = "agent.hosting-outbound.enabled", havingValue = "true")
public final class HostingOutboundController {
    private final HostingOutboundExchangeService service;
    public HostingOutboundController(HostingOutboundExchangeService service) { this.service = service; }
    @PostMapping(value = HostingOutboundExchangeService.PATH, consumes = "application/json", produces = "application/json")
    public ResponseEntity<JsonResult<HostingOutboundExchangeService.Reply>> exchange(
            @RequestBody JsonRequest<Map<String, Object>> envelope, HttpServletRequest servlet) {
        var data = envelope == null ? null : envelope.data();
        if (data == null || !data.keySet().containsAll(Set.of("authorizationId", "hostId", "runtimeInstanceId", "result")))
            throw new IllegalArgumentException("Invalid hosting exchange");
        Map<String, Object> result = null;
        if (data.get("result") != null) {
            if (!(data.get("result") instanceof Map<?, ?> map) || map.keySet().stream().anyMatch(key -> !(key instanceof String)))
                throw new IllegalArgumentException("Invalid hosting result");
            @SuppressWarnings("unchecked") var typed = (Map<String, Object>) map; result = typed;
        }
        var request = new HostingOutboundExchangeService.Request(text(data, "authorizationId"), text(data, "hostId"), text(data, "runtimeInstanceId"), result);
        var response = JsonResult.success(service.exchange(HostingOutboundCredentialFilter.credential(servlet), request, System.currentTimeMillis()));
        response.setRequestId(envelope.requestId());
        return ResponseEntity.ok().header("Cache-Control", "private, no-store").header(RequestIdFilter.HEADER_NAME, envelope.requestId()).body(response);
    }
    private static String text(Map<String, Object> map, String field) {
        if (!(map.get(field) instanceof String value)) throw new IllegalArgumentException("Invalid hosting exchange"); return value;
    }
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<JsonResult<Void>> failure(RuntimeException error, HttpServletRequest request) {
        var response = HostingRentErrors.response(error); response.getBody().setRequestId(RequestIdFilter.current(request));
        return ResponseEntity.status(response.getStatusCode()).header("Cache-Control", "private, no-store")
                .header(RequestIdFilter.HEADER_NAME, response.getBody().getRequestId()).body(response.getBody());
    }
}
