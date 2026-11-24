package io.github.waleed875.memo.modules.notes.api;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.notes.application.NoteSearchReader;
import io.github.waleed875.memo.modules.notes.domain.NoteSearchCriteria;
import io.github.waleed875.memo.modules.notes.domain.PagedNotes;
import io.github.waleed875.memo.platform.security.SessionUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
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
class NoteSearchControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean NoteSearchReader reader;
    @MockitoBean SessionUserResolver sessions;

    @Test void anonymousCannotSearchOrQueryDatabase() throws Exception {
        when(sessions.resolve(any(HttpServletRequest.class))).thenReturn(Optional.empty());
        mvc.perform(get("/api/notes")).andExpect(status().isUnauthorized());
        verifyNoInteractions(reader);
    }

    @Test void dataEntrySearchUsesSessionCreatorAndReturnsOriginalPagingShape() throws Exception {
        UUID actor = UUID.randomUUID(), foreign = UUID.randomUUID();
        when(sessions.resolve(any(HttpServletRequest.class))).thenReturn(Optional.of(
            new UserProfile(actor, "entry", "Data Entry", "DATA_ENTRY")));
        when(reader.search(any(NoteSearchCriteria.class))).thenAnswer(invocation -> {
            NoteSearchCriteria secured = invocation.getArgument(0);
            assertEquals(actor, secured.createdBy());
            assertEquals(1, secured.pageNumber());
            assertEquals(200, secured.pageSize());
            assertEquals("2026", secured.noteNumber());
            return PagedNotes.of(List.of(), 0, secured.pageNumber(), secured.pageSize());
        });
        mvc.perform(get("/api/notes")
                .param("createdBy", foreign.toString())
                .param("noteNumber", "2026")
                .param("pageNumber", "0").param("pageSize", "900"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items").isArray())
            .andExpect(jsonPath("$.totalCount").value(0))
            .andExpect(jsonPath("$.totalPages").value(0))
            .andExpect(jsonPath("$.pageNumber").value(1))
            .andExpect(jsonPath("$.pageSize").value(200));
        verify(reader).search(any(NoteSearchCriteria.class));
    }

    @Test void rejectsInvalidDateOrderAndUnknownStatusBeforeSql() throws Exception {
        when(sessions.resolve(any(HttpServletRequest.class))).thenReturn(Optional.of(
            new UserProfile(UUID.randomUUID(), "admin", "Admin", "ADMIN")));
        mvc.perform(get("/api/notes").param("status", "unknown"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/notes").param("from", "2026-12")
                .param("to", "2026-11"))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(reader);
    }

    @Test void requiresReadOnlyGetAndKeepsReviewRoutesClosed() throws Exception {
        when(sessions.resolve(any(HttpServletRequest.class))).thenReturn(Optional.of(
            new UserProfile(UUID.randomUUID(), "admin", "Admin", "ADMIN")));
        mvc.perform(get("/api/notes/review-queue"))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/notes/incomplete-indicators"))
            .andExpect(status().isForbidden());
    }
}
