package io.github.waleed875.memo.modules.notes.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataWriteGate;
import io.github.waleed875.memo.modules.notes.application.AdminNoteTextEditCommand;
import io.github.waleed875.memo.modules.notes.application.AdminNoteTextEditRepository;
import io.github.waleed875.memo.modules.notes.application.NoteAccessDenied;
import io.github.waleed875.memo.modules.notes.application.NoteDraftEditCommand;
import io.github.waleed875.memo.modules.notes.application.NoteDraftEditConflict;
import io.github.waleed875.memo.modules.notes.application.NoteDraftEditRepository;
import io.github.waleed875.memo.modules.notes.domain.NoteStatus;
import io.github.waleed875.memo.platform.security.SessionUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class NoteEditCommandControllerTest {
    private static final LocalDateTime REVISION = LocalDateTime.parse("2026-11-24T10:20:30");
    @Autowired MockMvc mvc;
    @MockitoBean SessionUserResolver sessions;
    @MockitoBean NoteDraftEditRepository drafts;
    @MockitoBean AdminNoteTextEditRepository adminTexts;
    @MockitoBean MasterDataWriteGate gate;

    private final UUID note = UUID.randomUUID();
    private final UUID field = UUID.randomUUID();

    private String draftJson() {
        return """
          {
            "expectedUpdatedAt":"2026-11-24T10:20:30",
            "notes":"تم التصحيح",
            "saveAsDraft":true,
            "markAllIndicatorsCorrect":false,
            "fieldValues":[{"fieldId":"%s","value":"Correct","inactiveReason":null}]
          }
          """.formatted(field);
    }

    private String adminJson() {
        return """
          {"expectedUpdatedAt":"2026-11-24T10:20:30","notes":"تعديل إداري"}
          """;
    }

    private void user(String role, UUID id) {
        when(sessions.resolve(any(HttpServletRequest.class))).thenReturn(Optional.of(
            new UserProfile(id, "test", "Test", role)));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
        owner(String json) {
        return put("/api/internal/notes/{id}/draft", note).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(json);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
        admin(String json) {
        return put("/api/internal/notes/{id}/admin/text", note).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(json);
    }

    @Test void csrfAndSessionsCannotBeBypassedForEitherRole() throws Exception {
        when(sessions.resolve(any(HttpServletRequest.class))).thenReturn(Optional.empty());
        mvc.perform(owner(draftJson())).andExpect(status().isUnauthorized());
        mvc.perform(admin(adminJson())).andExpect(status().isUnauthorized());
        user("ADMIN", UUID.randomUUID());
        mvc.perform(put("/api/internal/notes/{id}/draft", note)
            .contentType(MediaType.APPLICATION_JSON).content(draftJson()))
            .andExpect(status().isForbidden());
        mvc.perform(put("/api/internal/notes/{id}/admin/text", note)
            .contentType(MediaType.APPLICATION_JSON).content(adminJson()))
            .andExpect(status().isForbidden());
        verifyNoInteractions(drafts, adminTexts);
    }

    @Test void ownerOnlyEditsDraftAndUsesSessionIdentity() throws Exception {
        UUID actor = UUID.randomUUID();
        user("DATA_ENTRY", actor);
        when(drafts.edit(any(NoteDraftEditCommand.class), eq(actor), any(LocalDate.class)))
            .thenReturn(new NoteDraftEditRepository.EditResult(
                note, NoteStatus.DRAFT, REVISION.plusMinutes(1)));
        mvc.perform(owner(draftJson())).andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(note.toString()))
            .andExpect(jsonPath("$.status").value("DRAFT"))
            .andExpect(jsonPath("$.message").value("تم تحديث المذكرة بنجاح."));
        var command = ArgumentCaptor.forClass(NoteDraftEditCommand.class);
        verify(drafts).edit(command.capture(), eq(actor), any(LocalDate.class));
        assertEquals(REVISION, command.getValue().expectedUpdatedAt());
        assertEquals(note, command.getValue().noteId());
        assertEquals(1, command.getValue().fieldValues().size());
        assertNull(command.getValue().attachment());
        assertNull(command.getValue().metadata());
        assertNull(command.getValue().relatedNoteIds());
        assertNull(command.getValue().expectedRelatedNoteIds());
        verifyNoInteractions(adminTexts);
    }

    @Test void administratorTextEditPreservesOtherFieldsAndSupportsExplicitNull() throws Exception {
        UUID actor = UUID.randomUUID();
        user("ADMIN", actor);
        when(adminTexts.updateText(any(AdminNoteTextEditCommand.class), eq(actor)))
            .thenReturn(REVISION.plusSeconds(2));
        mvc.perform(admin(adminJson())).andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(note.toString()))
            .andExpect(jsonPath("$.message").value("تم تحديث نص المذكرة بنجاح."));
        mvc.perform(admin(adminJson().replace("تعديل إداري", "تعديل إداري")))
            .andExpect(status().isOk());
        var command = ArgumentCaptor.forClass(AdminNoteTextEditCommand.class);
        verify(adminTexts, times(2)).updateText(command.capture(), eq(actor));
        assertEquals("تعديل إداري", command.getValue().notes());
        mvc.perform(admin(adminJson().replace("\"تعديل إداري\"", "null")))
            .andExpect(status().isOk());
        verify(adminTexts, times(3)).updateText(command.capture(), eq(actor));
        assertNull(command.getValue().notes());
        verifyNoInteractions(drafts);
    }

    @Test void rolesAreSeparatedEvenWhenAuthenticated() throws Exception {
        user("ADMIN", UUID.randomUUID());
        mvc.perform(owner(draftJson())).andExpect(status().isForbidden());
        user("DATA_ENTRY", UUID.randomUUID());
        mvc.perform(admin(adminJson())).andExpect(status().isForbidden());
        user("VIEWER", UUID.randomUUID());
        mvc.perform(owner(draftJson())).andExpect(status().isForbidden());
        mvc.perform(admin(adminJson())).andExpect(status().isForbidden());
        verifyNoInteractions(drafts, adminTexts);
    }

    @Test void rejectOmissionsAndUnknownFieldsInsteadOfSilentlyClearingValues() throws Exception {
        user("DATA_ENTRY", UUID.randomUUID());
        mvc.perform(owner(draftJson().replace("\"saveAsDraft\":true,", "")))
            .andExpect(status().isBadRequest());
        mvc.perform(owner(draftJson().replace("\"notes\":\"تم التصحيح\",", "")))
            .andExpect(status().isBadRequest());
        mvc.perform(owner(draftJson().replace(
            "\"markAllIndicatorsCorrect\":false",
            "\"markAllIndicatorsCorrect\":false,\"isInvoice\":true")))
            .andExpect(status().isBadRequest());
        mvc.perform(owner(draftJson().replace(
            "\"value\":\"Correct\"", "\"value\":\"InvalidValue\"")))
            .andExpect(status().isBadRequest());
        mvc.perform(owner(draftJson().replace(
            "\"expectedUpdatedAt\":\"2026-11-24T10:20:30\"",
            "\"expectedUpdatedAt\":\"2026-11-24T10:20:30Z\"")))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(drafts);
    }

    @Test void adminTextRejectsWorkflowAndMetadataOverrides() throws Exception {
        user("ADMIN", UUID.randomUUID());
        mvc.perform(admin(adminJson().replace(
            "\"notes\":\"تعديل إداري\"",
            "\"notes\":\"تعديل إداري\",\"status\":\"APPROVED\"")))
            .andExpect(status().isBadRequest());
        mvc.perform(admin(adminJson().replace(
            "\"notes\":\"تعديل إداري\"",
            "\"notes\":\"تعديل إداري\",\"companyId\":\"" + UUID.randomUUID() + "\"")))
            .andExpect(status().isBadRequest());
        mvc.perform(admin(adminJson().replace("\"notes\":\"تعديل إداري\"", "")))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(adminTexts);
    }

    @Test void conflictAndOwnershipFailuresMapToStableHttpCodes() throws Exception {
        UUID actor = UUID.randomUUID();
        user("DATA_ENTRY", actor);
        when(drafts.edit(any(NoteDraftEditCommand.class), eq(actor), any(LocalDate.class)))
            .thenThrow(new NoteDraftEditConflict("stale"));
        mvc.perform(owner(draftJson())).andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("stale"));
        when(drafts.edit(any(NoteDraftEditCommand.class), eq(actor), any(LocalDate.class)))
            .thenThrow(new NoteAccessDenied());
        mvc.perform(owner(draftJson())).andExpect(status().isForbidden());
    }

    @Test void unchangedLegacyWritePathRemainsDenied() throws Exception {
        user("ADMIN", UUID.randomUUID());
        mvc.perform(put("/api/notes/{id}", note).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(adminJson()))
            .andExpect(status().isForbidden());
        verifyNoInteractions(drafts, adminTexts);
    }
}
