package cn.jia.agent.api;
import cn.jia.agent.skill.SkillMarketplaceException;
import cn.jia.agent.hosting.HostingRentHttp;
import cn.jia.agent.security.AgentRuntimeAuthenticationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
@RestController
public final class SkillPackageController {
    private final cn.jia.agent.skill.SkillManagementService installs;
    public SkillPackageController(cn.jia.agent.skill.SkillManagementService installs) { this.installs=installs; }
    @GetMapping("/internal/agent/skill-installations/{installationId}/package")
    public void download(@PathVariable String installationId,Authentication auth,HttpServletRequest request,HttpServletResponse response) throws IOException {
        HostingRentHttp.noQuery(request);cn.jia.agent.skill.SkillManagementCodec.path(installationId);
        if(auth==null||!auth.isAuthenticated())throw new SkillMarketplaceException(401,"UNAUTHENTICATED");
        installs.writePackage(AgentRuntimeAuthenticationService.requireProof(auth),installationId,length->{
            response.setHeader(HttpHeaders.CACHE_CONTROL,"private, no-store");
            response.setHeader("X-Content-Type-Options","nosniff");
            response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
            response.setContentLengthLong(length);
            return response.getOutputStream();
        });
    }
}
