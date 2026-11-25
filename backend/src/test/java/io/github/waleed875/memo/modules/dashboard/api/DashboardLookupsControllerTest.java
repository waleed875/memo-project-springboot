package io.github.waleed875.memo.modules.dashboard.api;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.github.waleed875.memo.modules.dashboard.application.DashboardLookups;
import io.github.waleed875.memo.modules.dashboard.application.DashboardLookupsReader;
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
class DashboardLookupsControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean SessionUserResolver sessions;
    @MockitoBean DashboardLookupsReader reader;

    private void actor(String role, UUID id) {
        when(sessions.resolve(any(HttpServletRequest.class))).thenReturn(
            Optional.of(new UserProfile(id, "tester", "Tester", role)));
    }

    @Test void noSessionIs401AndCannotReachDatabase() throws Exception {
        when(sessions.resolve(any(HttpServletRequest.class))).thenReturn(Optional.empty());
        mvc.perform(get("/api/dashboard/lookups")).andExpect(status().isUnauthorized());
        verifyNoInteractions(reader);
    }

    @Test void dataEntryReviewerAndViewerCannotReadAdminLookups() throws Exception {
        for (String role : List.of("DATA_ENTRY", "REVIEWER", "VIEWER", "INVALID")) {
            actor(role, UUID.randomUUID());
            mvc.perform(get("/api/dashboard/lookups")).andExpect(status().isForbidden());
        }
        verifyNoInteractions(reader);
    }

    @Test void adminGetsExactFourCamelCaseArraysWithSafeCacheHeaders() throws Exception {
        UUID admin = UUID.randomUUID();
        UUID company = UUID.randomUUID();
        UUID factory = UUID.randomUUID();
        UUID line = UUID.randomUUID();
        UUID type = UUID.randomUUID();
        actor("ADMIN", admin);
        when(reader.read(admin)).thenReturn(new DashboardLookups(
            List.of(new DashboardLookups.CompanyLookup(company, "شركة")),
            List.of(new DashboardLookups.FactoryLookup(factory, company, "مصنع")),
            List.of(new DashboardLookups.ProductionLineLookup(line, factory, "خط")),
            List.of(new DashboardLookups.NoteTypeLookup(type, "ENTRY", "نوع"))));
        mvc.perform(get("/api/dashboard/lookups"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.companies[0].id").value(company.toString()))
            .andExpect(jsonPath("$.factories[0].companyId").value(company.toString()))
            .andExpect(jsonPath("$.productionLines[0].factoryId").value(factory.toString()))
            .andExpect(jsonPath("$.noteTypes[0].code").value("ENTRY"))
            .andExpect(jsonPath("$.noteTypes[0].nameAr").value("نوع"));
        verify(reader).read(admin);
    }

    @Test void otherDashboardRoutesRemainDenied() throws Exception {
        actor("ADMIN", UUID.randomUUID());
        mvc.perform(get("/api/dashboard/snapshot")).andExpect(status().isForbidden());
        mvc.perform(get("/api/dashboard/company-metrics")).andExpect(status().isForbidden());
        mvc.perform(get("/api/dashboard/review-queue")).andExpect(status().isForbidden());
        verifyNoInteractions(reader);
    }
}
