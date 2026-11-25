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
class InternalDashboardPerformanceControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean SessionUserResolver sessions;
    @MockitoBean DashboardPerformanceReader reader;
    private void actor(String role,UUID id) {
        when(sessions.resolve(any(HttpServletRequest.class))).thenReturn(
            Optional.of(new UserProfile(id,"test","Tester",role)));
    }
    @Test void anonymousAndViewerCannotReadPerformance() throws Exception {
        when(sessions.resolve(any(HttpServletRequest.class))).thenReturn(Optional.empty());
        mvc.perform(get("/api/internal/dashboard/performance")).andExpect(status().isUnauthorized());
        for(String role: List.of("VIEWER","REVIEWER","DATA_ENTRY")) {
            actor(role,UUID.randomUUID());
            mvc.perform(get("/api/internal/dashboard/performance")).andExpect(status().isForbidden());
        }
        verifyNoInteractions(reader);
    }
    @Test void invalidFiltersAreRejectedWithoutDatabaseRead() throws Exception {
        actor("ADMIN",UUID.randomUUID());
        mvc.perform(get("/api/internal/dashboard/performance").param("from","invalid"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/internal/dashboard/performance")
                .param("from","2026-11-26").param("to","2026-11-25"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/internal/dashboard/performance").param("status","UNKNOWN"))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(reader);
    }
    @Test void responseMatchesLegacyPerformanceFieldsWhileFullSnapshotRemainsClosed() throws Exception {
        UUID admin=UUID.randomUUID(),dimension=UUID.randomUUID();
        actor("ADMIN",admin);
        when(reader.read(eq(admin),any(DashboardSummaryCriteria.class))).thenAnswer(call->
            new DashboardPerformanceBreakdown(List.of(new DashboardPerformanceItem(
                dimension,"Company",3,2,1,1,3,1,1,2,50.0,50.0)),
                List.of(),List.of(),call.getArgument(1)));
        mvc.perform(get("/api/internal/dashboard/performance").param("dateBasis","ArrivalDate"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.timelinessByCompany[0].dimensionId").value(dimension.toString()))
            .andExpect(jsonPath("$.timelinessByCompany[0].totalNotes").value(3))
            .andExpect(jsonPath("$.timelinessByCompany[0].eligibleCount").value(2))
            .andExpect(jsonPath("$.timelinessByCompany[0].qualityEligibleCount").value(2))
            .andExpect(jsonPath("$.timelinessByCompany[0].accuracyRate").value(50.0))
            .andExpect(jsonPath("$.effectiveFilters.dateBasis").value("ArrivalDate"));
        mvc.perform(get("/api/dashboard/snapshot")).andExpect(status().isForbidden());
    }
}
