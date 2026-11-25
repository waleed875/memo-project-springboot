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
class InternalDashboardActivityControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean SessionUserResolver sessions;
    @MockitoBean DashboardActivityReader reader;

    private void actor(String role,UUID id) {
        when(sessions.resolve(any(HttpServletRequest.class))).thenReturn(
            Optional.of(new UserProfile(id,"test","Test",role)));
    }

    @Test void noSessionOrAdminRoleDeniesAllData() throws Exception {
        when(sessions.resolve(any(HttpServletRequest.class))).thenReturn(Optional.empty());
        mvc.perform(get("/api/internal/dashboard/activity")).andExpect(status().isUnauthorized());
        for(String role:List.of("DATA_ENTRY","VIEWER","REVIEWER")) {
            actor(role,UUID.randomUUID());
            mvc.perform(get("/api/internal/dashboard/activity")).andExpect(status().isForbidden());
        }
        verifyNoInteractions(reader);
    }

    @Test void invalidPeriodDateAndStatusNeverReadDb() throws Exception {
        actor("ADMIN",UUID.randomUUID());
        mvc.perform(get("/api/internal/dashboard/activity").param("from","bad"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/internal/dashboard/activity")
            .param("from","2026-11-25").param("to","2026-11-24"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/internal/dashboard/activity").param("status","UNKNOWN"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/internal/dashboard/activity")
            .param("from","2001-01-01").param("to","2026-11-25"))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(reader);
    }

    @Test void adminReceivesLegacyNamedSlicesAndPublicSnapshotRemainsClosed() throws Exception {
        UUID admin=UUID.randomUUID(),note=UUID.randomUUID();
        actor("ADMIN",admin);
        when(reader.read(eq(admin),any(),any(LocalDate.class))).thenAnswer(call ->{
            DashboardSummaryCriteria f=call.getArgument(1);
            return new DashboardActivitySnapshot(
                List.of(new DashboardActivitySnapshot.DuplicateTrendItem(
                    "2026-11","نوفمبر 2026","نوفمبر 2026",1,2)),
                List.of(new DashboardActivitySnapshot.DuplicateBreakdownItem(
                    "COMPANY_RESEND","إعادة إرسال من الشركة",1)),
                List.of(new DashboardActivitySnapshot.RecentNoteItem(
                    note,"123","شركة",null,null,"نوع","APPROVED","معتمدة",
                    LocalDate.of(2026,11,19),LocalDate.of(2026,11,25),true)),
                List.of(new DashboardActivitySnapshot.BacklogAgeItem(
                    "1-3-days","1-3 أيام",1)),f);
        });
        mvc.perform(get("/api/internal/dashboard/activity")
            .param("from","2026-11-01").param("to","2026-11-30")
            .param("dateBasis","ArrivalDate"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.duplicateTrend[0].periodKey").value("2026-11"))
            .andExpect(jsonPath("$.duplicateTrend[0].companyResend").value(1))
            .andExpect(jsonPath("$.duplicateBreakdown[0].classification").value("COMPANY_RESEND"))
            .andExpect(jsonPath("$.recentNotes[0].id").value(note.toString()))
            .andExpect(jsonPath("$.recentNotes[0].isLate").value(true))
            .andExpect(jsonPath("$.backlogByAge[0].code").value("1-3-days"))
            .andExpect(jsonPath("$.effectiveFilters.dateBasis").value("ArrivalDate"));
        mvc.perform(get("/api/dashboard/snapshot")).andExpect(status().isForbidden());
        verify(reader).read(eq(admin),any(DashboardSummaryCriteria.class),any(LocalDate.class));
    }
}
