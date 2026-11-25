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
class InternalDashboardCompanyMetricsControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean SessionUserResolver sessions;
    @MockitoBean DashboardCompanyMetricsReader reader;

    private void actor(String role,UUID id) {
        when(sessions.resolve(any(HttpServletRequest.class))).thenReturn(
            Optional.of(new UserProfile(id,"test","Tester",role)));
    }

    @Test void sessionAndAdminPermissionRequired() throws Exception {
        when(sessions.resolve(any(HttpServletRequest.class))).thenReturn(Optional.empty());
        mvc.perform(get("/api/internal/dashboard/company-metrics"))
            .andExpect(status().isUnauthorized());
        for(String role:List.of("DATA_ENTRY","REVIEWER","VIEWER")) {
            actor(role,UUID.randomUUID());
            mvc.perform(get("/api/internal/dashboard/company-metrics"))
                .andExpect(status().isForbidden());
        }
        verifyNoInteractions(reader);
    }

    @Test void invalidDatesAndStatusAreRejectedBeforeSql() throws Exception {
        actor("ADMIN",UUID.randomUUID());
        mvc.perform(get("/api/internal/dashboard/company-metrics").param("from","invalid"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/internal/dashboard/company-metrics")
                .param("from","2026-11-25").param("to","2026-11-24"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/internal/dashboard/company-metrics").param("status","INVALID"))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(reader);
    }

    @Test void existingCompanyMetricFieldsAndPriorPeriodAreReturnedAsLegacyShape() throws Exception {
        UUID admin=UUID.randomUUID(),company=UUID.randomUUID();
        actor("ADMIN",admin);
        DashboardCompanyMetrics metrics=new DashboardCompanyMetrics(3, 0, 0, 0, 0, 0, 0, 0, null, null, null, 0, 0, 0, 0, null, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null, null, null, null, null, null, 0, 0, 0, 0, null, null, null, 0, 0, 0, null);
        when(reader.read(eq(admin),any(DashboardSummaryCriteria.class),any(DashboardCompanyPeriod.class)))
            .thenAnswer(call->new DashboardCompanyComparison(
                metrics,metrics,call.getArgument(2),call.getArgument(1)));
        mvc.perform(get("/api/internal/dashboard/company-metrics")
            .param("dateBasis","ArrivalDate")
            .param("companyId",company.toString())
            .param("from","2026-11-11").param("to","2026-11-20"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.companyMetrics.totalNotes").value(3))
            .andExpect(jsonPath("$.companyMetrics.qualityEligibleNoteCount").value(0))
            .andExpect(jsonPath("$.previousCompanyMetrics.totalNotes").value(3))
            .andExpect(jsonPath("$.previousPeriod.from").value("2026-11-01"))
            .andExpect(jsonPath("$.previousPeriod.to").value("2026-11-10"))
            .andExpect(jsonPath("$.effectiveFilters.companyId").value(company.toString()));
        mvc.perform(get("/api/dashboard/snapshot")).andExpect(status().isForbidden());
    }
}
