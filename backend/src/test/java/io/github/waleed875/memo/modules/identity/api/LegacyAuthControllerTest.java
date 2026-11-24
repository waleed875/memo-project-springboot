package io.github.waleed875.memo.modules.identity.api;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.github.waleed875.memo.modules.identity.infrastructure.BcryptPasswordVerifier;
import io.github.waleed875.memo.modules.identity.application.UserAccountLookup;
import io.github.waleed875.memo.modules.identity.domain.UserAccount;
import io.github.waleed875.memo.sharedkernel.identity.MemoRole;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class LegacyAuthControllerTest {
    private static final UUID ID = UUID.fromString("cc5f1230-a257-4b64-94c2-23765c009818");
    private static final LocalDateTime UPDATED = LocalDateTime.parse("2026-11-01T12:00:00");

    @Autowired MockMvc mvc;
    @MockitoBean UserAccountLookup accounts;
    @MockitoBean BcryptPasswordVerifier verifier;

    private void validLoginAccount() {
        UserAccount user = new UserAccount(ID, "admin", "System Admin",
            "stored-hash", MemoRole.ADMIN, false, UPDATED);
        when(accounts.findActiveByUsername("admin")).thenReturn(Optional.of(user));
        when(accounts.findActiveById(ID)).thenReturn(Optional.of(user));
        when(verifier.matches("valid-password", "stored-hash")).thenReturn(true);
    }

    @Test void csrfTokenIsExposedToExistingFrontend() throws Exception {
        mvc.perform(get("/api/admin/csrf"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.token", not(emptyOrNullString())));
    }

    @Test void loginFailsWhenCsrfIsAbsent() throws Exception {
        mvc.perform(post("/api/admin/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"valid-password\"}"))
            .andExpect(status().isForbidden());
        verifyNoInteractions(accounts);
    }

    @Test void successfulAdminLoginAndSessionMatchLegacyShape() throws Exception {
        validLoginAccount();
        MvcResult result = mvc.perform(post("/api/admin/login").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"valid-password\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.authenticated").value(true))
            .andExpect(jsonPath("$.role").value("ADMIN"))
            .andExpect(jsonPath("$.username").value("admin"))
            .andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        mvc.perform(get("/api/admin/session").session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.authenticated").value(true))
            .andExpect(jsonPath("$.username").value("admin"));
        mvc.perform(post("/api/admin/logout").session(session).with(csrf()))
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/admin/session").session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.authenticated").value(false));
    }

    @Test void invalidPasswordReturns401() throws Exception {
        mvc.perform(post("/api/auth/login").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
            .andExpect(status().isUnauthorized());
    }

    @Test void unfinishedBusinessApisRemainDenied() throws Exception {
        mvc.perform(get("/api/notes")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/admin/apply-migrations").with(csrf()))
            .andExpect(status().isForbidden());
    }

    @Test void failedReLoginClearsPreviousIdentity() throws Exception {
        validLoginAccount();
        MvcResult authenticated = mvc.perform(post("/api/admin/login").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"valid-password\"}"))
            .andExpect(status().isOk()).andReturn();
        MockHttpSession session = (MockHttpSession) authenticated.getRequest().getSession(false);
        mvc.perform(post("/api/admin/login").session(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"incorrect\"}"))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/session").session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.authenticated").value(false));
    }

    @Test void changePasswordRequiresCsrfAndHidesOtherBusinessRoutes() throws Exception {
        mvc.perform(post("/api/auth/change-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"currentPassword\":\"old\",\"newPassword\":\"new-password\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/users")).andExpect(status().isForbidden());
    }
}
