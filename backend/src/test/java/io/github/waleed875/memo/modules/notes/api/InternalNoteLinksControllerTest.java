package io.github.waleed875.memo.modules.notes.api;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataWriteGate;
import io.github.waleed875.memo.modules.notes.application.AdminNoteLinkSyncCommand;
import io.github.waleed875.memo.modules.notes.application.AdminNoteLinkSyncRepository;
import io.github.waleed875.memo.modules.notes.application.NoteDraftEditConflict;
import io.github.waleed875.memo.platform.security.SessionUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.List;
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
class InternalNoteLinksControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean SessionUserResolver sessions;
    @MockitoBean MasterDataWriteGate gate;
    @MockitoBean AdminNoteLinkSyncRepository repository;

    private final UUID note = UUID.randomUUID();
    private final UUID linked = UUID.randomUUID();

    private String payload() {
        return """
            {"expectedUpdatedAt":"2026-11-24T10:20:30",
             "expectedRelatedNoteIds":[],
             "relatedNoteIds":["%s"]}
            """.formatted(linked);
    }

    private void actor(String role, UUID id) {
        when(sessions.resolve(any(HttpServletRequest.class))).thenReturn(Optional.of(
            new UserProfile(id, "test", "Test", role)));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder submit(String json) {
        return put("/api/internal/notes/{id}/admin/links", note)
            .contentType(MediaType.APPLICATION_JSON).with(csrf()).content(json);
    }

    @Test void sessionCsrfAndRoleAreMandatory() throws Exception {
        when(sessions.resolve(any(HttpServletRequest.class))).thenReturn(Optional.empty());
        mvc.perform(submit(payload())).andExpect(status().isUnauthorized());
        actor("ADMIN", UUID.randomUUID());
        mvc.perform(put("/api/internal/notes/{id}/admin/links", note)
            .contentType(MediaType.APPLICATION_JSON).content(payload()))
            .andExpect(status().isForbidden());
        for (String role : List.of("DATA_ENTRY", "REVIEWER", "VIEWER")) {
            actor(role, UUID.randomUUID());
            mvc.perform(submit(payload())).andExpect(status().isForbidden());
        }
        verifyNoInteractions(repository);
    }

    @Test void adminPassesExplicitOldAndNewSetsToTransactionalPort() throws Exception {
        UUID admin = UUID.randomUUID();
        actor("ADMIN", admin);
        when(repository.sync(any(AdminNoteLinkSyncCommand.class), eq(admin)))
            .thenReturn(new AdminNoteLinkSyncRepository.SyncResult(
                note, List.of(linked), 1, 0, LocalDateTime.parse("2026-11-24T10:20:31")));
        mvc.perform(submit(payload())).andExpect(status().isOk())
            .andExpect(jsonPath("$.added").value(1))
            .andExpect(jsonPath("$.relatedNoteIds[0]").value(linked.toString()));
        var capture = ArgumentCaptor.forClass(AdminNoteLinkSyncCommand.class);
        verify(repository).sync(capture.capture(), eq(admin));
        org.junit.jupiter.api.Assertions.assertTrue(capture.getValue().expectedRelatedNoteIds().isEmpty());
        org.junit.jupiter.api.Assertions.assertEquals(List.of(linked), capture.getValue().relatedNoteIds());
    }

    @Test void incompleteOrInvalidCommandsDoNotReachRepository() throws Exception {
        actor("ADMIN", UUID.randomUUID());
        mvc.perform(submit(payload().replace("\"expectedRelatedNoteIds\":[],", "")))
            .andExpect(status().isBadRequest());
        mvc.perform(submit(payload().replace(
            "\"relatedNoteIds\":[", "\"relatedNoteIds\":[\"bad-uuid\",")))
            .andExpect(status().isBadRequest());
        mvc.perform(submit(payload().replace(
            "\"relatedNoteIds\":[", "\"relatedNoteIds\":[\"" + note + "\",")))
            .andExpect(status().isBadRequest());
        mvc.perform(submit(payload().replace(
            "\"expectedRelatedNoteIds\":[]",
            "\"expectedRelatedNoteIds\":[],\"status\":\"APPROVED\"")))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(repository);
    }

    @Test void staleSetMapsTo409() throws Exception {
        UUID admin = UUID.randomUUID();
        actor("ADMIN", admin);
        when(repository.sync(any(), eq(admin))).thenThrow(new NoteDraftEditConflict("stale"));
        mvc.perform(submit(payload())).andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("stale"));
    }
}
