package io.github.waleed875.memo.modules.dashboard.api;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.github.waleed875.memo.modules.dashboard.DashboardUnifiedFixture;
import io.github.waleed875.memo.modules.dashboard.application.DashboardUnifiedService;
import io.github.waleed875.memo.modules.identity.application.UserProfile;
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

@SpringBootTest(properties = "memo.dashboard.snapshot.enabled=true")
@AutoConfigureMockMvc
final class LegacyDashboardSnapshotControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean SessionUserResolver sessions;
    @MockitoBean DashboardUnifiedService unified;

    private void actor(String role) {
        when(sessions.resolve(any(HttpServletRequest.class)))
            .thenReturn(Optional.of(new UserProfile(
                UUID.randomUUID(), "user", "Test User", role)));
    }

    @Test void anonymousCannotReadSnapshotOrQueryDatabase() throws Exception {
        when(sessions.resolve(any(HttpServletRequest.class))).thenReturn(Optional.empty());
        mvc.perform(get("/api/dashboard/snapshot")).andExpect(status().isUnauthorized());
        verifyNoInteractions(unified);
    }

    @Test void nonAdminCannotReadSnapshotOrQueryDatabase() throws Exception {
        actor("VIEWER");
        mvc.perform(get("/api/dashboard/snapshot")).andExpect(status().isForbidden());
        verifyNoInteractions(unified);
    }

    @Test void adminGetsFlatLegacyContractWithoutPreviewEnvelope() throws Exception {
        actor("ADMIN");
        when(unified.read(any(), any(), eq(10)))
            .thenReturn(DashboardUnifiedFixture.build(null));
        mvc.perform(get("/api/dashboard/snapshot").param("dateBasis", "ArrivalDate"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.isHealthy").value(true))
            .andExpect(jsonPath("$.summary.totalNotes").value(3))
            .andExpect(jsonPath("$.views").exists())
            .andExpect(jsonPath("$.snapshot").doesNotExist())
            .andExpect(jsonPath("$.parityGaps").doesNotExist())
            .andExpect(jsonPath("$.readyForPublicApi").doesNotExist());
        verify(unified).read(any(), any(), eq(10));
    }

    @Test void malformedDatesFailWithoutDatabaseQueries() throws Exception {
        actor("ADMIN");
        mvc.perform(get("/api/dashboard/snapshot").param("from", "not-a-date"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/dashboard/snapshot").param("from", "2026-11-26").param("to", "2026-11-25"))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(unified);
    }
}
