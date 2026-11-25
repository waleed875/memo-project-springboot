package io.github.waleed875.memo.modules.dashboard.api;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.github.waleed875.memo.modules.dashboard.application.*;
import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.platform.security.SessionUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
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
class InternalDashboardUnifiedControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean SessionUserResolver sessions;
    @MockitoBean DashboardUnifiedService service;

    private void actor(String role,UUID id) {
        when(sessions.resolve(any(HttpServletRequest.class))).thenReturn(
            Optional.of(new UserProfile(id,"test","Tester",role)));
    }

    @Test void sessionAndRoleAreRequiredBeforeDatabaseWork() throws Exception {
        when(sessions.resolve(any(HttpServletRequest.class))).thenReturn(Optional.empty());
        mvc.perform(get("/api/internal/dashboard/unified")).andExpect(status().isUnauthorized());
        for(String role:List.of("REVIEWER","VIEWER","DATA_ENTRY")) {
            actor(role,UUID.randomUUID());
            when(service.read(any(),any(),anyInt()))
                .thenThrow(new io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException());
            mvc.perform(get("/api/internal/dashboard/unified")).andExpect(status().isForbidden());
        }
        verify(service,times(3)).read(any(),any(),anyInt());
    }

    @Test void invalidFiltersReturn400WithoutServiceExecution() throws Exception {
        actor("ADMIN",UUID.randomUUID());
        mvc.perform(get("/api/internal/dashboard/unified").param("from","bad"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/internal/dashboard/unified")
            .param("from","2026-11-21").param("to","2026-11-20"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/internal/dashboard/unified").param("status","WRONG"))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test void internalCompositionReturnsExplicitParityGapsAndLegacyEndpointStaysClosed()
        throws Exception {
        UUID admin=UUID.randomUUID();
        actor("ADMIN",admin);
        when(service.read(any(),any(),eq(10))).thenAnswer(invocation->{
            DashboardSummaryCriteria f=invocation.getArgument(1);
            return new DashboardUnifiedSnapshot(
                null,null,null,null,null,null,null,null,null,f,
                OffsetDateTime.parse("2026-11-25T12:00:00+03:00"),10,
                List.of("legacy fields remain unverified"));
        });
        mvc.perform(get("/api/internal/dashboard/unified").param("dateBasis","ArrivalDate"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.minSample").value(10))
            .andExpect(jsonPath("$.effectiveFilters.dateBasis").value("ArrivalDate"))
            .andExpect(jsonPath("$.parityGaps[0]").value("legacy fields remain unverified"));
        mvc.perform(get("/api/dashboard/snapshot")).andExpect(status().isForbidden());
    }
}
