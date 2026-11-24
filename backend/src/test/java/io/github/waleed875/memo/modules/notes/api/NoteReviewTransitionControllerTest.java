package io.github.waleed875.memo.modules.notes.api;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataReadOnlyException;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataWriteGate;
import io.github.waleed875.memo.modules.notes.application.NoteReviewConflict;
import io.github.waleed875.memo.modules.notes.application.NoteReviewTransitionRepository;
import io.github.waleed875.memo.platform.security.SessionUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
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
class NoteReviewTransitionControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean SessionUserResolver sessions;
    @MockitoBean NoteReviewTransitionRepository repo;
    @MockitoBean MasterDataWriteGate gate;

    private static final String STAMP = "2026-11-24T10:00:00";

    private UserProfile user(String role) {
        var user = new UserProfile(UUID.randomUUID(), "test", "Test", role);
        when(sessions.resolve(any(HttpServletRequest.class))).thenReturn(Optional.of(user));
        return user;
    }

    @Test void csrfPreventsAllReviewWrites() throws Exception {
        user("REVIEWER");
        mvc.perform(post("/api/notes/{id}/start-review", UUID.randomUUID())
                .contentType("application/json").content("{}"))
            .andExpect(status().isForbidden());
        verifyNoInteractions(repo, gate);
    }

    @Test void anonymousAndViewerAreDenied() throws Exception {
        when(sessions.resolve(any(HttpServletRequest.class))).thenReturn(Optional.empty());
        mvc.perform(post("/api/notes/{id}/start-review", UUID.randomUUID()).with(csrf()))
            .andExpect(status().isUnauthorized());
        user("VIEWER");
        mvc.perform(post("/api/notes/{id}/start-review", UUID.randomUUID()).with(csrf()))
            .andExpect(status().isForbidden());
        verifyNoInteractions(repo, gate);
    }

    @Test void reviewerCanStartAndReturnButCannotAdminRelease() throws Exception {
        UserProfile user = user("REVIEWER");
        UUID id = UUID.randomUUID();
        mvc.perform(post("/api/notes/{id}/start-review", id).with(csrf())
                .contentType("application/json").content("{\"expectedUpdatedAt\":\"" + STAMP + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("بدأت عملية المراجعة."));
        verify(repo).start(id, user.id(), LocalDateTime.parse(STAMP));

        mvc.perform(post("/api/notes/{id}/return", id).with(csrf())
                .contentType("application/json")
                .content("{\"reason\":\"  mistake \",\"expectedUpdatedAt\":\"" + STAMP + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("تمت إعادة المذكرة للتصحيح."));
        verify(repo).returnForCorrection(id, user.id(), "mistake", LocalDateTime.parse(STAMP));

        mvc.perform(post("/api/notes/{id}/release-review", id).with(csrf())
                .contentType("application/json").content("{\"reason\":\"release\"}"))
            .andExpect(status().isForbidden());
        verify(repo, never()).release(any(), any(), any());
    }

    @Test void adminCanReleaseButReasonRequired() throws Exception {
        UserProfile user = user("ADMIN");
        UUID id = UUID.randomUUID();
        mvc.perform(post("/api/notes/{id}/release-review", id).with(csrf())
            .contentType("application/json").content("{\"reason\":\"  \"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/notes/{id}/release-review", id).with(csrf())
            .contentType("application/json").content("{\"reason\":\"  reassignment  \"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("تم تحرير قفل المراجعة."));
        verify(repo).release(id, user.id(), "reassignment");
    }

    @Test void readOnlyAndStaleVersionReturnConflict() throws Exception {
        user("REVIEWER");
        doThrow(new MasterDataReadOnlyException()).when(gate).requireWritable();
        mvc.perform(post("/api/notes/{id}/start-review", UUID.randomUUID()).with(csrf()))
            .andExpect(status().isConflict());
        verifyNoInteractions(repo);
        reset(gate);
        doThrow(new NoteReviewConflict("stale")).when(repo)
            .start(any(), any(), nullable(LocalDateTime.class));
        mvc.perform(post("/api/notes/{id}/start-review", UUID.randomUUID()).with(csrf()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("stale"));
    }

    @Test void approveRequiresCsrfAndReviewerAndUsesSessionActor() throws Exception {
        UUID id = UUID.randomUUID();
        var reviewer = user("REVIEWER");
        mvc.perform(post("/api/notes/{id}/approve", id)).andExpect(status().isForbidden());
        verify(repo, never()).approve(any(), any(), any());

        mvc.perform(post("/api/notes/{id}/approve", id).with(csrf())
            .contentType("application/json").content("{\"expectedUpdatedAt\":\"" + STAMP + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("تم اعتماد المذكرة بنجاح."));
        verify(repo).approve(id, reviewer.id(), LocalDateTime.parse(STAMP));

        user("DATA_ENTRY");
        mvc.perform(post("/api/notes/{id}/approve", id).with(csrf()))
            .andExpect(status().isForbidden());
    }
}
