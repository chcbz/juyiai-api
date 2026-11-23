package cn.jia.agent.service.recommendation;

import cn.jia.agent.exception.PersonalWorkspaceException;
import cn.jia.agent.entity.PersonalWorkspaceViews.*;
import cn.jia.agent.hosting.HostingRentHttp;
import cn.jia.agent.service.PersonalWorkspaceService;
import cn.jia.agent.service.PersonalWorkspaceTaskLinkService;
import cn.jia.agent.service.PersonalWorkspaceTaskLinkService.LinkView;
import cn.jia.agent.service.PersonalWorkspaceTaskLinkService.LinkListView;
import cn.jia.agent.service.PersonalWorkspaceTaskLinkService.Scope;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.stream.IntStream;
import static cn.jia.agent.entity.AssigneeRecommendation.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class RecommendationMaterialsTest {
    private static final HostingRentHttp.Actor ACTOR = new HostingRentHttp.Actor("user", "0", "client", "owner");
    private final PersonalWorkspaceTaskLinkService links = mock(PersonalWorkspaceTaskLinkService.class);
    private final PersonalWorkspaceService files = mock(PersonalWorkspaceService.class);
    private final RecommendationMaterials subject = new RecommendationMaterials(links, files);
    private static final Scope LINK_SCOPE = new Scope("0", "client", "owner");
    private static final PersonalWorkspaceService.Scope FILE_SCOPE = new PersonalWorkspaceService.Scope("0", "client", "owner");
    private static LinkView link(String id, String role, String state) {
        return new LinkView(id, "task", "file", 1, role, state, 1, 123);
    }
    private static VersionView version(int n, String mime) {
        return new VersionView("file", n, "original.png", mime, 30, "sha" + n, 123);
    }
    private static PersonalWorkspaceService.SelectedVersions detail(String name, long revision, List<VersionView> versions) {
        return new PersonalWorkspaceService.SelectedVersions(new FileView("file", "UPLOAD", "USER", name, "document", "ACTIVE",
                revision, 102, 123, null), versions);
    }
    private void single() {
        when(links.list(LINK_SCOPE, "task", null)).thenReturn(new LinkListView(List.of(link("r", "INPUT", "ACTIVE")), null));
        when(files.selectedVersions(FILE_SCOPE, "file", List.of(1))).thenReturn(detail("图片", 1, List.of(version(1, "image/png"))));
    }
    @Test void readsOnlyMetadataAndUsesLinkedMimeNotCurrentFileFamilyOrLatestVersion() {
        single();
        var rows = subject.observe(ACTOR, "task");
        assertEquals("image/png", rows.getFirst().material().contentMimeType());
        assertEquals("图片", rows.getFirst().material().name());
        assertEquals(1, rows.getFirst().material().version());
        assertNull(rows.getFirst().material().summary());
        verify(links).list(LINK_SCOPE, "task", null); verify(files).selectedVersions(FILE_SCOPE, "file", List.of(1));
        verifyNoMoreInteractions(links, files); // no body, preview, download or write path
    }
    @Test void followsRelationPagesButRequestsOnlyExactLinkedVersion() {
        var first = IntStream.range(0, 50).mapToObj(i -> link("r" + i, "OUTPUT", "ACTIVE")).toList();
        when(links.list(LINK_SCOPE, "task", null)).thenReturn(new LinkListView(first, "next"));
        when(links.list(LINK_SCOPE, "task", "next")).thenReturn(new LinkListView(List.of(link("input", "REFERENCE", "ACTIVE")), null));
        when(files.selectedVersions(FILE_SCOPE, "file", List.of(1))).thenReturn(detail("旧图片", 1, List.of(version(1, "image/png"))));
        var rows = subject.observe(ACTOR, "task");
        assertEquals(1, rows.size()); assertEquals("image/png", rows.getFirst().material().contentMimeType());
        verify(files).selectedVersions(FILE_SCOPE, "file", List.of(1)); verifyNoMoreInteractions(files);
    }
    @Test void sameFileInTwoRolesReadsOncePerObservationAndKeepsExactBytesOutsideText() {
        single(); when(links.list(LINK_SCOPE,"task",null)).thenReturn(new LinkListView(List.of(
                link("a","INPUT","ACTIVE"),link("b","REFERENCE","ACTIVE")),null));
        var before = subject.observe(ACTOR,"task"); assertEquals(2,before.size()); assertEquals(30,before.getFirst().byteLength());
        verify(files).selectedVersions(FILE_SCOPE, "file", List.of(1)); subject.verifyUnchanged(ACTOR,"task",before);
        verify(files,times(2)).selectedVersions(FILE_SCOPE, "file", List.of(1));
    }
    @Test void excludesDetachedAndOutputAndAllowsEmptyMaterials() {
        when(links.list(LINK_SCOPE, "task", null)).thenReturn(new LinkListView(List.of(link("a", "INPUT", "DETACHED"), link("b", "OUTPUT", "ACTIVE")), null));
        assertTrue(subject.observe(ACTOR, "task").isEmpty()); verifyNoInteractions(files);
    }
    @Test void missingExactVersionFailsRatherThanInventingOrIgnoringMaterial() {
        single(); when(files.selectedVersions(FILE_SCOPE, "file", List.of(1))).thenReturn(detail("file", 1, List.of(version(102, "text/plain"))));
        assertEquals(409, assertThrows(Failure.class, () -> subject.observe(ACTOR, "task")).status());
    }
    @Test void renameDetachAndVersionChangesInvalidateObservation() {
        single(); var before = subject.observe(ACTOR, "task");
        when(files.selectedVersions(FILE_SCOPE, "file", List.of(1))).thenReturn(detail("renamed", 2, List.of(version(1, "image/png"))));
        assertEquals(409, assertThrows(Failure.class, () -> subject.verifyUnchanged(ACTOR, "task", before)).status());
        when(links.list(LINK_SCOPE, "task", null)).thenReturn(new LinkListView(List.of(), null));
        assertEquals(409, assertThrows(Failure.class, () -> subject.verifyUnchanged(ACTOR, "task", before)).status());
        when(links.list(LINK_SCOPE, "task", null)).thenReturn(new LinkListView(List.of(new LinkView("r", "task", "file", 102, "INPUT", "ACTIVE", 2, 123)), null));
        when(files.selectedVersions(FILE_SCOPE, "file", List.of(102))).thenReturn(detail("图片", 1, List.of(version(102, "application/pdf"))));
        assertEquals(409, assertThrows(Failure.class, () -> subject.verifyUnchanged(ACTOR, "task", before)).status());
    }
    @Test void laterLinkPageAccessLossDoesNotReturnPartialInputsOrMaskAnOutage() {
        when(links.list(LINK_SCOPE, "task", null)).thenReturn(new LinkListView(List.of(link("r", "INPUT", "ACTIVE")), "next"));
        var outage = new PersonalWorkspaceTaskLinkService.Failure(PersonalWorkspaceTaskLinkService.Reason.UNAVAILABLE);
        when(links.list(LINK_SCOPE, "task", "next")).thenThrow(new PersonalWorkspaceTaskLinkService.Failure(
                PersonalWorkspaceTaskLinkService.Reason.NOT_FOUND), outage);
        assertEquals(PersonalWorkspaceException.Reason.NOT_FOUND,
                assertThrows(PersonalWorkspaceException.class, () -> subject.observe(ACTOR, "task")).getReason());
        assertSame(outage, assertThrows(PersonalWorkspaceTaskLinkService.Failure.class, () -> subject.observe(ACTOR, "task")));
        verifyNoInteractions(files);
    }
    @Test void metadataFailureIsNotTreatedAsNoAttachments() {
        single(); when(files.selectedVersions(FILE_SCOPE, "file", List.of(1))).thenThrow(new IllegalStateException("unavailable"));
        assertThrows(IllegalStateException.class, () -> subject.observe(ACTOR, "task"));
    }
    @Test void passesEveryFileTypeIncludingUnknownMimeAndNoExtensionWithoutClassification() {
        single();
        for (String filename : List.of("result.png", "report.pdf", "data.csv", "recording.wav", "archive.unknown", "无后缀资料")) {
            var v = new VersionView("file", 1, filename, "application/octet-stream", 30, "sha1", 123);
            when(files.selectedVersions(FILE_SCOPE, "file", List.of(1))).thenReturn(detail(filename, 1, List.of(v)));
            var rows = subject.observe(ACTOR, "task");
            assertEquals(1, rows.size());
            assertEquals(filename, rows.getFirst().material().name());
            assertEquals(filename, rows.getFirst().material().originalFilename());
            assertEquals("application/octet-stream", rows.getFirst().material().contentMimeType());
        }
        verify(files, times(6)).selectedVersions(FILE_SCOPE, "file", List.of(1));
        verifyNoMoreInteractions(files);
    }

}
