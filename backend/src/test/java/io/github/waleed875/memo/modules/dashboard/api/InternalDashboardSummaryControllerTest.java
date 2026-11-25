package io.github.waleed875.memo.modules.dashboard.api;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.github.waleed875.memo.modules.dashboard.application.*;
import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.platform.security.SessionUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
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
class InternalDashboardSummaryControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean SessionUserResolver sessions;
    @MockitoBean DashboardSummaryReader reader;

    private void actor(String role, UUID id) {
        when(sessions.resolve(any(HttpServletRequest.class))).thenReturn(
            Optional.of(new UserProfile(id,"test","Test",role)));
    }

    @Test void unauthenticatedOrNonAdminNeverReachReader() throws Exception {
        when(sessions.resolve(any(HttpServletRequest.class))).thenReturn(Optional.empty());
        mvc.perform(get("/api/internal/dashboard/summary")).andExpect(status().isUnauthorized());
        for (String role : new String[]{"DATA_ENTRY","REVIEWER","VIEWER"}) {
            actor(role,UUID.randomUUID());
            mvc.perform(get("/api/internal/dashboard/summary")).andExpect(status().isForbidden());
        }
        verifyNoInteractions(reader);
    }

    @Test void invalidDatesAndStatusReturn400WithoutQuery() throws Exception {
        actor("ADMIN",UUID.randomUUID());
        mvc.perform(get("/api/internal/dashboard/summary")
            .param("from","2026-11-26").param("to","2026-11-24"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/internal/dashboard/summary").param("status","INVALID"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/internal/dashboard/summary").param("from","yesterday"))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(reader);
    }

    @Test void successfulRequestIsScopedAndLegacyFullSnapshotRemainsClosed() throws Exception {
        UUID admin=UUID.randomUUID(), company=UUID.randomUUID();
        actor("ADMIN",admin);
        when(reader.read(eq(admin),any(),any(LocalDate.class))).thenAnswer(call -> {
            DashboardSummaryCriteria criteria=call.getArgument(1);
            return new DashboardCoreSnapshot(
                new DashboardCoreSummary(2,1,2,1,1,1,1,0,
                    0,0,0,0,2,1,1,0,1,6.0,6.0,LocalDate.of(2026,11,24)),
                new DashboardCoreSla(2,1,1,50.0,0),criteria);
        });
        mvc.perform(get("/api/internal/dashboard/summary")
                .param("from","2026-11-20").param("to","2026-11-24")
                .param("dateBasis","ArrivalDate")
                .param("companyId",company.toString()).param("status","approved"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.summary.totalNotes").value(2))
            .andExpect(jsonPath("$.sla.onTimeRate").value(50.0))
            .andExpect(jsonPath("$.effectiveFilters.status").value("APPROVED"))
            .andExpect(jsonPath("$.effectiveFilters.dateBasis").value("ArrivalDate"))
            .andExpect(jsonPath("$.effectiveFilters.companyId").value(company.toString()));
        verify(reader).read(eq(admin),any(DashboardSummaryCriteria.class),any(LocalDate.class));
        mvc.perform(get("/api/dashboard/snapshot")).andExpect(status().isForbidden());
    }
}
