package io.github.waleed875.memo.modules.notes.api;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataReadOnlyException;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataWriteGate;
import io.github.waleed875.memo.modules.notes.application.NoteDeleteConflict;
import io.github.waleed875.memo.modules.notes.application.NoteDeleteRepository;
import io.github.waleed875.memo.platform.security.SessionUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.UUID;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class NoteDeleteControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean SessionUserResolver sessions;
    @MockitoBean NoteDeleteRepository repository;
    @MockitoBean MasterDataWriteGate gate;
    static final String STAMP = "2026-11-24T11:00:00";

    void user(String role) {
        when(sessions.resolve(any(HttpServletRequest.class))).thenReturn(Optional.of(
            new UserProfile(UUID.randomUUID(), "test", "Tester", role)));
    }

    @Test void csrfPreventsAllDeleteEffects() throws Exception {
        user("ADMIN");
        mvc.perform(delete("/api/notes/{id}", UUID.randomUUID())
                .param("expectedUpdatedAt", STAMP))
            .andExpect(status().isForbidden());
        verifyNoInteractions(repository, gate);
    }

    @Test void anonymousAndViewerAreDenied() throws Exception {
        when(sessions.resolve(any(HttpServletRequest.class))).thenReturn(Optional.empty());
        mvc.perform(delete("/api/notes/{id}", UUID.randomUUID()).with(csrf())
                .param("expectedUpdatedAt", STAMP))
            .andExpect(status().isUnauthorized());
        user("VIEWER");
        mvc.perform(delete("/api/notes/{id}", UUID.randomUUID()).with(csrf())
                .param("expectedUpdatedAt", STAMP))
            .andExpect(status().isForbidden());
        verifyNoInteractions(repository, gate);
    }

    @Test void readonlyAndVersionConflictAreReportedWithoutSideEffects() throws Exception {
        user("ADMIN");
        doThrow(new MasterDataReadOnlyException()).when(gate).requireWritable();
        mvc.perform(delete("/api/notes/{id}", UUID.randomUUID()).with(csrf())
                .param("expectedUpdatedAt", STAMP))
            .andExpect(status().isConflict());
        verifyNoInteractions(repository);
        reset(gate);
        doThrow(new NoteDeleteConflict("تعارض تعديل"))
            .when(repository).delete(any(UUID.class), any(UUID.class), any(LocalDateTime.class));
        mvc.perform(delete("/api/notes/{id}", UUID.randomUUID()).with(csrf())
                .param("expectedUpdatedAt", STAMP))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("تعارض تعديل"));
    }

    @Test void adminDeleteMatchesOriginalMessageAndRequiresTimestamp() throws Exception {
        user("ADMIN");
        UUID id = UUID.randomUUID();
        mvc.perform(delete("/api/notes/{id}", id).with(csrf()))
            .andExpect(status().isBadRequest());
        mvc.perform(delete("/api/notes/{id}", id).with(csrf())
                .param("expectedUpdatedAt", STAMP))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("تم حذف المذكرة بنجاح."));
        verify(repository).delete(eq(id), any(UUID.class), eq(LocalDateTime.parse(STAMP)));
    }
}
