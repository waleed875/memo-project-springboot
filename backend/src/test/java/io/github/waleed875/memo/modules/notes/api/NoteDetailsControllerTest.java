package io.github.waleed875.memo.modules.notes.api;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.notes.application.NoteDetailsReader;
import io.github.waleed875.memo.modules.notes.application.NoteOwnership;
import io.github.waleed875.memo.platform.security.SessionUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class NoteDetailsControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean SessionUserResolver sessions;
    @MockitoBean NoteDetailsReader reader;

    @Test void anonymousCannotReadDetailsEvenIfNoteExists() throws Exception {
        when(sessions.resolve(any(HttpServletRequest.class))).thenReturn(Optional.empty());
        mvc.perform(get("/api/notes/{id}", UUID.randomUUID()))
            .andExpect(status().isUnauthorized());
        verifyNoInteractions(reader);
    }

    @Test void dataEntryCannotReadOtherUserNote() throws Exception {
        UUID current = UUID.randomUUID(), owner = UUID.randomUUID(), noteId = UUID.randomUUID();
        when(sessions.resolve(any(HttpServletRequest.class))).thenReturn(Optional.of(
            new UserProfile(current, "entry", "Data Entry", "DATA_ENTRY")));
        when(reader.ownership(noteId)).thenReturn(Optional.of(new NoteOwnership(owner)));
        mvc.perform(get("/api/notes/{id}", noteId))
            .andExpect(status().isForbidden());
        verify(reader, never()).find(any(), any(), anyBoolean());
    }

    @Test void missingNoteReturnsOriginal404Payload() throws Exception {
        when(sessions.resolve(any(HttpServletRequest.class))).thenReturn(Optional.of(
            new UserProfile(UUID.randomUUID(), "viewer", "Viewer", "VIEWER")));
        mvc.perform(get("/api/notes/{id}", UUID.randomUUID()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.message").value("المذكرة غير موجودة."));
    }

    @Test void malformedGuidIsNotFoundAndOtherApisRemainClosed() throws Exception {
        when(sessions.resolve(any(HttpServletRequest.class))).thenReturn(Optional.of(
            new UserProfile(UUID.randomUUID(), "viewer", "Viewer", "VIEWER")));
        mvc.perform(get("/api/notes/not-a-guid")).andExpect(status().isNotFound());
        mvc.perform(get("/api/notes")).andExpect(status().isForbidden());
        mvc.perform(post("/api/notes").with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/api/notes/{id}/start-review", UUID.randomUUID()).with(csrf()))
            .andExpect(status().isForbidden());
    }
}
