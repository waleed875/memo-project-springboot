package io.github.waleed875.memo.modules.notes.api;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.notes.application.NoteReviewQueueReader;
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
class NoteReviewQueueControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean SessionUserResolver sessions;
    @MockitoBean NoteReviewQueueReader queue;

    @Test void unauthenticatedDoesNotQuery() throws Exception {
        when(sessions.resolve(any(HttpServletRequest.class))).thenReturn(Optional.empty());
        mvc.perform(get("/api/notes/review-queue")).andExpect(status().isUnauthorized());
        verifyNoInteractions(queue);
    }

    @Test void dataEntryCannotViewReviewQueue() throws Exception {
        when(sessions.resolve(any(HttpServletRequest.class))).thenReturn(Optional.of(
            new UserProfile(UUID.randomUUID(), "entry", "Entry", "DATA_ENTRY")));
        mvc.perform(get("/api/notes/review-queue")).andExpect(status().isForbidden());
        verifyNoInteractions(queue);
    }

    @Test void reviewerIdentityComesFromSessionNotQueryParameters() throws Exception {
        UUID reviewer = UUID.randomUUID();
        when(sessions.resolve(any(HttpServletRequest.class))).thenReturn(Optional.of(
            new UserProfile(reviewer, "reviewer", "Reviewer", "REVIEWER")));
        when(queue.findForReviewer(eq(reviewer))).thenReturn(List.of());
        mvc.perform(get("/api/notes/review-queue").param("reviewerId", UUID.randomUUID().toString()))
            .andExpect(status().isOk()).andExpect(content().json("[]"));
        verify(queue).findForReviewer(reviewer);
    }

    @Test void noNewMutatingEndpointIsOpened() throws Exception {
        mvc.perform(post("/api/notes/review-queue")
            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
            .andExpect(status().isForbidden());
    }
}

