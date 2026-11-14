package cn.jia.agent.hosting;

import cn.jia.core.entity.JsonResult;
import cn.jia.core.security.SensitiveResponseBodyAdvice;
import cn.jia.core.security.SensitiveResponseProperties;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import static org.junit.jupiter.api.Assertions.*;

class HostingOutboundResponseSanitizationTest {
    private final SensitiveResponseBodyAdvice advice = new SensitiveResponseBodyAdvice(new SensitiveResponseProperties());

    @SuppressWarnings("unchecked")
    private Map<String, Object> response(HostingSetupService.View view) {
        return (Map<String, Object>) advice.beforeBodyWrite(JsonResult.success(view), null,
                MediaType.APPLICATION_JSON, null, null, null);
    }

    @Test
    @SuppressWarnings("unchecked")
    void preservesPublicTargetMetadataForServerAndLocalSetupReceipts() {
        for (String mode : new String[]{"server", "local"}) {
            for (String status : new String[]{"WAITING_INVENTORY", "INVENTORY_READY"}) {
                var grant = new HostingSetupService.Authorization("hca_fixture", "host_fixture", "0",
                        "client_fixture", "owner_fixture", "agt_fixture");
                var data = (Map<String, Object>) response(new HostingSetupService.View("hca_fixture", "huarong",
                        "agt_fixture", mode, status, grant)).get("data");
                assertEquals("hca_fixture", data.get("setupId"));
                assertEquals("huarong", data.get("personaCode"));
                assertEquals(mode, data.get("mode"));
                assertEquals(status, data.get("status"));
                assertEquals(Map.of("authorizationId", "hca_fixture", "hostId", "host_fixture", "tenantId", "0",
                        "clientId", "client_fixture", "ownerJiacn", "owner_fixture", "canonicalAgentId", "agt_fixture"), data.get("authorization"));
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void retainsNullForRevokedSetupsAndStillRedactsRealCredentials() {
        var revoked = (Map<String, Object>) response(new HostingSetupService.View("hca_fixture", "huarong",
                "agt_fixture", "server", "REVOKED", null)).get("data");
        assertNull(revoked.get("authorization"));
        var secrets = (Map<String, Object>) advice.beforeBodyWrite(Map.of("authorization", "Bearer secret-fixture",
                "apiKey", "key-fixture", "accessToken", "token-fixture", "nested", Map.of("authorization", "nested-secret")),
                null, MediaType.APPLICATION_JSON, null, null, null);
        assertNull(secrets.get("authorization"));
        assertNull(secrets.get("apiKey"));
        assertNull(secrets.get("accessToken"));
        assertNull(((Map<?, ?>) secrets.get("nested")).get("authorization"));
    }
}
