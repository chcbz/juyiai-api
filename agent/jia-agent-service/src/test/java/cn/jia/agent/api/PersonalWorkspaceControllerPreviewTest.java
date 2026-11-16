package cn.jia.agent.api;

import cn.jia.agent.entity.PersonalWorkspaceViews;
import cn.jia.agent.service.PersonalWorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PersonalWorkspaceControllerPreviewTest {
    private static final String CLIENT = "client-a";
    private static final String OWNER = "owner-a";
    private static final String FILE = "file-a";

    private PersonalWorkspaceService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(PersonalWorkspaceService.class);
        mvc = MockMvcBuilders.standaloneSetup(new PersonalWorkspaceController(service)).build();
    }

    @Test
    void trustedTenantIsNotOverriddenByUploadParameters() throws Exception {
        var token = Jwt.withTokenValue("fixture").header("alg", "none")
                .claim("jiacn", OWNER).claim("client_id", CLIENT).claim("tenant_id", "1").build();
        mvc.perform(multipart("/agent/personal-workspace/files")
                .file(new MockMultipartFile("file", "note.txt", "text/plain", new byte[]{1}))
                .param("tenant_id", "0").param("tenantId", "0")
                .header("Idempotency-Key", "gray-upload")
                .principal(new JwtAuthenticationToken(token, List.of())))
                .andExpect(status().isCreated());
        verify(service).create(org.mockito.ArgumentMatchers.eq(
                new PersonalWorkspaceService.Scope("1", CLIENT, OWNER)), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void invalidOrConflictingTenantClaimsNeverReachTheService() throws Exception {
        for (String tenant : new String[]{"", " 1", "1\n"}) {
            var token = Jwt.withTokenValue("fixture").header("alg", "none")
                    .claim("jiacn", OWNER).claim("client_id", CLIENT).claim("tenant_id", tenant).build();
            mvc.perform(get("/agent/personal-workspace/files/" + FILE)
                    .principal(new JwtAuthenticationToken(token, List.of())))
                    .andExpect(status().isNotFound());
        }
        var conflicting = Jwt.withTokenValue("fixture").header("alg", "none")
                .claim("jiacn", OWNER).claim("client_id", CLIENT)
                .claim("tenant_id", "1").claim("tenantId", "0").build();
        mvc.perform(get("/agent/personal-workspace/files/" + FILE)
                .principal(new JwtAuthenticationToken(conflicting, List.of())))
                .andExpect(status().isNotFound());
        org.mockito.Mockito.verifyNoInteractions(service);
    }

    @Test
    void arbitraryUploadMimeIsPreservedAndUnknownMissingMimeUsesOctetStream() throws Exception {
        String[][] cases = {{"README", null, "application/octet-stream"},
                {"data.custom", "application/octet-stream", "application/octet-stream"},
                {"page.html", "text/html", "text/html"}, {"renamed.mp3", "video/mp4", "video/mp4"}};
        for (int i = 0; i < cases.length; i++) {
            String[] item = cases[i];
            mvc.perform(multipart("/agent/personal-workspace/files")
                    .file(new MockMultipartFile("file", item[0], item[1], new byte[]{1, 2, 3}))
                    .header("Idempotency-Key", "arbitrary-" + i).principal(jwt()))
                    .andExpect(status().isCreated());
            verify(service).create(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.argThat(
                    command -> item[0].equals(command.originalFilename()) && item[2].equals(command.contentMimeType())));
        }
    }

    @Test
    void uploadInfersTextMimeWhenBrowserMultipartMetadataIsMissing() throws Exception {
        PersonalWorkspaceService.Scope scope = new PersonalWorkspaceService.Scope("0", CLIENT, OWNER);
        MockMultipartFile text = new MockMultipartFile("file", "notes.txt", null,
                "portable text".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        mvc.perform(multipart("/agent/personal-workspace/files")
                        .file(text)
                        .header("Idempotency-Key", "text-upload-key")
                        .principal(jwt()))
                .andExpect(status().isCreated());

        verify(service).create(org.mockito.ArgumentMatchers.eq(scope),
                org.mockito.ArgumentMatchers.argThat(command -> command != null
                        && "notes.txt".equals(command.originalFilename())
                        && "text/plain".equals(command.contentMimeType())
                        && "text-upload-key".equals(command.idempotency().key())));
    }

    @Test
    void metadataKeepsOriginalIdentityWithoutClaimingBrowserPreviewSupport() throws Exception {
        var scope = new PersonalWorkspaceService.Scope("0", CLIENT, OWNER);
        var version = new PersonalWorkspaceViews.VersionView(FILE, 3, "budget.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", 42, "a".repeat(64), 1);
        var file = new PersonalWorkspaceViews.FileView(FILE, "UPLOAD", "USER_UPLOAD", "Budget",
                "SPREADSHEET", "ACTIVE", 1, 3, 1,
                new PersonalWorkspaceViews.Capabilities("AVAILABLE", "AVAILABLE", "UNVERIFIED", "AVAILABLE"));
        when(service.get(scope, FILE)).thenReturn(new PersonalWorkspaceViews.FileDetailView(
                file, version, List.of(version), List.of(), List.of()));
        mvc.perform(get("/agent/personal-workspace/files/{fileId}", FILE).principal(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.latestVersion.version").value("3"))
                .andExpect(jsonPath("$.data.latestVersion.byteLength").value("42"))
                .andExpect(jsonPath("$.data.latestVersion.sha256").value("a".repeat(64)))
                .andExpect(jsonPath("$.data.latestVersion.contentMimeType").value(version.contentMimeType()))
                .andExpect(jsonPath("$.data.latestVersion.previewState").doesNotExist())
                .andExpect(jsonPath("$.data.versions[0].previewState").doesNotExist())
                .andExpect(jsonPath("$.data.file.capabilities.preview").doesNotExist())
                .andExpect(jsonPath("$.data.file.capabilities.read").value("AVAILABLE"));
    }

    @Test
    void retiredPreviewRoutesHaveNoMapping() throws Exception {
        for (String suffix : List.of("preview", "preview?view=parts", "preview/parts/content")) {
            mvc.perform(get("/agent/personal-workspace/files/file-a/versions/1/" + suffix)
                    .principal(jwt())).andExpect(status().isNotFound());
        }
        org.mockito.Mockito.verifyNoInteractions(service);
    }

    @Test
    void originalContentPreservesBytesMimeAndClosesStream() throws Exception {
        var scope = new PersonalWorkspaceService.Scope("0", CLIENT, OWNER);
        for (String mime : List.of("text/html", "text/plain",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) {
            byte[] original = ("unparsed-original-" + mime).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            var closed = new java.util.concurrent.atomic.AtomicBoolean();
            var stream = new java.io.ByteArrayInputStream(original) {
                @Override public void close() { closed.set(true); }
            };
            when(service.download(scope, FILE, 1)).thenReturn(new PersonalWorkspaceService.Download(
                    "original", mime, original.length, stream));
            mvc.perform(get("/agent/personal-workspace/files/{fileId}/versions/1/content", FILE).principal(jwt()))
                    .andExpect(status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().bytes(original))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().contentType(mime))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control", "private, no-store"));
            org.junit.jupiter.api.Assertions.assertTrue(closed.get());
        }
    }

    private static JwtAuthenticationToken jwt() {
        Jwt token = Jwt.withTokenValue("token").claim("tenant_id", "0")
                .header("alg", "none")
                .claim("jiacn", OWNER)
                .claim("client_id", CLIENT)
                .claim("sub", "subject-a")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        return new JwtAuthenticationToken(token, List.of());
    }
}
