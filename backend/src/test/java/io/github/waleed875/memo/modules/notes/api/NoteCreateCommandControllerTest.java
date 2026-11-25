package io.github.waleed875.memo.modules.notes.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataWriteGate;
import io.github.waleed875.memo.modules.notes.application.NoteCreateCommand;
import io.github.waleed875.memo.modules.notes.application.NoteCreator;
import io.github.waleed875.memo.modules.notes.application.NoteDuplicateClassification;
import io.github.waleed875.memo.platform.security.SessionUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
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
class NoteCreateCommandControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean SessionUserResolver sessions;
    @MockitoBean NoteCreator creator;
    @MockitoBean MasterDataWriteGate gate;

    private final UUID operation = UUID.randomUUID();
    private final UUID note = UUID.randomUUID();
    private final UUID company = UUID.randomUUID();
    private final UUID type = UUID.randomUUID();
    private final UUID field = UUID.randomUUID();

    private String payload() {
        return """
            {
              "operationId":"%s", "noteId":"%s",
              "candidate": {
                "noteNumber":"12345", "companyId":"%s", "noteTypeId":"%s",
                "arrivalAt":"2026-11-24", "saveAsDraft":true,
                "markAllIndicatorsCorrect":false,
                "fieldValues":[{"fieldId":"%s","value":"Correct"}]
              },
              "notes":"اختبار", "relatedNoteIds":[]
            }
            """.formatted(operation, note, company, type, field);
    }

    private void user(String role, UUID id) {
        when(sessions.resolve(any(HttpServletRequest.class))).thenReturn(Optional.of(
            new UserProfile(id, "test", "Test", role)));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder submit(String json) {
        return post("/api/internal/notes").with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(json);
    }

    @Test void anonymousCsrfAndViewerAreDeniedBeforeDatabaseWrite() throws Exception {
        when(sessions.resolve(any(HttpServletRequest.class))).thenReturn(Optional.empty());
        mvc.perform(submit(payload())).andExpect(status().isUnauthorized());
        user("ADMIN", UUID.randomUUID());
        mvc.perform(post("/api/internal/notes")
            .contentType(MediaType.APPLICATION_JSON).content(payload()))
            .andExpect(status().isForbidden());
        user("VIEWER", UUID.randomUUID());
        mvc.perform(submit(payload())).andExpect(status().isForbidden());
        verifyNoInteractions(creator);
    }

    @Test void validDraftPersistsUsingSessionIdentityAndExplicitOperationId() throws Exception {
        UUID actor = UUID.randomUUID();
        user("DATA_ENTRY", actor);
        when(creator.create(any(NoteCreateCommand.class), eq(actor), any(LocalDate.class)))
            .thenAnswer(call -> ((NoteCreateCommand) call.getArgument(0)).noteId());
        mvc.perform(submit(payload())).andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(note.toString()))
            .andExpect(jsonPath("$.message").value("تم حفظ المذكرة بنجاح."));
        var capture = ArgumentCaptor.forClass(NoteCreateCommand.class);
        verify(creator).create(capture.capture(), eq(actor), any(LocalDate.class));
        assertEquals(operation, capture.getValue().operationId());
        assertEquals(note, capture.getValue().noteId());
        assertEquals(company, capture.getValue().candidate().companyId());
        assertTrue(capture.getValue().candidate().saveAsDraft());
        assertEquals(1, capture.getValue().candidate().fieldValues().size());
        assertNull(capture.getValue().attachment());
    }

    @Test void rejectLegacyFlagsAndUnknownNestedFieldsWithoutWriting() throws Exception {
        user("ADMIN", UUID.randomUUID());
        mvc.perform(submit(payload().replace(
            "\"relatedNoteIds\":[]", "\"relatedNoteIds\":[],\"attachmentBase64\":\"AAAA\"")))
            .andExpect(status().isBadRequest());
        mvc.perform(submit(payload().replace(
            "\"saveAsDraft\":true", "\"saveAsDraft\":true,\"isInvoice\":true")))
            .andExpect(status().isBadRequest());
        mvc.perform(submit(payload().replace(
            "\"value\":\"Correct\"", "\"value\":\"Correct\",\"inactiveReason\":\"x\"")))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(creator);
    }

    @Test void rejectInvalidTypesMissingIdAndNoCreateDecisionOnCreateRoute() throws Exception {
        user("ADMIN", UUID.randomUUID());
        mvc.perform(submit(payload().replace(operation.toString(), "not-a-uuid")))
            .andExpect(status().isBadRequest());
        mvc.perform(submit(payload().replace("\"value\":\"Correct\"", "\"value\":\"Unknown\"")))
            .andExpect(status().isBadRequest());
        mvc.perform(submit(payload().replace(
            "\"relatedNoteIds\":[]",
            "\"relatedNoteIds\":[],\"duplicateResolution\":{\"classification\":\"COMPANY_RESEND\",\"matchedNoteId\":\"" + UUID.randomUUID() + "\"}")))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(creator);
    }

    @Test void explicitCompanyDuplicateIsMappedForAtomicVerificationByRepository() throws Exception {
        UUID actor = UUID.randomUUID(), matched = UUID.randomUUID();
        user("ADMIN", actor);
        when(creator.create(any(NoteCreateCommand.class), eq(actor), any(LocalDate.class)))
            .thenReturn(note);
        String request = payload().replace(
            "\"relatedNoteIds\":[]",
            "\"relatedNoteIds\":[],\"duplicateResolution\":{\"classification\":\"COMPANY_DUPLICATE_NUMBER\",\"matchedNoteId\":\"" + matched + "\"}");
        mvc.perform(submit(request)).andExpect(status().isOk());
        var command = ArgumentCaptor.forClass(NoteCreateCommand.class);
        verify(creator).create(command.capture(), eq(actor), any(LocalDate.class));
        assertEquals(NoteDuplicateClassification.COMPANY_DUPLICATE_NUMBER,
            command.getValue().resolution().classification());
        assertEquals(matched, command.getValue().resolution().matchedNoteId());
    }
}
