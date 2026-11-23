package cn.jia.agent.service.recommendation;

import cn.jia.agent.entity.PersonalWorkspaceViews.VersionView;
import cn.jia.agent.hosting.HostingRentHttp;
import cn.jia.agent.service.PersonalWorkspaceService;
import cn.jia.agent.service.PersonalWorkspaceTaskLinkService;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.HashMap;
import java.util.LinkedHashMap;
import cn.jia.agent.service.PersonalWorkspaceTaskLinkService.LinkView;
import java.util.Objects;
import static cn.jia.agent.entity.AssigneeRecommendation.*;

/** Recommendation-only projection. Authorization and metadata access remain in workspace services. */
@Service
public final class RecommendationMaterials {
    private final PersonalWorkspaceTaskLinkService links;
    private final PersonalWorkspaceService files;

    public RecommendationMaterials(PersonalWorkspaceTaskLinkService links, PersonalWorkspaceService files) {
        this.links = links;
        this.files = files;
    }

    // Observation fields are for stale checks only; never serialize the snapshot into model input.
    record Observation(String relationId, long relationRevision, String fileId, long metadataRevision,
                       String sha256, long byteLength, Material material) { }
    record Material(String name, String originalFilename, String contentMimeType, int version,
                    String role, String fileState, String summary) { }

    List<Observation> observe(HostingRentHttp.Actor actor, String taskId) {
        var linkScope = new PersonalWorkspaceTaskLinkService.Scope(actor.tenantId(), actor.clientId(), actor.ownerJiacn());
        var fileScope = new PersonalWorkspaceService.Scope(actor.tenantId(), actor.clientId(), actor.ownerJiacn());
        var result = new ArrayList<Observation>();
        // Group relation headers, not file details (each can carry 100 unrelated versions).
        // Never share an observation between owners or reuse it during final revalidation.
        var byFile = new LinkedHashMap<String,List<LinkView>>();
        String cursor = null;
        do {
            var page = links.list(linkScope, taskId, cursor);
            for (var link : page.items()) {
                if ("ACTIVE".equals(link.state()) && ("INPUT".equals(link.role()) || "REFERENCE".equals(link.role())))
                    byFile.computeIfAbsent(link.fileId(), unused -> new ArrayList<>()).add(link);
            }
            cursor = page.nextCursor();
        } while (cursor != null);
        for (var selection : byFile.entrySet()) {
            var detail = files.get(fileScope,selection.getKey());
            var versions = new HashMap<Integer,VersionView>();
            for (var version : detail.versions()) versions.put(version.version(), version);
            for (var link : selection.getValue()) {
                var version = versions.computeIfAbsent(link.version(), n -> linkedVersion(fileScope,link.fileId(),n));
                var file = detail.file();
                result.add(new Observation(link.relationId(), link.relationRevision(), link.fileId(),
                        file.metadataRevision(), version.sha256(), version.byteLength(), new Material(file.displayName(),
                        version.originalFilename(), version.contentMimeType(), link.version(), link.role(), file.state(),
                        null))); // No authoritative summary field exists in workspace metadata yet.
            }
        }
        result.sort(Comparator.comparing(Observation::relationId));
        return List.copyOf(result);
    }

    private VersionView linkedVersion(PersonalWorkspaceService.Scope scope, String fileId, int version) {
        String cursor = null;
        do {
            var page = files.versionsPage(scope, fileId, cursor);
            for (var item : page.items()) if (item.version() == version) return item;
            cursor = page.nextCursor();
        } while (cursor != null);
        // Missing referenced version is not permission to substitute latest metadata or omit the attachment.
        throw new Failure(409, "RECOMMENDATION_STALE");
    }

    void verifyUnchanged(HostingRentHttp.Actor actor, String taskId, List<Observation> before) {
        if (!Objects.equals(before, observe(actor, taskId))) throw new Failure(409, "RECOMMENDATION_STALE");
    }
}
