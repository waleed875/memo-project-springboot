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
class InternalDashboardQualityControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean SessionUserResolver sessions;
    @MockitoBean DashboardQualityReader reader;

    void actor(String role,UUID id) {
        when(sessions.resolve(any(HttpServletRequest.class))).thenReturn(
            Optional.of(new UserProfile(id,"test","Tester",role)));
    }

    @Test void sessionAndAdminRoleRequired() throws Exception {
        when(sessions.resolve(any(HttpServletRequest.class))).thenReturn(Optional.empty());
        mvc.perform(get("/api/internal/dashboard/quality"))
            .andExpect(status().isUnauthorized());
        for (String role : List.of("DATA_ENTRY","REVIEWER","VIEWER")) {
            actor(role,UUID.randomUUID());
            mvc.perform(get("/api/internal/dashboard/quality"))
                .andExpect(status().isForbidden());
        }
        verifyNoInteractions(reader);
    }

    @Test void invalidSampleDateAndStatusDoNotReachReader() throws Exception {
        actor("ADMIN",UUID.randomUUID());
        mvc.perform(get("/api/internal/dashboard/quality").param("minSample","0"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/internal/dashboard/quality").param("minSample","10001"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/internal/dashboard/quality").param("status","UNKNOWN"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/internal/dashboard/quality").param("from","bad-date"))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(reader);
    }

    @Test void adminJsonContainsMeasuredQualityAndPublicSnapshotRemainsClosed() throws Exception {
        UUID admin=UUID.randomUUID();
        actor("ADMIN",admin);
        when(reader.read(eq(admin),any(DashboardSummaryCriteria.class),eq(1)))
            .thenAnswer(call -> new DashboardQualitySnapshot(
                new DashboardQualitySnapshot.FieldQualitySummary(3,2,1,1,66.67),
                List.of(),List.of(),List.of(),
                List.of(new DashboardQualitySnapshot.TopErrorField(
                    UUID.randomUUID(),"حقل","حقل",3,1,33.33)),
                List.of(new DashboardQualitySnapshot.IndicatorState(
                    0,"لم تتم المراجعة",1)),call.getArgument(1),1));
        mvc.perform(get("/api/internal/dashboard/quality")
                .param("minSample","1").param("dateBasis","ArrivalDate"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.fieldQuality.accuracyRate").value(66.67))
            .andExpect(jsonPath("$.indicatorStateDistribution[0].code").value(0))
            .andExpect(jsonPath("$.topErrorFields[0].errorRate").value(33.33))
            .andExpect(jsonPath("$.effectiveFilters.dateBasis").value("ArrivalDate"));
        mvc.perform(get("/api/dashboard/snapshot")).andExpect(status().isForbidden());
        verify(reader).read(eq(admin),any(DashboardSummaryCriteria.class),eq(1));
    }
}
