package io.github.waleed875.memo.modules.dashboard.api;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.github.waleed875.memo.modules.dashboard.application.*;
import io.github.waleed875.memo.modules.identity.application.UserProfile;
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
class InternalDashboardDistributionsControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean SessionUserResolver sessions;
    @MockitoBean DashboardDistributionsReader reader;

    private void actor(String role,UUID id) {
        when(sessions.resolve(any(HttpServletRequest.class))).thenReturn(
            Optional.of(new UserProfile(id,"test","Test",role)));
    }

    @Test void sessionAndAdminOnlyBoundary() throws Exception {
        when(sessions.resolve(any(HttpServletRequest.class))).thenReturn(Optional.empty());
        mvc.perform(get("/api/internal/dashboard/distributions"))
            .andExpect(status().isUnauthorized());
        for (String role : List.of("DATA_ENTRY","REVIEWER","VIEWER")) {
            actor(role,UUID.randomUUID());
            mvc.perform(get("/api/internal/dashboard/distributions"))
                .andExpect(status().isForbidden());
        }
        verifyNoInteractions(reader);
    }

    @Test void invalidFiltersFailWithoutDatabaseRead() throws Exception {
        actor("ADMIN",UUID.randomUUID());
        mvc.perform(get("/api/internal/dashboard/distributions").param("from","invalid"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/internal/dashboard/distributions").param("status","X"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/internal/dashboard/distributions")
                .param("from","2026-11-25").param("to","2026-11-24"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/internal/dashboard/distributions")
                .param("from","2000-01-01").param("to","2026-11-25"))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(reader);
    }

    @Test void adminGetsExplicitChartsWithoutOpeningFullSnapshot() throws Exception {
        UUID admin=UUID.randomUUID();
        actor("ADMIN",admin);
        when(reader.read(eq(admin),any())).thenAnswer(call->{
            DashboardSummaryCriteria f=call.getArgument(1);
            return new DashboardDistributions(
                List.of(new DashboardDistributions.MonthlyActivity("2026-11","نوفمبر 2026",2)),
                List.of(new DashboardDistributions.StatusDistribution(
                    "APPROVED","معتمدة",2)),
                List.of(),List.of(),List.of(),List.of(),f);
        });
        mvc.perform(get("/api/internal/dashboard/distributions")
                .param("dateBasis","ArrivalDate"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.monthlyActivity[0].month").value("2026-11"))
            .andExpect(jsonPath("$.statusDistribution[0].statusLabel").value("معتمدة"))
            .andExpect(jsonPath("$.effectiveFilters.dateBasis").value("ArrivalDate"));
        mvc.perform(get("/api/dashboard/snapshot")).andExpect(status().isForbidden());
        verify(reader).read(eq(admin),any(DashboardSummaryCriteria.class));
    }
}
