package io.github.waleed875.memo.modules.notes.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataWriteGate;
import io.github.waleed875.memo.modules.notes.application.*;
import io.github.waleed875.memo.platform.security.SessionUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class InternalNoteAttachmentControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean SessionUserResolver sessions;
    @MockitoBean MasterDataWriteGate gate;
    @MockitoBean AdminNoteAttachmentUploadRepository uploadRepository;
    @MockitoBean AdminNoteAttachmentDeleteRepository deleteRepository;
    @MockitoBean NoteAttachmentDownloadReader reader;

    private final UUID note = UUID.randomUUID();
    private final UUID attachment = UUID.randomUUID();
    private static final String REV = "2026-11-24T10:20:30";

    private void actor(String role, UUID id) {
        when(sessions.resolve(any(HttpServletRequest.class))).thenReturn(Optional.of(
            new UserProfile(id, "test", "Test", role)));
    }

    private String download() {
        return "/api/internal/notes/" + note + "/attachments/" + attachment;
    }

    @Test void downloadRequiresSessionAndDoesNotExposeMissingFiles() throws Exception {
        when(sessions.resolve(any(HttpServletRequest.class))).thenReturn(Optional.empty());
        mvc.perform(get(download())).andExpect(status().isUnauthorized());
        verifyNoInteractions(reader);
        actor("DATA_ENTRY", UUID.randomUUID());
        mvc.perform(get(download())).andExpect(status().isNotFound());
        verify(reader).find(eq(note), eq(attachment), any(UUID.class));
    }

    @Test void authorizedDownloadForcesSafeHeadersAndAttachmentDisposition() throws Exception {
        UUID user = UUID.randomUUID();
        actor("DATA_ENTRY", user);
        byte[] bytes = "%PDF-1.4\n%%EOF".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(reader.find(note, attachment, user)).thenReturn(Optional.of(
            new DownloadedNoteAttachment("report.pdf", bytes)));
        mvc.perform(get(download()))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(header().string("Content-Type", "application/octet-stream"))
            .andExpect(header().string("Content-Disposition",
                org.hamcrest.Matchers.containsString("attachment")))
            .andExpect(content().bytes(bytes));
        assertEquals("report_pdf", InternalNoteAttachmentController.safeName("report/pdf"));
    }

    @Test void anonymousAndMissingCsrfCannotChangeAttachments() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
            "file", "test.pdf", "application/pdf", "%PDF-1.4\n%%EOF".getBytes());
        when(sessions.resolve(any(HttpServletRequest.class))).thenReturn(Optional.empty());
        mvc.perform(multipart("/api/internal/notes/{id}/admin/attachments", note)
                .file(file).with(csrf()).param("expectedUpdatedAt", REV))
            .andExpect(status().isUnauthorized());
        actor("ADMIN", UUID.randomUUID());
        mvc.perform(multipart("/api/internal/notes/{id}/admin/attachments", note)
                .file(file).param("expectedUpdatedAt", REV))
            .andExpect(status().isForbidden());
        mvc.perform(delete("/api/internal/notes/{id}/admin/attachments/{aid}", note, attachment)
                .param("expectedUpdatedAt", REV))
            .andExpect(status().isForbidden());
        verifyNoInteractions(uploadRepository, deleteRepository);
    }

    @Test void adminUploadCallsExistingTransactionalPortAndReturnsRevision() throws Exception {
        UUID user = UUID.randomUUID();
        actor("ADMIN", user);
        byte[] bytes = "%PDF-1.4\n%%EOF".getBytes();
        MockMultipartFile file = new MockMultipartFile(
            "file", "report.pdf", "application/pdf", bytes);
        when(uploadRepository.upload(eq(note), any(LocalDateTime.class), any(), eq(user)))
            .thenReturn(new AdminNoteAttachmentUploadRepository.UploadResult(
                attachment, LocalDateTime.parse(REV).plusSeconds(1), "application/pdf", bytes.length));
        mvc.perform(multipart("/api/internal/notes/{id}/admin/attachments", note)
                .file(file).with(csrf()).param("expectedUpdatedAt", REV))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(attachment.toString()));
        var fileCaptor = ArgumentCaptor.forClass(
            io.github.waleed875.memo.modules.notes.domain.NoteAttachment.class);
        verify(uploadRepository).upload(eq(note), eq(LocalDateTime.parse(REV)),
            fileCaptor.capture(), eq(user));
        assertArrayEquals(bytes, fileCaptor.getValue().bytes());
    }

    @Test void viewerCannotWriteAndInvalidPdfCannotReachRepository() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
            "file", "report.pdf", "application/pdf", "%PDF-1.4\n%%EOF".getBytes());
        actor("VIEWER", UUID.randomUUID());
        mvc.perform(multipart("/api/internal/notes/{id}/admin/attachments", note)
                .file(file).with(csrf()).param("expectedUpdatedAt", REV))
            .andExpect(status().isForbidden());
        actor("ADMIN", UUID.randomUUID());
        MockMultipartFile invalid = new MockMultipartFile(
            "file", "report.pdf", "application/pdf", "not-a-pdf".getBytes());
        mvc.perform(multipart("/api/internal/notes/{id}/admin/attachments", note)
                .file(invalid).with(csrf()).param("expectedUpdatedAt", REV))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(uploadRepository);
    }

    @Test void deleteUsesBothIdentifiersAndMapsMissingTo404() throws Exception {
        UUID user = UUID.randomUUID();
        actor("ADMIN", user);
        when(deleteRepository.delete(note, attachment, LocalDateTime.parse(REV), user))
            .thenReturn(new AdminNoteAttachmentDeleteRepository.DeleteResult(
                attachment, "removed.pdf", null, LocalDateTime.parse(REV).plusSeconds(1)));
        mvc.perform(delete("/api/internal/notes/{id}/admin/attachments/{aid}", note, attachment)
                .param("expectedUpdatedAt", REV).with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(attachment.toString()));
        verify(deleteRepository).delete(note, attachment, LocalDateTime.parse(REV), user);
        when(deleteRepository.delete(note, attachment, LocalDateTime.parse(REV), user))
            .thenThrow(new NoteAttachmentNotFound());
        mvc.perform(delete("/api/internal/notes/{id}/admin/attachments/{aid}", note, attachment)
                .param("expectedUpdatedAt", REV).with(csrf()))
            .andExpect(status().isNotFound());
    }

    @Test void originalAttachmentPathsRemainClosed() throws Exception {
        actor("ADMIN", UUID.randomUUID());
        mvc.perform(get("/api/notes/{id}/attachments/{aid}", note, attachment))
            .andExpect(status().isForbidden());
        mvc.perform(multipart("/api/notes/{id}/attachments", note)
                .file(new MockMultipartFile("file", "report.pdf", "application/pdf",
                    "%PDF-1.4\n%%EOF".getBytes())).with(csrf()))
            .andExpect(status().isForbidden());
    }
}
