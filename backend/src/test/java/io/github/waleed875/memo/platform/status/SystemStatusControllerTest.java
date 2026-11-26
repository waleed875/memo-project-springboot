package io.github.waleed875.memo.platform.status;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.github.waleed875.memo.modules.identity.application.UserAccountLookup;
import io.github.waleed875.memo.modules.identity.domain.UserAccount;
import io.github.waleed875.memo.modules.identity.infrastructure.BcryptPasswordVerifier;
import io.github.waleed875.memo.sharedkernel.identity.MemoRole;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class SystemStatusControllerTest {
    private static final UUID ID = UUID.fromString("ccccaaaa-1111-4444-8888-555555555555");
    private static final LocalDateTime STAMP = LocalDateTime.parse("2026-11-01T12:00:00");

    @Autowired MockMvc mvc;
    @MockitoBean UserAccountLookup accounts;
    @MockitoBean BcryptPasswordVerifier verifier;
    @MockitoBean JdbcTemplate jdbc;

    private MockHttpSession sessionFor(MemoRole role) throws Exception {
        UserAccount user = new UserAccount(ID, "tester", "Test User", "stored",
            role, false, STAMP);
        when(accounts.findActiveByUsername("tester")).thenReturn(Optional.of(user));
        when(accounts.findActiveById(ID)).thenReturn(Optional.of(user));
        when(verifier.matches("valid-password", "stored")).thenReturn(true);

        MvcResult login = mvc.perform(post("/api/admin/login").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"tester\",\"password\":\"valid-password\"}"))
            .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) login.getRequest().getSession(false);
    }

    @Test void publicHealthIsMinimalAndDoesNotAccessDatabase() throws Exception {
        mvc.perform(get("/api/system/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.backend").value("UP"))
            .andExpect(jsonPath("$.database").doesNotExist())
            .andExpect(jsonPath("$.password").doesNotExist());
        verify(jdbc, never()).queryForObject("select 1", Integer.class);
    }

    @Test void anonymousCannotReadDatabaseDetails() throws Exception {
        mvc.perform(get("/api/admin/system-status"))
            .andExpect(status().isUnauthorized());
        verify(jdbc, never()).queryForObject("select 1", Integer.class);
    }

    @Test void nonAdminCannotReadDatabaseDetails() throws Exception {
        var session = sessionFor(MemoRole.VIEWER);
        mvc.perform(get("/api/admin/system-status").session(session))
            .andExpect(status().isForbidden());
        verifyNoInteractions(jdbc);
    }

    @Test void adminGetsOnlySafeVersionAndStatusMetadata() throws Exception {
        var session = sessionFor(MemoRole.ADMIN);
        when(jdbc.queryForObject("select 1", Integer.class)).thenReturn(1);
        when(jdbc.queryForObject(
            "select database_version from system.database_settings where singleton = true",
            String.class)).thenReturn("070");

        mvc.perform(get("/api/admin/system-status").session(session))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
            .andExpect(jsonPath("$.backend").value("UP"))
            .andExpect(jsonPath("$.database").value("CONNECTED"))
            .andExpect(jsonPath("$.databaseVersion").value("070"))
            .andExpect(jsonPath("$.migrations").value("CURRENT"))
            .andExpect(jsonPath("$.password").doesNotExist())
            .andExpect(jsonPath("$.host").doesNotExist());
    }

    @Test void adminGetsSafeUnknownStateWhenMigrationMetadataMissing() throws Exception {
        var session = sessionFor(MemoRole.ADMIN);
        when(jdbc.queryForObject("select 1", Integer.class)).thenReturn(1);
        when(jdbc.queryForObject(
            "select database_version from system.database_settings where singleton = true",
            String.class)).thenThrow(new DataAccessResourceFailureException("secret connection info"));

        mvc.perform(get("/api/admin/system-status").session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.database").value("CONNECTED"))
            .andExpect(jsonPath("$.migrations").value("UNKNOWN"))
            .andExpect(jsonPath("$.databaseVersion").doesNotExist())
            .andExpect(content().string(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.containsString("secret connection info"))));
    }
}
