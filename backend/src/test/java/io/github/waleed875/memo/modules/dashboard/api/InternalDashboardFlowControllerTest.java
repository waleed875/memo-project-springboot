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
class InternalDashboardFlowControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean SessionUserResolver sessions;
    @MockitoBean DashboardFlowReader reader;

    private void actor(String role,UUID id) {
        when(sessions.resolve(any(HttpServletRequest.class))).thenReturn(
            Optional.of(new UserProfile(id,"test","Test",role)));
    }

    @Test void noSessionOrAdminRoleDeniesAllData() throws Exception {
        when(sessions.resolve(any(HttpServletRequest.class))).thenReturn(Optional.empty());
        mvc.perform(get("/api/internal/dashboard/flow")).andExpect(status().isUnauthorized());
        for(String role:List.of("VIEWER","REVIEWER","DATA_ENTRY")) {
            actor(role,UUID.randomUUID());
            mvc.perform(get("/api/internal/dashboard/flow")).andExpect(status().isForbidden());
        }
        verifyNoInteractions(reader);
    }

    @Test void malformedDatesAndRangesAreRejected() throws Exception {
        actor("ADMIN",UUID.randomUUID());
        mvc.perform(get("/api/internal/dashboard/flow").param("from","invalid"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/internal/dashboard/flow")
            .param("from","2026-11-25").param("to","2026-11-24"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/internal/dashboard/flow").param("status","NOT_A_STATE"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/internal/dashboard/flow")
            .param("from","2000-01-01").param("to","2026-11-25"))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(reader);
    }

    @Test void responsePreservesLegacyFieldNamesWithoutOpeningPublicSnapshot() throws Exception {
        UUID admin=UUID.randomUUID();
        actor("ADMIN",admin);
        when(reader.read(eq(admin),any(DashboardSummaryCriteria.class))).thenAnswer(call->
            new DashboardFlowSnapshot(
                List.of(new DashboardFlowSnapshot.IncomingVsApprovedItem(
                    "2026-11","نوفمبر 2026","نوفمبر 2026",3,1)),
                List.of(new DashboardFlowSnapshot.DataCompletenessItem(
                    "missing-writing-date","دون تاريخ كتابة",2)),
                call.getArgument(1)));
        mvc.perform(get("/api/internal/dashboard/flow")
            .param("dateBasis","ArrivalDate").param("from","2026-11-01")
            .param("to","2026-11-30"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.incomingVsApproved[0].incoming").value(3))
            .andExpect(jsonPath("$.incomingVsApproved[0].approved").value(1))
            .andExpect(jsonPath("$.incomingVsApproved[0].periodKey").value("2026-11"))
            .andExpect(jsonPath("$.dataCompleteness[0].code").value("missing-writing-date"))
            .andExpect(jsonPath("$.effectiveFilters.dateBasis").value("ArrivalDate"));
        mvc.perform(get("/api/dashboard/snapshot")).andExpect(status().isForbidden());
        verify(reader).read(eq(admin),any(DashboardSummaryCriteria.class));
    }
}
