package io.github.waleed875.memo.modules.dashboard.api;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.nullValue;

import io.github.waleed875.memo.modules.dashboard.DashboardUnifiedFixture;
import io.github.waleed875.memo.modules.dashboard.application.*;
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

@SpringBootTest
@AutoConfigureMockMvc
class InternalDashboardSnapshotPreviewControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean SessionUserResolver sessions;
    @MockitoBean DashboardUnifiedService service;

    @Test void previewKeepsLegacyNamesAndMetadataOutsideFlatSnapshot() throws Exception {
        UUID actor=UUID.randomUUID(),company=UUID.randomUUID();
        when(sessions.resolve(any(HttpServletRequest.class))).thenReturn(
            Optional.of(new UserProfile(actor,"admin","Admin","ADMIN")));
        when(service.read(any(),any(),eq(10)))
            .thenReturn(DashboardUnifiedFixture.build(company));

        mvc.perform(get("/api/internal/dashboard/snapshot-preview")
            .param("companyId",company.toString()).param("dateBasis","ArrivalDate"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.readyForPublicApi").value(false))
            .andExpect(jsonPath("$.parityGaps[0]").value("browser parity pending"))
            .andExpect(jsonPath("$.snapshot.summary.totalNotes").value(3))
            .andExpect(jsonPath("$.snapshot.serverTime").value("2026-11-25T12:00:00"))
            .andExpect(jsonPath("$.snapshot.views.context.period.dateBasis").value("ArrivalDate"))
            .andExpect(jsonPath("$.snapshot.views.context.scope.companyId")
                .value(company.toString()))
            .andExpect(jsonPath("$.snapshot.views.companySummary.company.id")
                .value(company.toString()))
            .andExpect(jsonPath("$.snapshot.topErrorFields[0].totalFieldCount").value(0))
            .andExpect(jsonPath("$.snapshot.topErrorFields[0].fieldAccuracyRate").value(nullValue()))
            .andExpect(jsonPath("$.snapshot.isHealthy").value(true))
            .andExpect(jsonPath("$.snapshot.errorMessage").value((Object)null));
        mvc.perform(get("/api/dashboard/snapshot")).andExpect(status().isForbidden());
    }

    @Test void unauthenticatedAndMalformedRequestsCannotBuildPreview() throws Exception {
        when(sessions.resolve(any(HttpServletRequest.class))).thenReturn(Optional.empty());
        mvc.perform(get("/api/internal/dashboard/snapshot-preview"))
            .andExpect(status().isUnauthorized());
        when(sessions.resolve(any(HttpServletRequest.class))).thenReturn(
            Optional.of(new UserProfile(UUID.randomUUID(),"admin","Admin","ADMIN")));
        mvc.perform(get("/api/internal/dashboard/snapshot-preview")
            .param("from","not-a-date")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/internal/dashboard/snapshot-preview")
            .param("status","NOT_A_STATE")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
